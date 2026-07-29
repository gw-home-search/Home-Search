#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/recover-budget-production-tainted-ssm.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

mkdir -p "${temp_dir}/bin"
cat >"${temp_dir}/bin/terraform" <<'FAKE_TERRAFORM'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
if [[ "${args}" == *' state pull '* ]]; then
  if [[ -s "${RECOVERY_CALLS_FILE}" ]]; then
    jq '(.resources[].instances[] | select(.status == "tainted") | .status) = "ready"' <<<"${RECOVERY_STATE_JSON}"
  else
    printf '%s\n' "${RECOVERY_STATE_JSON}"
  fi
elif [[ "${args}" == *' untaint '* ]]; then
  printf '%s\n' "${*: -1}" >>"${RECOVERY_CALLS_FILE}"
else
  echo "unexpected terraform call:${args}" >&2
  exit 64
fi
FAKE_TERRAFORM
chmod +x "${temp_dir}/bin/terraform"

cat >"${temp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
if [[ "${args}" == *' ssm describe-parameters '* ]]; then
  filter=''
  while (($#)); do
    if [[ "$1" == '--parameter-filters' ]]; then
      filter="$2"
      break
    fi
    shift
  done
  name="${filter##*Values=}"
  parameter_type=SecureString
  if [[ "${RECOVERY_SCENARIO}" == bad-type && "${name}" == */user/oauth/kakao-client-secret ]]; then
    parameter_type=String
  fi
  jq -cn --arg name "${name}" --arg type "${parameter_type}" \
    '{Parameters:[{Name:$name,Type:$type,DataType:"text",Tier:"Standard"}]}'
elif [[ "${args}" == *' ssm list-tags-for-resource '* ]]; then
  if [[ "${RECOVERY_SCENARIO}" == bad-tags && "${args}" == *'/user/oauth/kakao-client-secret'* ]]; then
    printf '%s\n' '{"TagList":[{"Key":"DataClass","Value":"public"}]}'
  else
    printf '%s\n' '{"TagList":[{"Key":"DataClass","Value":"secret"},{"Key":"ParameterStatus","Value":"out-of-band"}]}'
  fi
elif [[ "${args}" == *' sts get-caller-identity '* ]]; then
  printf '%s\n' '123456789012'
elif [[ "${args}" == *' ec2 describe-vpcs '* ]]; then
  printf '%s\n' '{"Vpcs":[{"VpcId":"vpc-0123456789abcdef0","State":"available","IsDefault":false,"CidrBlock":"10.44.0.0/24","Tags":[{"Key":"Project","Value":"home-search"},{"Key":"Environment","Value":"budget-production"},{"Key":"ManagedBy","Value":"terraform"},{"Key":"Name","Value":"home-search-budget-production-vpc"}]}]}'
elif [[ "${args}" == *' ec2 describe-internet-gateways '* ]]; then
  if [[ "${RECOVERY_SCENARIO}" == bad-igw-tags ]]; then
    tags='[{"Key":"Project","Value":"foreign"}]'
  else
    tags='[{"Key":"Project","Value":"home-search"},{"Key":"Environment","Value":"budget-production"},{"Key":"ManagedBy","Value":"terraform"},{"Key":"Name","Value":"home-search-budget-production-igw"}]'
  fi
  jq -cn --argjson tags "${tags}" \
    '{InternetGateways:[{InternetGatewayId:"igw-0123456789abcdef0",Attachments:[],Tags:$tags}]}'
elif [[ "${args}" == *' ec2 describe-security-groups '* ]]; then
  group_id=''
  while (($#)); do
    if [[ "$1" == '--group-ids' ]]; then
      group_id="$2"
      break
    fi
    shift
  done
  if [[ "${group_id}" == 'sg-0123456789abcdef0' ]]; then
    group_name='home-search-budget-production-host'
    description='Budget production public host; no SSH, database, cache, or admin ingress'
    tags='[{"Key":"Project","Value":"home-search"},{"Key":"Environment","Value":"budget-production"},{"Key":"ManagedBy","Value":"terraform"},{"Key":"Name","Value":"home-search-budget-production-host"}]'
  else
    group_name='home-search-budget-production-recovery'
    description='Ephemeral recovery rehearsal; intentionally no ingress'
    tags='[{"Key":"Project","Value":"home-search"},{"Key":"Environment","Value":"budget-production"},{"Key":"ManagedBy","Value":"terraform"},{"Key":"Service","Value":"recovery"},{"Key":"Ingress","Value":"none"}]'
  fi
  ingress='[]'
  if [[ "${RECOVERY_SCENARIO}" == bad-sg-ingress && "${group_id}" == 'sg-0123456789abcdef0' ]]; then
    ingress='[{"IpProtocol":"tcp","FromPort":22,"ToPort":22,"IpRanges":[{"CidrIp":"0.0.0.0/0"}]}]'
  fi
  jq -cn \
    --arg id "${group_id}" --arg name "${group_name}" --arg description "${description}" \
    --argjson ingress "${ingress}" --argjson tags "${tags}" \
    '{SecurityGroups:[{GroupId:$id,GroupName:$name,VpcId:"vpc-0123456789abcdef0",Description:$description,IpPermissions:$ingress,Tags:$tags}]}'
elif [[ "${args}" == *' s3api head-bucket '* ]]; then
  :
elif [[ "${args}" == *' s3api get-bucket-location '* ]]; then
  if [[ "${RECOVERY_SCENARIO}" == bad-bucket-region ]]; then
    printf '%s\n' '{"LocationConstraint":"us-east-1"}'
  else
    printf '%s\n' '{"LocationConstraint":"ap-northeast-2"}'
  fi
else
  echo "unexpected aws call:${args}" >&2
  exit 64
fi
FAKE_AWS
chmod +x "${temp_dir}/bin/aws"

runtime_state='{
  "resources": [{
    "mode": "managed",
    "type": "aws_ssm_parameter",
    "name": "runtime",
    "instances": [
      {"index_key":"postgres/superuser-password","status":"tainted","attributes":{"name":"/home-search/budget-production/postgres/superuser-password"}},
      {"index_key":"user/oauth/kakao-client-secret","status":"tainted","attributes":{"name":"/home-search/budget-production/user/oauth/kakao-client-secret"}}
    ]
  }]
}'

foundation_state='{
  "resources": [
    {
      "mode":"managed",
      "type":"aws_internet_gateway",
      "name":"this",
      "instances":[{"index_key":0,"status":"tainted","attributes":{"id":"igw-0123456789abcdef0","vpc_id":"vpc-0123456789abcdef0"}}]
    },
    {
      "mode":"managed",
      "type":"aws_s3_bucket",
      "name":"reference_raw",
      "instances":[{"index_key":0,"status":"tainted","attributes":{"id":"home-search-budget-production-reference-raw-123456789012","bucket":"home-search-budget-production-reference-raw-123456789012"}}]
    },
    {
      "mode":"managed",
      "type":"aws_security_group",
      "name":"host",
      "instances":[{"index_key":0,"status":"tainted","attributes":{"id":"sg-0123456789abcdef0","name":"home-search-budget-production-host","description":"Budget production public host; no SSH, database, cache, or admin ingress","vpc_id":"vpc-0123456789abcdef0"}}]
    },
    {
      "mode":"managed",
      "type":"aws_security_group",
      "name":"recovery",
      "instances":[{"index_key":0,"status":"tainted","attributes":{"id":"sg-0fedcba9876543210","name":"home-search-budget-production-recovery","description":"Ephemeral recovery rehearsal; intentionally no ingress","vpc_id":"vpc-0123456789abcdef0"}}]
    }
  ]
}'
combined_state="$(jq -c --argjson foundation "${foundation_state}" '.resources += $foundation.resources' <<<"${runtime_state}")"

run_recovery() {
  local scenario="$1"
  local state_json="$2"
  : >"${temp_dir}/calls"
  RECOVERY_SCENARIO="${scenario}" RECOVERY_STATE_JSON="${state_json}" \
    RECOVERY_CALLS_FILE="${temp_dir}/calls" PATH="${temp_dir}/bin:${PATH}" \
    "${script}" "${root}/infra/terraform/budget-production" ap-northeast-2
}

run_recovery success "${runtime_state}" >"${temp_dir}/success.out"
grep -Fxq 'aws_ssm_parameter.runtime["postgres/superuser-password"]' "${temp_dir}/calls"
grep -Fxq 'aws_ssm_parameter.runtime["user/oauth/kakao-client-secret"]' "${temp_dir}/calls"
[[ "$(wc -l <"${temp_dir}/calls" | tr -d ' ')" -eq 2 ]]
grep -Fq '상태: Pass' "${temp_dir}/success.out"

run_recovery success "${combined_state}" >"${temp_dir}/foundation-success.out"
grep -Fxq 'aws_internet_gateway.this[0]' "${temp_dir}/calls"
grep -Fxq 'aws_s3_bucket.reference_raw[0]' "${temp_dir}/calls"
grep -Fxq 'aws_security_group.host[0]' "${temp_dir}/calls"
grep -Fxq 'aws_security_group.recovery[0]' "${temp_dir}/calls"
grep -Fxq 'aws_ssm_parameter.runtime["postgres/superuser-password"]' "${temp_dir}/calls"
grep -Fxq 'aws_ssm_parameter.runtime["user/oauth/kakao-client-secret"]' "${temp_dir}/calls"
[[ "$(wc -l <"${temp_dir}/calls" | tr -d ' ')" -eq 6 ]]
grep -Fq 'verified taint 6개' "${temp_dir}/foundation-success.out"

if run_recovery bad-igw-tags "${combined_state}" >"${temp_dir}/igw.out" 2>"${temp_dir}/igw.err"; then
  echo '상태: Fail - 소유 태그가 다른 internet gateway를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'internet gateway' "${temp_dir}/igw.err"

if run_recovery bad-sg-ingress "${combined_state}" >"${temp_dir}/sg.out" 2>"${temp_dir}/sg.err"; then
  echo '상태: Fail - 예상하지 않은 ingress가 있는 security group을 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'security group' "${temp_dir}/sg.err"

if run_recovery bad-bucket-region "${combined_state}" >"${temp_dir}/bucket.out" 2>"${temp_dir}/bucket.err"; then
  echo '상태: Fail - 다른 region의 reference bucket을 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'reference bucket' "${temp_dir}/bucket.err"

foreign_state="$(jq '.resources += [{"mode":"managed","type":"aws_instance","name":"host","instances":[{"status":"tainted","attributes":{"id":"i-0123456789abcdef0"}}]}]' <<<"${runtime_state}")"
if run_recovery foreign-taint "${foreign_state}" >"${temp_dir}/foreign.out" 2>"${temp_dir}/foreign.err"; then
  echo '상태: Fail - 허용 목록 밖의 tainted resource를 거부하지 않았습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq '허용되지 않은 tainted resource' "${temp_dir}/foreign.err"

if run_recovery bad-type "${runtime_state}" >"${temp_dir}/type.out" 2>"${temp_dir}/type.err"; then
  echo '상태: Fail - SecureString이 아닌 live parameter를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'SecureString' "${temp_dir}/type.err"

if run_recovery bad-tags "${runtime_state}" >"${temp_dir}/tags.out" 2>"${temp_dir}/tags.err"; then
  echo '상태: Fail - 보호 태그가 없는 live parameter를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq '보호 태그' "${temp_dir}/tags.err"

ready_state="$(jq '(.resources[].instances[].status) = "ready"' <<<"${runtime_state}")"
run_recovery no-taint "${ready_state}" >"${temp_dir}/ready.out"
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'verified tainted resource가 없습니다' "${temp_dir}/ready.out"

echo '상태: Pass - exact SSM/foundation taint 복구, foreign taint, live metadata fail-closed를 확인했습니다.'

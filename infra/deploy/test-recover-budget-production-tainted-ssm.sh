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
grep -Fq 'tainted SSM parameter가 없습니다' "${temp_dir}/ready.out"

echo '상태: Pass - exact SSM taint 복구, foreign taint, live type/tag fail-closed를 확인했습니다.'

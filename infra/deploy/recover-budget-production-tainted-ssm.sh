#!/usr/bin/env bash
set -Eeuo pipefail

terraform_dir="${1:?budget-production Terraform directory is required}"
aws_region="${2:?AWS region is required}"
readonly parameter_prefix='/home-search/budget-production/'

fail() {
  echo "상태: Fail - $*" >&2
  exit 1
}

state_json="$(terraform -chdir="${terraform_dir}" state pull)"
tainted_json="$(
  jq -c '[
    .resources[]? as $resource
    | $resource.instances[]?
    | select(.status == "tainted")
    | {
        mode: $resource.mode,
        type: $resource.type,
        name: $resource.name,
        index_key: .index_key,
        id: .attributes.id,
        vpc_id: .attributes.vpc_id,
        live_name: .attributes.name,
        description: .attributes.description,
        bucket: .attributes.bucket
      }
  ]' <<<"${state_json}"
)"
tainted_count="$(jq 'length' <<<"${tainted_json}")"

if ((tainted_count == 0)); then
  echo '상태: Pass - 복구할 verified tainted resource가 없습니다.'
  exit 0
fi

if ! jq -e 'all(.[];
  .mode == "managed"
  and (
    (
      .type == "aws_ssm_parameter"
      and .name == "runtime"
      and (.index_key | type == "string")
      and (.live_name | type == "string")
    )
    or (
      (.index_key == 0)
      and (
        (.type == "aws_internet_gateway" and .name == "this")
        or (.type == "aws_s3_bucket" and .name == "reference_raw")
        or (.type == "aws_security_group" and (.name == "host" or .name == "recovery"))
      )
    )
  )
)' <<<"${tainted_json}" >/dev/null; then
  fail '허용되지 않은 tainted resource가 있어 state 복구를 중단합니다.'
fi

addresses=()
verified_vpc_id=''
verify_budget_vpc() {
  local vpc_id="$1"
  [[ "${vpc_id}" =~ ^vpc-[0-9a-f]{8,17}$ ]] || fail 'foundation resource의 VPC ID 형식이 올바르지 않습니다.'
  if [[ -n "${verified_vpc_id}" ]]; then
    [[ "${verified_vpc_id}" == "${vpc_id}" ]] || fail 'foundation resource의 VPC ID가 서로 다릅니다.'
    return
  fi

  local vpc_metadata
  vpc_metadata="$(aws ec2 describe-vpcs --region "${aws_region}" --vpc-ids "${vpc_id}" --output json)"
  if ! jq -e --arg id "${vpc_id}" '
    (.Vpcs | length) == 1
    and .Vpcs[0].VpcId == $id
    and .Vpcs[0].State == "available"
    and .Vpcs[0].IsDefault == false
    and .Vpcs[0].CidrBlock == "10.44.0.0/24"
    and any(.Vpcs[0].Tags[]?; .Key == "Project" and .Value == "home-search")
    and any(.Vpcs[0].Tags[]?; .Key == "Environment" and .Value == "budget-production")
    and any(.Vpcs[0].Tags[]?; .Key == "ManagedBy" and .Value == "terraform")
    and any(.Vpcs[0].Tags[]?; .Key == "Name" and .Value == "home-search-budget-production-vpc")
  ' <<<"${vpc_metadata}" >/dev/null; then
    fail 'foundation resource의 live VPC가 exact budget-production VPC가 아닙니다.'
  fi
  verified_vpc_id="${vpc_id}"
}

while IFS= read -r item; do
  resource_type="$(jq -er '.type' <<<"${item}")"
  resource_name="$(jq -er '.name' <<<"${item}")"
  if [[ "${resource_type}" == 'aws_ssm_parameter' ]]; then
    parameter_key="$(jq -er '.index_key' <<<"${item}")"
    parameter_name="$(jq -er '.live_name' <<<"${item}")"
    [[ "${parameter_key}" =~ ^[a-z0-9][a-z0-9/-]*[a-z0-9]$ ]] ||
      fail "허용되지 않은 SSM parameter key입니다: ${parameter_key}"
    [[ "${parameter_key}" != *'//'* && "${parameter_key}" != *'..'* ]] ||
      fail "허용되지 않은 SSM parameter key입니다: ${parameter_key}"
    expected_name="${parameter_prefix}${parameter_key}"
    [[ "${parameter_name}" == "${expected_name}" ]] ||
      fail "state parameter name이 budget-production prefix와 일치하지 않습니다: ${parameter_key}"

    metadata="$(
      aws ssm describe-parameters --region "${aws_region}" \
        --parameter-filters "Key=Name,Option=Equals,Values=${parameter_name}" \
        --max-results 2 --output json
    )"
    if ! jq -e --arg name "${parameter_name}" '
      (.Parameters | length) == 1
      and .Parameters[0].Name == $name
      and .Parameters[0].Type == "SecureString"
    ' <<<"${metadata}" >/dev/null; then
      fail "live parameter가 정확한 SecureString이 아닙니다: ${parameter_key}"
    fi

    tags="$(
      aws ssm list-tags-for-resource --region "${aws_region}" \
        --resource-type Parameter --resource-id "${parameter_name}" --output json
    )"
    if ! jq -e '
      any(.TagList[]?; .Key == "DataClass" and .Value == "secret")
      and any(.TagList[]?; .Key == "ParameterStatus" and .Value == "out-of-band")
    ' <<<"${tags}" >/dev/null; then
      fail "live parameter 보호 태그가 일치하지 않습니다: ${parameter_key}"
    fi

    addresses+=("aws_ssm_parameter.runtime[\"${parameter_key}\"]")
  elif [[ "${resource_type}" == 'aws_internet_gateway' ]]; then
    resource_id="$(jq -er '.id' <<<"${item}")"
    vpc_id="$(jq -er '.vpc_id' <<<"${item}")"
    [[ "${resource_id}" =~ ^igw-[0-9a-f]{8,17}$ ]] || fail 'internet gateway ID 형식이 올바르지 않습니다.'
    verify_budget_vpc "${vpc_id}"
    metadata="$(aws ec2 describe-internet-gateways --region "${aws_region}" --internet-gateway-ids "${resource_id}" --output json)"
    if ! jq -e --arg id "${resource_id}" --arg vpc "${vpc_id}" '
      (.InternetGateways | length) == 1
      and .InternetGateways[0].InternetGatewayId == $id
      and (.InternetGateways[0].Attachments | length) <= 1
      and all(.InternetGateways[0].Attachments[]?; .VpcId == $vpc and (.State == "available" or .State == "attaching"))
      and any(.InternetGateways[0].Tags[]?; .Key == "Project" and .Value == "home-search")
      and any(.InternetGateways[0].Tags[]?; .Key == "Environment" and .Value == "budget-production")
      and any(.InternetGateways[0].Tags[]?; .Key == "ManagedBy" and .Value == "terraform")
      and any(.InternetGateways[0].Tags[]?; .Key == "Name" and .Value == "home-search-budget-production-igw")
    ' <<<"${metadata}" >/dev/null; then
      fail 'live internet gateway가 exact budget-production resource가 아닙니다.'
    fi
    addresses+=("aws_internet_gateway.this[0]")
  elif [[ "${resource_type}" == 'aws_s3_bucket' ]]; then
    resource_id="$(jq -er '.id' <<<"${item}")"
    bucket_name="$(jq -er '.bucket' <<<"${item}")"
    account_id="$(aws sts get-caller-identity --query Account --output text)"
    expected_bucket="home-search-budget-production-reference-raw-${account_id}"
    [[ "${resource_id}" == "${expected_bucket}" && "${bucket_name}" == "${expected_bucket}" ]] ||
      fail 'state reference bucket 이름이 exact budget-production bucket이 아닙니다.'
    aws s3api head-bucket --bucket "${bucket_name}" >/dev/null
    metadata="$(aws s3api get-bucket-location --bucket "${bucket_name}" --output json)"
    if ! jq -e --arg region "${aws_region}" '.LocationConstraint == $region' <<<"${metadata}" >/dev/null; then
      fail 'live reference bucket region이 budget-production region과 일치하지 않습니다.'
    fi
    addresses+=("aws_s3_bucket.reference_raw[0]")
  elif [[ "${resource_type}" == 'aws_security_group' ]]; then
    resource_id="$(jq -er '.id' <<<"${item}")"
    vpc_id="$(jq -er '.vpc_id' <<<"${item}")"
    live_name="$(jq -er '.live_name' <<<"${item}")"
    description="$(jq -er '.description' <<<"${item}")"
    [[ "${resource_id}" =~ ^sg-[0-9a-f]{8,17}$ ]] || fail 'security group ID 형식이 올바르지 않습니다.'
    verify_budget_vpc "${vpc_id}"
    if [[ "${resource_name}" == 'host' ]]; then
      expected_name='home-search-budget-production-host'
      expected_description='Budget production public host; no SSH, database, cache, or admin ingress'
      expected_tag_key='Name'
      expected_tag_value="${expected_name}"
    else
      expected_name='home-search-budget-production-recovery'
      expected_description='Ephemeral recovery rehearsal; intentionally no ingress'
      expected_tag_key='Ingress'
      expected_tag_value='none'
    fi
    [[ "${live_name}" == "${expected_name}" && "${description}" == "${expected_description}" ]] ||
      fail "state security group metadata가 exact budget-production ${resource_name} group이 아닙니다."
    metadata="$(aws ec2 describe-security-groups --region "${aws_region}" --group-ids "${resource_id}" --output json)"
    if ! jq -e \
      --arg id "${resource_id}" --arg vpc "${vpc_id}" --arg name "${expected_name}" \
      --arg description "${expected_description}" --arg tag_key "${expected_tag_key}" --arg tag_value "${expected_tag_value}" '
      (.SecurityGroups | length) == 1
      and .SecurityGroups[0].GroupId == $id
      and .SecurityGroups[0].VpcId == $vpc
      and .SecurityGroups[0].GroupName == $name
      and .SecurityGroups[0].Description == $description
      and (.SecurityGroups[0].IpPermissions | length) == 0
      and any(.SecurityGroups[0].Tags[]?; .Key == "Project" and .Value == "home-search")
      and any(.SecurityGroups[0].Tags[]?; .Key == "Environment" and .Value == "budget-production")
      and any(.SecurityGroups[0].Tags[]?; .Key == "ManagedBy" and .Value == "terraform")
      and any(.SecurityGroups[0].Tags[]?; .Key == $tag_key and .Value == $tag_value)
    ' <<<"${metadata}" >/dev/null; then
      fail "live security group이 exact ingress-free budget-production ${resource_name} group이 아닙니다."
    fi
    addresses+=("aws_security_group.${resource_name}[0]")
  fi
done < <(jq -c '.[]' <<<"${tainted_json}")

for address in "${addresses[@]}"; do
  terraform -chdir="${terraform_dir}" untaint "${address}"
done

remaining_tainted="$(
  terraform -chdir="${terraform_dir}" state pull |
    jq '[.resources[]?.instances[]? | select(.status == "tainted")] | length'
)"
[[ "${remaining_tainted}" == 0 ]] ||
  fail "state에 tainted resource ${remaining_tainted}개가 남아 있습니다."

echo "상태: Pass - live resource를 변경하지 않고 verified taint ${tainted_count}개를 해제했습니다."

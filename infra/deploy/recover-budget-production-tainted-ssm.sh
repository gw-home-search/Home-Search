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
        parameter_name: .attributes.name
      }
  ]' <<<"${state_json}"
)"
tainted_count="$(jq 'length' <<<"${tainted_json}")"

if ((tainted_count == 0)); then
  echo '상태: Pass - 복구할 tainted SSM parameter가 없습니다.'
  exit 0
fi

if ! jq -e 'all(.[];
  .mode == "managed"
  and .type == "aws_ssm_parameter"
  and .name == "runtime"
  and (.index_key | type == "string")
  and (.parameter_name | type == "string")
)' <<<"${tainted_json}" >/dev/null; then
  fail '허용되지 않은 tainted resource가 있어 state 복구를 중단합니다.'
fi

addresses=()
while IFS= read -r item; do
  parameter_key="$(jq -er '.index_key' <<<"${item}")"
  parameter_name="$(jq -er '.parameter_name' <<<"${item}")"
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

echo "상태: Pass - live resource를 변경하지 않고 verified SSM parameter taint ${tainted_count}개를 해제했습니다."

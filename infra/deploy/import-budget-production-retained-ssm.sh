#!/usr/bin/env bash
set -Eeuo pipefail

terraform_dir="${1:?budget-production Terraform directory is required}"
aws_region="${2:?AWS region is required}"
parameter_key="${3:?retained SSM parameter key is required}"

readonly allowed_parameter_key='property/apt-service-key'
readonly parameter_prefix='/home-search/budget-production/'

fail() {
  echo "상태: Fail - $*" >&2
  exit 1
}

[[ "${parameter_key}" == "${allowed_parameter_key}" ]] ||
  fail "허용되지 않은 retained SSM parameter key입니다: ${parameter_key}"

parameter_name="${parameter_prefix}${parameter_key}"
resource_address="aws_ssm_parameter.runtime[\"${parameter_key}\"]"

state_contains_parameter() {
  terraform -chdir="${terraform_dir}" state pull |
    jq -e --arg key "${parameter_key}" --arg name "${parameter_name}" '
      any(.resources[]?;
        .mode == "managed"
        and .type == "aws_ssm_parameter"
        and .name == "runtime"
        and any(.instances[]?;
          .index_key == $key
          and .attributes.name == $name
          and .attributes.value_wo_version == 1
        )
      )
    ' >/dev/null
}

if state_contains_parameter; then
  echo "상태: Pass - retained SSM parameter가 이미 Terraform state에 있습니다: ${parameter_key}"
  exit 0
fi

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

terraform -chdir="${terraform_dir}" import "${resource_address}" "${parameter_name}" >/dev/null
state_contains_parameter ||
  fail "import 뒤 retained SSM parameter의 name/value_wo_version state가 일치하지 않습니다: ${parameter_key}"

echo "상태: Pass - 값을 읽지 않고 retained SSM parameter를 Terraform state에 편입했습니다: ${parameter_key}"

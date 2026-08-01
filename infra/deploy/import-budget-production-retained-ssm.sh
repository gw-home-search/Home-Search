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
patched_state=""

cleanup() {
  if [[ -n "${patched_state}" && -f "${patched_state}" ]]; then
    unlink "${patched_state}"
  fi
}
trap cleanup EXIT

parameter_state_status() {
  terraform -chdir="${terraform_dir}" state pull |
    jq -er --arg key "${parameter_key}" --arg name "${parameter_name}" '
      [
        .resources[]?
        | select(
            .mode == "managed"
            and .type == "aws_ssm_parameter"
            and .name == "runtime"
          )
        | .instances[]?
        | select(.index_key == $key)
      ] as $matches
      | if ($matches | length) == 0 then "missing"
        elif ($matches | length) != 1 then "invalid"
        elif $matches[0].attributes.name != $name then "invalid"
        elif $matches[0].attributes.value_wo_version == 1 then "ready"
        elif $matches[0].attributes.value_wo_version == null then "version-missing"
        else "invalid"
        end
    '
}

patch_imported_write_only_version() {
  patched_state="$(mktemp)"
  terraform -chdir="${terraform_dir}" state pull |
    jq -e --arg key "${parameter_key}" --arg name "${parameter_name}" '
      [
        .resources
        | to_entries[]
        | select(
            .value.mode == "managed"
            and .value.type == "aws_ssm_parameter"
            and .value.name == "runtime"
          ) as $resource
        | $resource.value.instances
        | to_entries[]
        | select(.value.index_key == $key)
        | {
            resource_index: $resource.key,
            instance_index: .key,
            attributes: .value.attributes
          }
      ] as $matches
      | if .version != 4
          or (.serial | type) != "number"
          or (.lineage | type) != "string"
          or (.lineage | length) == 0
          or ($matches | length) != 1
          or $matches[0].attributes.name != $name
          or $matches[0].attributes.value_wo_version != null
        then error("retained SSM import state metadata mismatch")
        else
          .serial += 1
          | .resources[$matches[0].resource_index]
              .instances[$matches[0].instance_index]
              .attributes.value_wo_version = 1
        end
    ' >"${patched_state}"
  terraform -chdir="${terraform_dir}" state push "${patched_state}" >/dev/null
  unlink "${patched_state}"
  patched_state=""
}

initial_state_status="$(parameter_state_status)"
if [[ "${initial_state_status}" == ready ]]; then
  echo "상태: Pass - retained SSM parameter가 이미 Terraform state에 있습니다: ${parameter_key}"
  exit 0
fi
[[ "${initial_state_status}" == missing || "${initial_state_status}" == version-missing ]] ||
  fail "retained SSM parameter의 기존 state metadata가 일치하지 않습니다: ${parameter_key}"

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

if [[ "${initial_state_status}" == missing ]]; then
  terraform -chdir="${terraform_dir}" import "${resource_address}" "${parameter_name}" >/dev/null
fi
current_state_status="$(parameter_state_status)"
if [[ "${current_state_status}" == version-missing ]]; then
  patch_imported_write_only_version
  current_state_status="$(parameter_state_status)"
fi
[[ "${current_state_status}" == ready ]] ||
  fail "import 뒤 retained SSM parameter의 name/value_wo_version state가 일치하지 않습니다: ${parameter_key}"

echo "상태: Pass - 값을 읽지 않고 retained SSM parameter를 Terraform state에 편입했습니다: ${parameter_key}"

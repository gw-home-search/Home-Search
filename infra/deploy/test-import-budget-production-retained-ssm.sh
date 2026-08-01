#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/import-budget-production-retained-ssm.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

mkdir -p "${temp_dir}/bin"
cat >"${temp_dir}/bin/terraform" <<'FAKE_TERRAFORM'
#!/usr/bin/env bash
set -Eeuo pipefail
args=" $* "
if [[ "${args}" == *' state pull '* ]]; then
  if [[ -s "${IMPORT_PUSHED_STATE_FILE}" ]]; then
    cat "${IMPORT_PUSHED_STATE_FILE}"
  elif [[ "${IMPORT_STATE_MODE}" == present ]]; then
    printf '%s\n' "${IMPORT_PRESENT_STATE_JSON}"
  elif [[ "${IMPORT_STATE_MODE}" == imported ]]; then
    printf '%s\n' "${IMPORT_AFTER_STATE_JSON}"
  elif [[ -s "${IMPORT_CALLS_FILE}" ]]; then
    printf '%s\n' "${IMPORT_AFTER_STATE_JSON}"
  else
    printf '%s\n' '{"resources":[]}'
  fi
elif [[ "${args}" == *' import '* ]]; then
  printf '%s\n%s\n' "${*: -2:1}" "${*: -1}" >"${IMPORT_CALLS_FILE}"
elif [[ "${args}" == *' state push '* ]]; then
  cp "${*: -1}" "${IMPORT_PUSHED_STATE_FILE}"
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
  parameter_type=SecureString
  [[ "${IMPORT_SCENARIO}" == bad-type ]] && parameter_type=String
  jq -cn --arg type "${parameter_type}" \
    '{Parameters:[{Name:"/home-search/budget-production/property/apt-service-key",Type:$type}]}'
elif [[ "${args}" == *' ssm list-tags-for-resource '* ]]; then
  if [[ "${IMPORT_SCENARIO}" == bad-tags ]]; then
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

present_state='{
  "version":4,
  "serial":7,
  "lineage":"11111111-2222-3333-4444-555555555555",
  "resources":[{
    "mode":"managed",
    "type":"aws_ssm_parameter",
    "name":"runtime",
    "instances":[{
      "index_key":"property/apt-service-key",
      "attributes":{
        "name":"/home-search/budget-production/property/apt-service-key",
        "value_wo_version":1
      }
    }]
  }]
}'

imported_state="$(jq 'del(.resources[0].instances[0].attributes.value_wo_version)' <<<"${present_state}")"

run_import() {
  local scenario="$1"
  local state_mode="$2"
  : >"${temp_dir}/calls"
  : >"${temp_dir}/pushed-state.json"
  IMPORT_SCENARIO="${scenario}" IMPORT_STATE_MODE="${state_mode}" \
    IMPORT_PRESENT_STATE_JSON="${present_state}" IMPORT_AFTER_STATE_JSON="${imported_state}" \
    IMPORT_CALLS_FILE="${temp_dir}/calls" IMPORT_PUSHED_STATE_FILE="${temp_dir}/pushed-state.json" \
    PATH="${temp_dir}/bin:${PATH}" \
    "${script}" "${root}/infra/terraform/budget-production" ap-northeast-2 property/apt-service-key
}

run_import success missing >"${temp_dir}/success.out"
[[ "$(sed -n '1p' "${temp_dir}/calls")" == 'aws_ssm_parameter.runtime["property/apt-service-key"]' ]]
[[ "$(sed -n '2p' "${temp_dir}/calls")" == '/home-search/budget-production/property/apt-service-key' ]]
grep -Fq '값을 읽지 않고' "${temp_dir}/success.out"
jq -e '
  .serial == 8
  and .lineage == "11111111-2222-3333-4444-555555555555"
  and .resources[0].instances[0].attributes.value_wo_version == 1
' "${temp_dir}/pushed-state.json" >/dev/null

run_import success imported >"${temp_dir}/imported.out"
[[ ! -s "${temp_dir}/calls" ]]
jq -e '.resources[0].instances[0].attributes.value_wo_version == 1' \
  "${temp_dir}/pushed-state.json" >/dev/null

run_import success present >"${temp_dir}/present.out"
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq '이미 Terraform state에 있습니다' "${temp_dir}/present.out"

bad_version_state="$(jq '.resources[0].instances[0].attributes.value_wo_version = 2' <<<"${present_state}")"
: >"${temp_dir}/calls"
: >"${temp_dir}/pushed-state.json"
if IMPORT_SCENARIO=success IMPORT_STATE_MODE=present IMPORT_PRESENT_STATE_JSON="${bad_version_state}" \
  IMPORT_AFTER_STATE_JSON="${imported_state}" IMPORT_CALLS_FILE="${temp_dir}/calls" \
  IMPORT_PUSHED_STATE_FILE="${temp_dir}/pushed-state.json" \
  PATH="${temp_dir}/bin:${PATH}" \
  "${script}" "${root}/infra/terraform/budget-production" ap-northeast-2 property/apt-service-key \
  >"${temp_dir}/version.out" 2>"${temp_dir}/version.err"; then
  echo '상태: Fail - value_wo_version이 다른 retained parameter state를 허용했습니다.' >&2
  exit 1
fi
grep -Fq '기존 state metadata가 일치하지 않습니다' "${temp_dir}/version.err"

if run_import bad-type missing >"${temp_dir}/type.out" 2>"${temp_dir}/type.err"; then
  echo '상태: Fail - SecureString이 아닌 retained parameter를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq 'SecureString' "${temp_dir}/type.err"

if run_import bad-tags missing >"${temp_dir}/tags.out" 2>"${temp_dir}/tags.err"; then
  echo '상태: Fail - 보호 태그가 없는 retained parameter를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls" ]]
grep -Fq '보호 태그' "${temp_dir}/tags.err"

: >"${temp_dir}/pushed-state.json"
if IMPORT_SCENARIO=success IMPORT_STATE_MODE=missing IMPORT_PRESENT_STATE_JSON="${present_state}" \
  IMPORT_AFTER_STATE_JSON="${imported_state}" IMPORT_CALLS_FILE="${temp_dir}/calls" \
  IMPORT_PUSHED_STATE_FILE="${temp_dir}/pushed-state.json" \
  PATH="${temp_dir}/bin:${PATH}" \
  "${script}" "${root}/infra/terraform/budget-production" ap-northeast-2 user/oauth/kakao-client-secret \
  >"${temp_dir}/foreign.out" 2>"${temp_dir}/foreign.err"; then
  echo '상태: Fail - allowlist 밖 retained parameter를 허용했습니다.' >&2
  exit 1
fi
grep -Fq '허용되지 않은 retained SSM parameter key' "${temp_dir}/foreign.err"

! grep -Eq 'get-parameter|with-decryption' "${script}"
! grep -Eq 'state push[[:space:]]+(-force|--force)' "${script}"
echo '상태: Pass - retained SSM parameter의 exact import와 secret 비열람 경계를 확인했습니다.'

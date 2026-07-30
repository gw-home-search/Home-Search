#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root_dir}/infra/bootstrap/normalize-budget-generated-values.sh"
tmp_dir="$(mktemp -d)"
cleanup() {
  local status=$?
  if [[ "${status}" != '0' ]]; then
    find "${tmp_dir}" -maxdepth 1 -name '*.err' -type f -exec sed -n '1,120p' {} \; >&2 || true
  fi
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
  return "${status}"
}
trap cleanup EXIT

mkdir -p "${tmp_dir}/bin" "${tmp_dir}/state"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_ARGV_LOG}"
printf '\n' >>"${FAKE_AWS_ARGV_LOG}"
if [[ "${1:-} ${2:-}" == 'ssm get-parameter' ]]; then
  name=''
  for ((index = 1; index <= $#; index++)); do
    if [[ "${!index}" == '--name' ]]; then
      next=$((index + 1))
      name="${!next}"
    fi
  done
  state="${FAKE_AWS_STATE}/ssm${name//\//_}"
  jq -n --rawfile value "${state}" '{Parameter:{Value:$value}}'
  exit 0
fi
if [[ "${1:-} ${2:-}" == 'ssm put-parameter' ]]; then
  input=''
  for ((index = 1; index <= $#; index++)); do
    if [[ "${!index}" == '--cli-input-json' ]]; then
      next=$((index + 1))
      input="${!next}"
    fi
  done
  name="$(jq -er '.Name' "${input#file://}")"
  jq -j '.Value' "${input#file://}" >"${FAKE_AWS_STATE}/ssm${name//\//_}"
  exit 0
fi
exit 2
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"
export PATH="${tmp_dir}/bin:${PATH}"
export FAKE_AWS_ARGV_LOG="${tmp_dir}/aws.argv"
export FAKE_AWS_STATE="${tmp_dir}/state"
: >"${FAKE_AWS_ARGV_LOG}"

hex='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
for suffix in \
  postgres/superuser-password \
  postgres/property-runtime-password \
  postgres/property-migrator-password \
  postgres/property-importer-password \
  postgres/property-ai-reader-password \
  postgres/user-runtime-password \
  postgres/user-migrator-password \
  postgres/admin-runtime-password \
  postgres/admin-migrator-password \
  postgres/ai-runtime-password \
  postgres/ai-migrator-password \
  postgres/ai-importer-password \
  postgres/backup-password \
  valkey/admin-password \
  valkey/property-password \
  valkey/bff-password \
  edge/certificate-passphrase; do
  printf '%s\n' "${hex}" >"${FAKE_AWS_STATE}/ssm_home-search_budget-production_${suffix//\//_}"
done
for suffix in ai/property-dsn ai/reference-dsn ai/migrator-dsn; do
  printf 'stale' >"${FAKE_AWS_STATE}/ssm_home-search_budget-production_${suffix//\//_}"
done

BUDGET_PARAMETER_PREFIX=/home-search/budget-production \
  "${script}" >"${tmp_dir}/first.out" 2>"${tmp_dir}/first.err"
[[ "$(grep -c 'ssm put-parameter' "${FAKE_AWS_ARGV_LOG}")" == '20' ]]
[[ "$(wc -c <"${FAKE_AWS_STATE}/ssm_home-search_budget-production_valkey_admin-password" | tr -d '[:space:]')" == '64' ]]
grep -Fq "home_search_ai_reader:${hex}@172.31.255.1:15432/home_search?sslmode=require" \
  "${FAKE_AWS_STATE}/ssm_home-search_budget-production_ai_property-dsn"
! grep -Eq '[[:xdigit:]]{64}' "${FAKE_AWS_ARGV_LOG}" "${tmp_dir}/first.out" "${tmp_dir}/first.err"

puts_after_first="$(grep -c 'ssm put-parameter' "${FAKE_AWS_ARGV_LOG}")"
BUDGET_PARAMETER_PREFIX=/home-search/budget-production \
  "${script}" >"${tmp_dir}/second.out" 2>"${tmp_dir}/second.err"
[[ "$(grep -c 'ssm put-parameter' "${FAKE_AWS_ARGV_LOG}")" == "${puts_after_first}" ]]
grep -Fq 'parameter 0개' "${tmp_dir}/second.out"

printf 'invalid\nvalue\n' >"${FAKE_AWS_STATE}/ssm_home-search_budget-production_valkey_admin-password"
set +e
BUDGET_PARAMETER_PREFIX=/home-search/budget-production \
  "${script}" >"${tmp_dir}/invalid.out" 2>"${tmp_dir}/invalid.err"
invalid_code=$?
set -e
[[ "${invalid_code}" == '1' ]]
[[ "$(grep -c 'ssm put-parameter' "${FAKE_AWS_ARGV_LOG}")" == "${puts_after_first}" ]]

echo '상태: Pass - budget generated value 정규화, 멱등성, invalid fail-closed를 확인했습니다.'

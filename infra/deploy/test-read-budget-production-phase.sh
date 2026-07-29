#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/read-budget-production-phase.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

mkdir -p "${temp_dir}/bin"
cat >"${temp_dir}/bin/terraform" <<'FAKE_TERRAFORM'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$*" == *'-chdir='*' state pull' ]]
case "${TEST_TERRAFORM_MODE:?}" in
  no-state)
    echo 'No state file was found!' >&2
    exit 1
    ;;
  foundation)
    printf '%s\n' '{"version":4,"outputs":{"deployment_phase":{"value":"foundation","type":"string"}}}'
    ;;
  denied)
    echo 'AccessDenied: budget state read rejected' >&2
    exit 1
    ;;
  missing-phase)
    printf '%s\n' '{"version":4,"outputs":{}}'
    ;;
  *)
    exit 64
    ;;
esac
FAKE_TERRAFORM
chmod +x "${temp_dir}/bin/terraform"

phase="$(TEST_TERRAFORM_MODE=no-state PATH="${temp_dir}/bin:${PATH}" "${script}" "${root}/infra/terraform/budget-production")"
[[ "${phase}" == registry ]]

phase="$(TEST_TERRAFORM_MODE=foundation PATH="${temp_dir}/bin:${PATH}" "${script}" "${root}/infra/terraform/budget-production")"
[[ "${phase}" == foundation ]]

if TEST_TERRAFORM_MODE=denied PATH="${temp_dir}/bin:${PATH}" "${script}" \
  "${root}/infra/terraform/budget-production" >"${temp_dir}/denied.out" 2>"${temp_dir}/denied.err"; then
  echo '상태: Fail - state AccessDenied가 registry phase로 완화됐습니다.' >&2
  exit 1
fi
grep -Fq 'AccessDenied' "${temp_dir}/denied.err"
[[ ! -s "${temp_dir}/denied.out" ]]

if TEST_TERRAFORM_MODE=missing-phase PATH="${temp_dir}/bin:${PATH}" "${script}" \
  "${root}/infra/terraform/budget-production" >"${temp_dir}/missing.out" 2>"${temp_dir}/missing.err"; then
  echo '상태: Fail - deployment_phase가 없는 기존 state를 registry로 오인했습니다.' >&2
  exit 1
fi
grep -Fq 'deployment_phase' "${temp_dir}/missing.err"
[[ ! -s "${temp_dir}/missing.out" ]]

echo '상태: Pass - budget-production phase 판독은 빈 state만 registry로 처리하고 나머지는 fail closed입니다.'

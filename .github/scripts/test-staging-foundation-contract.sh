#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/staging-foundation.yml"

[[ -f "${workflow}" ]] || {
  echo '상태: Fail - staging foundation workflow가 없습니다.' >&2
  exit 1
}

grep -Fq 'name: Staging foundation' "${workflow}"
grep -Fq 'AWS_STAGING_PLAN_ROLE_ARN' "${workflow}"
grep -Fq 'AWS_STAGING_APPLY_ROLE_ARN' "${workflow}"
grep -Fq 'reviewed_plan_run_id' "${workflow}"
grep -Fq 'REVIEWED_PLAN_RUN_ID' "${workflow}"
grep -Fq 'staging-foundation-plan-${{ inputs.release_tag }}' "${workflow}"
grep -Fq 'foundation_plan_sha256' "${workflow}"
grep -Fq 'actual_plan_sha256' "${workflow}"
grep -Fq 'enable_services:false' "${workflow}"
grep -Fq 'enable_backup_schedules:false' "${workflow}"
grep -Fq 'verify-staging-foundation-plan.sh' "${workflow}"
grep -Fq 'if: ${{ !inputs.apply }}' "${workflow}"
grep -Fq 'if: ${{ inputs.apply }}' "${workflow}"

if grep -Fq 'needs: plan' "${workflow}"; then
  echo '상태: Fail - apply job이 별도 reviewed plan run 대신 같은 run의 plan에 연결돼 있습니다.' >&2
  exit 1
fi

plan_line="$(grep -nF 'terraform -chdir=infra/terraform/staging plan' "${workflow}" | head -1 | cut -d: -f1)"
apply_line="$(grep -nF 'terraform -chdir=infra/terraform/staging apply' "${workflow}" | head -1 | cut -d: -f1)"
[[ -n "${plan_line}" && -n "${apply_line}" && "${plan_line}" -lt "${apply_line}" ]]

previous=0
for task in secret-bootstrap database-bootstrap property-flyway admin-migration user-flyway source-data-migration runtime-grants; do
  line="$(grep -nF "${task}" "${workflow}" | tail -1 | cut -d: -f1)"
  [[ -n "${line}" && "${line}" -gt "${previous}" ]] || {
    echo "상태: Fail - initial task 순서가 올바르지 않습니다: ${task}" >&2
    exit 1
  }
  previous="${line}"
done

if grep -Fq 'map-marker-projection' "${workflow}"; then
  echo '상태: Fail - data import 전 foundation workflow가 marker projection을 실행합니다.' >&2
  exit 1
fi

echo '상태: Pass - staging foundation plan/apply와 bootstrap task 순서를 확인했습니다.'

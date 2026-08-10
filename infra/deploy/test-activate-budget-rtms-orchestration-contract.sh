#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/activate-budget-rtms-orchestration.yml"

[[ -f "${workflow}" ]]

for expected in \
  'name: Activate budget production RTMS orchestration' \
  'environment: budget-production-plan' \
  'environment: budget-production' \
  "[[ '\${{ inputs.source_plan_run_id }}' == 31321021796 ]]" \
  'TF_VAR_rtms_refresh_schedule_enabled: "false"' \
  'TF_VAR_rtms_refresh_schedule_enabled: "true"' \
  'terraform -chdir=infra/terraform/budget-production plan -input=false -out=rtms-dark.tfplan' \
  'terraform -chdir=infra/terraform/budget-production apply -auto-approve rtms-dark.tfplan' \
  'terraform -chdir=infra/terraform/budget-production plan -input=false -out=rtms-enable.tfplan' \
  'terraform -chdir=infra/terraform/budget-production apply -auto-approve rtms-enable.tfplan' \
  'infra/deploy/verify-budget-production-rollout-plan.sh' \
  'cron(30 4 * * ? *)' \
  'MaximumRetryAttempts == 0' \
  ':stateMachine:home-search-budget-production-rtms-refresh$' \
  'home-search-budget-production-rtms-daily-refresh:23'; do
  grep -Fq -- "${expected}" "${workflow}"
done

for forbidden in \
  'terraform destroy' \
  'aws ecs update-service' \
  'aws ec2 ' \
  'aws route53 ' \
  'enable_rtms_refresh_schedule: false'; do
  if grep -Fq -- "${forbidden}" "${workflow}"; then
    echo "상태: Fail - RTMS-only workflow에 금지된 mutation이 있습니다: ${forbidden}" >&2
    exit 1
  fi
done

echo '상태: Pass - RTMS-only dark prep, exact enable saved plan과 금지 mutation 부재를 확인했습니다.'

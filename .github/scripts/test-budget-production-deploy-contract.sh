#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/deploy-budget-production.yml"
bootstrap_policy="${root}/infra/terraform/bootstrap/budget_production_oidc.tf"
budget_backend="${root}/infra/terraform/budget-production/backend.tf"
for required in \
  'name: Deploy budget production' \
  'environment: budget-production-plan' \
  'environment: budget-production' \
  'home-search/budget-production/terraform.tfstate' \
  'options: [deploy, registry]' \
  'release_sha:' \
  'migration_artifact_uri:' \
  'migration_manifest_sha256:' \
  'public_dns_enable_approved:' \
  'CpuCredits=unlimited' \
  'CpuCredits=standard' \
  'budget-production-credit-cleanup' \
  'timeout --signal=TERM --kill-after=5m 8h' \
  'CPUCreditBalance' \
  'run-recovery-rehearsal.sh' \
  'deployment-evidence/dns-plan.json' \
  'BUDGET_PRODUCTION_READY.json'; do
  grep -Fq -- "${required}" "${workflow}"
done
[[ "$(grep -Ec '^[[:space:]]+environment: budget-production-plan$' "${workflow}")" -eq 2 ]]
[[ "$(grep -Ec '^[[:space:]]+environment: budget-production$' "${workflow}")" -eq 5 ]]
! grep -Fq 'infra/terraform/production' "${workflow}"
! grep -Fq 'home-search/production/terraform.tfstate' "${workflow}"
grep -Fq 'backend "s3" {' "${budget_backend}"
[[ "$(grep -Fc 'infra/deploy/read-budget-production-phase.sh' "${workflow}")" -eq 2 ]]
! grep -Fq 'output -raw deployment_phase' "${workflow}"
grep -Fq 'role-to-assume: "${{ vars.AWS_BUDGET_PRODUCTION_DEPLOY_ROLE_ARN }}"' "${workflow}"
for scoped_sid in ManageBudgetBucketsOnly DenyCrossEnvironmentEc2Mutation DenyCrossEnvironmentEcsMutation DenyCrossEnvironmentEcrMutation DenyCrossEnvironmentControlPlaneMutation LaunchTaggedRecoveryInstance TerminateTaggedRecoveryInstance PassBudgetRuntimeRolesOnly RunBudgetOneShotTasks StopBudgetOneShotTasks SendCommandToTaggedRecoveryOnly ReadBudgetBackupEvidence; do
  grep -Fq "${scoped_sid}" "${bootstrap_policy}"
done
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 'ec2:TerminateInstances|ec2:RunInstances|iam:PassRole'
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 's3:GetObject|s3:PutObject'
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 'ecs:RunTask|ecs:StopTask|ecs:UpdateService|ssm:SendCommand'
"${root}/infra/deploy/test-read-budget-production-phase.sh"
echo '상태: Pass - budget workflow의 plan/apply/deploy role, phase, credit, restore, DNS readiness 순서를 확인했습니다.'

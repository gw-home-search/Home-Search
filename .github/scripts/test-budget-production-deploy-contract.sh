#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/deploy-budget-production.yml"
rollout_workflow="${root}/.github/workflows/rollout-budget-production.yml"
bootstrap_policy="${root}/infra/terraform/bootstrap/budget_production_oidc.tf"
budget_backend="${root}/infra/terraform/budget-production/backend.tf"
budget_outputs="${root}/infra/terraform/budget-production/outputs.tf"
pin_selector="${root}/infra/deploy/select-budget-production-foundation-pins.sh"
taint_recovery="${root}/infra/deploy/recover-budget-production-tainted-ssm.sh"
retained_ssm_import="${root}/infra/deploy/import-budget-production-retained-ssm.sh"
budget_notification_reconciler="${root}/infra/deploy/reconcile-budget-production-budget-notifications.sh"
for required in \
  'name: Deploy budget production' \
  'environment: budget-production-plan' \
  'environment: budget-production' \
  'home-search/budget-production/terraform.tfstate' \
  'options: [deploy, foundation, registry]' \
  'release_sha:' \
  'migration_artifact_uri:' \
  'migration_manifest_sha256:' \
  'public_dns_enable_approved:' \
  'recover_tainted_ssm_state:' \
  'budget-production-foundation-state-recovery' \
  'infra/deploy/recover-budget-production-tainted-ssm.sh' \
  'infra/deploy/import-budget-production-retained-ssm.sh' \
  'infra/deploy/reconcile-budget-production-budget-notifications.sh' \
  "needs: state_recovery" \
  "needs.state_recovery.result == 'success' || needs.state_recovery.result == 'skipped'" \
  'CpuCredits=unlimited' \
  'CpuCredits=standard' \
  'budget-production-credit-cleanup' \
  'timeout --signal=TERM --kill-after=5m 8h' \
  'CPUCreditBalance' \
  'run-recovery-rehearsal.sh' \
  'get-anomaly-monitors' \
  'cost_anomaly_monitor_arn' \
  'deployment-evidence/dns-plan.json' \
  'BUDGET_PRODUCTION_READY.json'; do
  grep -Fq -- "${required}" "${workflow}"
done
[[ "$(grep -Fc "if: inputs.operation == 'deploy' || inputs.operation == 'foundation'" "${workflow}")" -eq 1 ]]
grep -Fq "if: always() && needs.plan.result == 'success' && (inputs.operation == 'deploy' || inputs.operation == 'foundation')" "${workflow}"
grep -Fq "if: always() && needs.plan.result == 'success' && inputs.operation == 'registry'" "${workflow}"
grep -Fq "if: always() && needs.foundation_apply.result == 'success' && inputs.operation == 'deploy'" "${workflow}"
grep -Fq "if: always() && needs.rollout.result == 'success' && needs.credit_cleanup.result == 'success' && inputs.public_dns_enable_approved" "${workflow}"
grep -Fq "if: always() && needs.dns_plan.result == 'success'" "${workflow}"
[[ "$(grep -Fc 'terraform_wrapper: false' "${workflow}")" -eq 7 ]]
grep -Fq 'if [[ -f deployment-evidence/data-live-outputs.json ]]; then' "${workflow}"
cleanup_reauth_line="$(grep -nF 'name: Re-authenticate deploy role for unconditional credit cleanup' "${workflow}" | cut -d: -f1)"
[[ "${cleanup_reauth_line}" =~ ^[0-9]+$ ]]
sed -n "${cleanup_reauth_line},$((cleanup_reauth_line + 6))p" "${workflow}" | grep -Fq 'if: always()'
sed -n "${cleanup_reauth_line},$((cleanup_reauth_line + 6))p" "${workflow}" | grep -Fq 'role-to-assume: "${{ vars.AWS_BUDGET_PRODUCTION_DEPLOY_ROLE_ARN }}"'
[[ "$(grep -Fc 'Name=tag:Name,Values=home-search-budget-production-host' "${workflow}")" -eq 2 ]]
grep -Fq 'with: { fetch-depth: 0, ref: "${{ github.sha }}" }' "${workflow}"
grep -Fq 'git rev-parse "${RELEASE_SHA}^{commit}"' "${workflow}"
grep -Fq 'git rev-list -n 1 "${RELEASE_TAG}"' "${workflow}"
[[ "$(grep -Fc 'with: { ref: "${{ github.sha }}" }' "${workflow}")" -eq 4 ]]
! grep -Fq 'with: { ref: "${{ inputs.release_sha }}" }' "${workflow}"
! grep -Fq "inputs.operation == 'deploy' && inputs.release_sha || github.sha" "${workflow}"
! grep -Fq 'path: release-source' "${workflow}"
[[ "$(grep -Fc "if: inputs.operation == 'deploy'" "${workflow}")" -ge 2 ]]
grep -Fq 'infra/deploy/select-budget-production-foundation-pins.sh' "${workflow}"
grep -Fq 'Name=tag:Name,Values=${name}-data' "${pin_selector}"
grep -Fq 'Name=tag:Environment,Values=budget-production' "${pin_selector}"
grep -Fq 'partial-resources' "${pin_selector}"
[[ -x "${taint_recovery}" ]]
[[ -x "${retained_ssm_import}" ]]
[[ -x "${budget_notification_reconciler}" ]]
state_recovery_line="$(grep -nF 'name: Clear only verified partial-create tainted state' "${workflow}" | cut -d: -f1)"
[[ "${state_recovery_line}" =~ ^[0-9]+$ ]]
state_recovery_block="$(sed -n "${state_recovery_line},$((state_recovery_line + 40))p" "${workflow}")"
grep -Fq 'HOSTED_ZONE_ID: "${{ vars.BUDGET_PRODUCTION_HOSTED_ZONE_ID }}"' <<<"${state_recovery_block}"
grep -Fq 'ALARM_EMAIL: "${{ vars.BUDGET_PRODUCTION_ALARM_EMAIL }}"' <<<"${state_recovery_block}"
grep -Fq 'TF_VAR_ami_id="$(terraform -chdir=infra/terraform/budget-production output -raw ami_id)"' <<<"${state_recovery_block}"
grep -Fq 'TF_VAR_availability_zone="$(terraform -chdir=infra/terraform/budget-production output -raw availability_zone)"' <<<"${state_recovery_block}"
grep -Fq 'TF_VAR_hosted_zone_id="${HOSTED_ZONE_ID}"' <<<"${state_recovery_block}"
grep -Fq 'TF_VAR_alarm_email="${ALARM_EMAIL}"' <<<"${state_recovery_block}"
grep -Fq 'export TF_VAR_ami_id TF_VAR_availability_zone TF_VAR_hosted_zone_id TF_VAR_alarm_email' <<<"${state_recovery_block}"
grep -Fq 'output "ami_id" {' "${budget_outputs}"
grep -Fq 'output "availability_zone" {' "${budget_outputs}"
[[ "$(grep -Ec '^[[:space:]]+environment: budget-production-plan$' "${workflow}")" -eq 2 ]]
[[ "$(grep -Ec '^[[:space:]]+environment: budget-production$' "${workflow}")" -eq 6 ]]
! grep -Fq 'infra/terraform/production' "${workflow}"
! grep -Fq 'home-search/production/terraform.tfstate' "${workflow}"
grep -Fq 'backend "s3" {' "${budget_backend}"
[[ "$(grep -Fc 'infra/deploy/read-budget-production-phase.sh' "${workflow}")" -eq 3 ]]
! grep -Fq 'output -raw deployment_phase' "${workflow}"
foundation_plan_line="$(grep -nF 'name: Pin AMI and stable AZ, then create zero-destroy foundation plan' "${workflow}" | cut -d: -f1)"
[[ "${foundation_plan_line}" =~ ^[0-9]+$ ]]
foundation_plan_block="$(sed -n "${foundation_plan_line},$((foundation_plan_line + 90))p" "${workflow}")"
grep -Fq 'current_data_services_enabled="$(terraform -chdir=infra/terraform/budget-production output -raw data_services_enabled)"' <<<"${foundation_plan_block}"
grep -Fq '[[ "${current_data_services_enabled}" == true || "${current_data_services_enabled}" == false ]]' <<<"${foundation_plan_block}"
! grep -Fq '[[ "${current_data_services_enabled}" == false ]]' <<<"${foundation_plan_block}"
grep -Fq 'reviewed_phase=data' <<<"${foundation_plan_block}"
grep -Fq -- '-var="deployment_phase=${reviewed_phase}"' <<<"${foundation_plan_block}"
grep -Fq 'deployment-evidence/foundation-plan.json "${reviewed_phase}" "${current_phase}"' <<<"${foundation_plan_block}"
grep -Fq -- '--arg current_data_services_enabled "${current_data_services_enabled}"' <<<"${foundation_plan_block}"
grep -Fq 'current_data_services_enabled:($current_data_services_enabled == "true")' <<<"${foundation_plan_block}"
data_dark_line="$(grep -nF 'name: Register data tasks without starting PostgreSQL' "${workflow}" | cut -d: -f1)"
[[ "${data_dark_line}" =~ ^[0-9]+$ ]]
data_dark_block="$(sed -n "${data_dark_line},$((data_dark_line + 55))p" "${workflow}")"
grep -Fq 'current_phase="$(infra/deploy/read-budget-production-phase.sh infra/terraform/budget-production)"' <<<"${data_dark_block}"
grep -Fq '[[ "${code}" == 0 || "${code}" == 2 ]]' <<<"${data_dark_block}"
grep -Fq 'deployment-evidence/data-dark-plan.json data "${current_phase}"' <<<"${data_dark_block}"
grep -Fq 'if [[ "${code}" == 2 ]]; then' <<<"${data_dark_block}"
grep -Fq 'infra/deploy/wait-budget-platform-services-healthy.sh "${cluster}"' "${workflow}"
grep -Fq 'role-to-assume: "${{ vars.AWS_BUDGET_PRODUCTION_DEPLOY_ROLE_ARN }}"' "${workflow}"
for scoped_sid in ManageBudgetBucketsOnly DenyCrossEnvironmentEc2Mutation DenyCrossEnvironmentEcsMutation DenyCrossEnvironmentEcrMutation DenyCrossEnvironmentControlPlaneMutation LaunchTaggedRecoveryInstance TerminateTaggedRecoveryInstance PassBudgetRuntimeRolesOnly RunBudgetOneShotTasks StopBudgetOneShotTasks SendCommandToTaggedRecoveryOnly ReadBudgetBackupEvidence; do
  grep -Fq "${scoped_sid}" "${bootstrap_policy}"
done
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 'ec2:TerminateInstances|ec2:RunInstances|iam:PassRole'
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 's3:GetObject|s3:PutObject'
! sed -n '/budget_deploy_actions = \[/,/^  ]/p' "${bootstrap_policy}" | grep -Eq 'ecs:RunTask|ecs:StopTask|ecs:UpdateService|ssm:SendCommand'
[[ -f "${rollout_workflow}" ]]
for required in \
  'name: Rollout budget production' \
  'release_tag:' \
  'release_sha:' \
  'property_migration_target:' \
  'environment: budget-production-plan' \
  'environment: budget-production' \
  'Verify public phase and live platform health' \
  'Require V39 live history before V40' \
  'Require backup and disk headroom' \
  'Preserve live PostgreSQL and Valkey digests' \
  'Verify incremental Terraform allowlist' \
  'Capture rollback service state' \
  'Run exact property migration target and validate' \
  'Roll application services in dependency order' \
  'Smoke backend search before public gateway' \
  'Reconcile Terraform state and require zero drift' \
  'Smoke current homesearch.world DNS without mutation' \
  'Observe public search for 60 minutes' \
  'BUDGET_PRODUCTION_INCREMENTAL_READY.json'; do
  grep -Fq -- "${required}" "${rollout_workflow}"
done
for forbidden in \
  'data-import-reconcile' \
  'run-recovery-rehearsal.sh' \
  'modify-instance-credit-specification' \
  'CpuCredits=unlimited' \
  'aws_ecs_service.platform' \
  'aws_ecs_task_definition.platform' \
  'public_dns_enabled=true' \
  'aws_route53_record' \
  'terraform destroy'; do
  ! grep -Fq -- "${forbidden}" "${rollout_workflow}"
done
[[ "$(grep -Fc 'terraform_wrapper: false' "${rollout_workflow}")" -eq 2 ]]
grep -Fq 'with: { fetch-depth: 0, ref: "${{ github.sha }}" }' "${rollout_workflow}"
grep -Fq 'git rev-list -n 1 "${RELEASE_TAG}"' "${rollout_workflow}"
grep -Fq '(.images | length) == 17 and (.platform_images | length) == 2' "${rollout_workflow}"
grep -Fq 'infra/deploy/verify-budget-production-rollout-plan.sh' "${rollout_workflow}"
grep -Fq 'infra/deploy/build-budget-production-incremental-ready-evidence.sh' "${rollout_workflow}"
grep -Fq 'infra/deploy/rollback-services.sh deployment-evidence/pre-rollout-services.json' "${rollout_workflow}"
grep -Fq 'https://homesearch.world/api/v1/search/complexes' "${rollout_workflow}"
"${root}/infra/deploy/test-read-budget-production-phase.sh"
"${root}/infra/deploy/test-select-budget-production-foundation-pins.sh"
"${root}/infra/deploy/test-recover-budget-production-tainted-ssm.sh"
"${root}/infra/deploy/test-import-budget-production-retained-ssm.sh"
"${root}/infra/deploy/test-reconcile-budget-production-budget-notifications.sh"
"${root}/infra/bootstrap/test-normalize-budget-generated-values.sh"
"${root}/infra/deploy/test-run-budget-generated-value-normalization.sh"
"${root}/infra/deploy/test-wait-budget-platform-services-healthy.sh"
bash "${root}/infra/deploy/test-run-budget-ecs-task.sh"
bash "${root}/infra/deploy/test-verify-budget-production-rollout-plan.sh"
bash "${root}/infra/deploy/test-build-budget-production-incremental-ready-evidence.sh"
bash "${root}/infra/budget/test-run-property-search-audit.sh"
echo '상태: Pass - budget workflow의 plan/apply/deploy role, phase, credit, restore, DNS readiness 순서를 확인했습니다.'

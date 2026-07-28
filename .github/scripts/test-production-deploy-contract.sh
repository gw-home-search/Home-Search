#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/deploy-production.yml"
staging_workflow="${root}/.github/workflows/deploy-staging.yml"
rollback="${root}/.github/workflows/rollback-supervisor-graph.yml"
application_rollback="${root}/.github/workflows/rollback-production.yml"
grep -Fq 'environment: production' "${workflow}"
grep -Fq '(.images | length == 17)' "${workflow}"
grep -Fq '.vulnerability_policy_gate_passed' "${workflow}"
grep -Fq '[[ "${release_sha}" == "${GITHUB_SHA}" ]]' "${workflow}"
grep -Fq 'index("delete")' "${workflow}"
grep -Fq 'capture-service-state.sh' "${workflow}"
grep -Fq 'release-manifest-to-tfvars.sh' "${workflow}"
grep -Fq 'PRODUCTION_ADOT_COLLECTOR_IMAGE_URI' "${workflow}"
grep -Fq 'TF_VAR_admin_certificate_arn' "${workflow}"
grep -Fq 'TF_VAR_public_origin' "${workflow}"
grep -Fq 'TF_VAR_migration_artifact_bucket' "${workflow}"
grep -Fq 'TF_VAR_migration_artifact_prefix' "${workflow}"
grep -Fq 'TF_VAR_migration_artifact_kms_key_arn' "${workflow}"
grep -Fq 'TF_VAR_migration_manifest_sha256' "${workflow}"
grep -Fq 'release.auto.tfvars.json' "${workflow}"
grep -Fq 'for task in secret-bootstrap secret-readiness database-bootstrap property-flyway admin-migration user-flyway ai-migration data-import-reconcile runtime-grants map-marker-projection' "${workflow}"
grep -Fq 'output -json one_shot_task_definition_arns' "${workflow}"
grep -Fq 'for phase in consumers private all' "${workflow}"
grep -Fq 'verify-terraform-plan.sh "${plan_json}" activation' "${workflow}"
grep -Fq 'verify-service-activation.sh "${cluster}" "${phase}"' "${workflow}"
grep -Fq 'deployment-evidence/dark-smoke.json' "${workflow}"
[[ "$(grep -Fc 'environment: production' "${workflow}")" -ge 2 ]]
if grep -F 'for task in ' "${workflow}" | grep -Fq 'source-data-migration'; then
  echo '상태: Fail - production 자동 배포가 deferred coordinate source migration을 실행합니다.' >&2
  exit 1
fi
if grep -F 'for task in ' "${staging_workflow}" | grep -Fq 'source-data-migration'; then
  echo '상태: Fail - staging 자동 배포가 deferred coordinate source migration을 실행합니다.' >&2
  exit 1
fi
grep -Fq 'HOME_AI_SUPERVISOR_GRAPH_MODE",value:"off"' "${rollback}"
grep -Fq 'production-foundation-evidence-${RELEASE_TAG}' "${application_rollback}"
grep -Fq 'rollback-services.sh rollback-evidence/pre-deploy-services.json' "${application_rollback}"
grep -Fq 'target_seconds:1800' "${application_rollback}"
if grep -Eq 'down -v|volume (rm|prune)|flyway.*clean|terraform destroy|delete-db|delete-table|delete-topic' "${workflow}" "${rollback}" "${application_rollback}"; then
  echo '상태: Fail - production workflow에 destructive rollback이 포함됐습니다.' >&2
  exit 1
fi
echo '상태: Pass - immutable manifest, approval, 0 destroy, migration ordering, Graph-only rollback 계약을 확인했습니다.'

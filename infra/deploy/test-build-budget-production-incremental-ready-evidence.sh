#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/build-budget-production-incremental-ready-evidence.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
evidence="${tmp_dir}/evidence"
mkdir "${evidence}"

tag=v1.0.24
sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
common="$(jq -cn --arg tag "${tag}" --arg sha "${sha}" '{status:"pass",release_tag:$tag,commit_sha:$sha,created_at:"2026-08-02T00:00:00Z",checks:{},redactions_applied:true}')"
application_names=(admin-api admin-gateway admin-migration admin-ops ai backup chat-bff ml ops-bootstrap property-api property-batch property-flyway public-gateway source-data-migration user-api user-flyway user-insight-worker)
images='{}'; index=0
for name in "${application_names[@]}"; do
  index=$((index + 1)); digest="sha256:$(printf '%064d' "${index}")"
  uri="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/${name}@${digest}"
  images="$(jq --arg name "${name}" --arg digest "${digest}" --arg uri "${uri}" '. + {($name):{digest:$digest,uri:$uri}}' <<<"${images}")"
done
platform='{"budget-postgres":{"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","uri":"123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"budget-valkey":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","uri":"123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-valkey@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}'
jq -n --arg tag "${tag}" --arg sha "${sha}" --argjson images "${images}" --argjson platform "${platform}" '{format_version:2,tag:$tag,commit_sha:$sha,images:$images,platform_images:$platform,build_flags:{market_news_enabled:true},sbom_set_sha256:("b"*64),vulnerability_set_sha256:("c"*64),vulnerability_critical_gate_passed:true,vulnerability_policy_gate_passed:true}' >"${evidence}/release-manifest.json"

for artifact in source-baseline live-audit diagnosis first-red terraform-bootstrap-plan terraform-prep-plan; do
  printf '%s\n' "${common}" >"${evidence}/${artifact}.json"
done
jq --argjson base "${common}" '$base + {current_phase:"public",dns_unchanged:true,platform_healthy:true,application_services_stable:true,backup_age_seconds:3600,data_free_bytes:22000000000,root_free_bytes:9000000000,platform_digest_preserved:true,release_exact_match:true,secrets_ready:true,model_checksums_match:true}' <<<null >"${evidence}/rollout-preflight.json"

apps='{}'
for name in admin-api admin-gateway ai chat-bff ml property-api public-gateway user-api; do
  apps="$(jq --arg name "${name}" '. + {($name):{task_definition:("arn:aws:ecs:ap-northeast-2:123456789012:task-definition/"+$name+":1"),desired_count:1,running_count:1,pending_count:0,deployment_state:"COMPLETED"}}' <<<"${apps}")"
done
jq -n --argjson services "${apps}" '{format_version:1,cluster_exists:true,services:$services}' >"${evidence}/pre-rollout-services.json"
printf '%s\n' '{"format_version":1,"cluster_exists":true,"services":{"budget-postgres":{},"budget-valkey":{}}}' >"${evidence}/pre-rollout-platform.json"

data='{"complex":{"rows":1,"identity_checksum":"a"},"complex_name_alias":{"rows":1,"identity_checksum":"b"},"parcel":{"rows":1,"identity_checksum":"c"},"trade":{"rows":1,"identity_checksum":"d"}}'
jq -n --argjson data "${data}" '{status:"pass",phase:"before",previous_version:39,target_version:40,failed:0,missing:0,out_of_order:0,data:$data}' >"${evidence}/migration-before.json"
jq -n --argjson data "${data}" '{status:"pass",phase:"after",previous_version:39,target_version:40,failed:0,missing:0,out_of_order:0,data:$data}' >"${evidence}/migration-after.json"

jq --argjson base "${common}" '$base | .checks={allowlist_exact:true,checksums_match:true,immutable_upload:true}' <<<null >"${evidence}/model-artifact.json"
jq --argjson base "${common}" '$base | .checks={atomic_install:true,uid_10001_readable:true,extra_files:0,symlinks:0}' <<<null >"${evidence}/model-install.json"
jq --argjson base "${common}" '$base | .checks={ecs_healthy:true,health_http_status:200,prediction_finite_positive:true,model_version_matches:true}' <<<null >"${evidence}/ml-smoke.json"
jq --argjson base "${common}" '$base | .checks={task_exit_code:0,all_steps_completed:true,raw_first:true,duplicate_normalized_trades:0,nation_snapshot_fresh:true,seoul_snapshot_fresh:true}' <<<null >"${evidence}/rtms-catchup.json"
jq --argjson base "${common}" '$base | .checks={provider_failures:0,scope_snapshot_count:18,nation_non_empty:true,seoul_non_empty:true,raw_first:true,duplicate_articles:0,quality_policy:"NEWS_V5"}' <<<null >"${evidence}/news-bootstrap.json"
jq --argjson base "${common}" '$base | .checks={providers:["google","kakao","naver"],redirects_passed:true,invalid_callbacks_controlled:true,full_logins_passed:true,logout_passed:true,cookie_policy_preserved:true}' <<<null >"${evidence}/oauth-smoke.json"
jq --argjson base "${common}" '$base | .checks={running:true,healthy:true,cleaned_up:true,failure_evidence_capable:true}' <<<null >"${evidence}/ai-canary.json"
jq --argjson base "${common}" '$base + {order:["ml","property-api","user-api","ai","chat-bff","admin-api","admin-gateway","public-gateway"],services:[{stable:true}]}' <<<null >"${evidence}/service-rollout.json"
jq --argjson base "${common}" '$base | .checks={news_passed:true,insight_passed:true,prediction_ready:true,oauth_three_providers_passed:true,search_exact_passed:true,search_prefix_passed:true,concurrent_requests:20,concurrent_5xx:0,api_contract_compatible:true,platform_unchanged:true}' <<<null >"${evidence}/feature-smoke.json"
printf '%s\n' '{"resource_changes":[]}' >"${evidence}/final-zero-drift-plan.json"
jq --argjson base "${common}" '$base + {duration_minutes:15,statement_timeouts:0} | .checks={hard_gate_minutes:15,http_failures:0,public_5xx:0,task_crashes:0,readiness_failures:0,secret_exposure_findings:0,platform_changes:0}' <<<null >"${evidence}/observation.json"

SECURITY_AUDIT_RESULT=none bash "${script}" "${evidence}" "${evidence}/BUDGET_PRODUCTION_INCREMENTAL_READY.json" >/dev/null
jq -e '.status == "BUDGET_PRODUCTION_INCREMENTAL_READY" and .property_migration_target == 40 and .contract_impact == "compatible"
  and .security_audit == "security-audit: 지적사항 = none" and (.artifacts | length) == 23' "${evidence}/BUDGET_PRODUCTION_INCREMENTAL_READY.json" >/dev/null

jq '.previous_version = 40' "${evidence}/migration-before.json" >"${evidence}/migration-before.next" && mv "${evidence}/migration-before.next" "${evidence}/migration-before.json"
jq '.previous_version = 40' "${evidence}/migration-after.json" >"${evidence}/migration-after.next" && mv "${evidence}/migration-after.next" "${evidence}/migration-after.json"
SECURITY_AUDIT_RESULT=none bash "${script}" "${evidence}" "${tmp_dir}/already-v40.json" >/dev/null

jq '.checks.public_5xx = 1' "${evidence}/observation.json" >"${evidence}/observation.next" && mv "${evidence}/observation.next" "${evidence}/observation.json"
if SECURITY_AUDIT_RESULT=none bash "${script}" "${evidence}" "${tmp_dir}/unsafe.json" >/dev/null 2>&1; then
  echo '상태: Fail - 5xx observation evidence를 허용했습니다.' >&2
  exit 1
fi
echo '상태: Pass - 증분 readiness evidence의 기능·migration·rollout·15분 hard gate를 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/build-budget-production-incremental-ready-evidence.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
evidence="${tmp_dir}/evidence"
mkdir "${evidence}"

application_names=(admin-api admin-gateway admin-migration admin-ops ai backup chat-bff ml ops-bootstrap property-api property-batch property-flyway public-gateway source-data-migration user-api user-flyway user-insight-worker)
images='{}'; index=0
for name in "${application_names[@]}"; do
  index=$((index + 1)); digest="sha256:$(printf '%064d' "${index}")"
  uri="123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/${name}@${digest}"
  images="$(jq --arg name "${name}" --arg digest "${digest}" --arg uri "${uri}" '. + {($name):{digest:$digest,uri:$uri}}' <<<"${images}")"
done
platform='{"budget-postgres":{"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","uri":"123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"budget-valkey":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","uri":"123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-valkey@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}'
jq -n --argjson images "${images}" --argjson platform "${platform}" '{format_version:2,tag:"v1.0.11",commit_sha:("a"*40),images:$images,platform_images:$platform,build_flags:{market_news_enabled:true},sbom_set_sha256:("b"*64),vulnerability_set_sha256:("c"*64),vulnerability_critical_gate_passed:true,vulnerability_policy_gate_passed:true}' >"${evidence}/release-manifest.json"
printf '%s\n' '{"status":"BUDGET_PRODUCTION_READY","artifacts":{"security.json":"abc"}}' >"${evidence}/baseline-ready.json"
printf '%s\n' '{"resource_changes":[]}' >"${evidence}/rollout-plan.json"
printf '%s\n' '{"status":"pass","current_phase":"public","dns_unchanged":true,"platform_healthy":true,"backup_age_seconds":3600,"data_free_bytes":22000000000,"root_free_bytes":9000000000,"platform_digest_preserved":true}' >"${evidence}/rollout-preflight.json"
printf '%s\n' '{"format_version":1,"cluster_exists":true,"services":{"property-api":{}}}' >"${evidence}/pre-rollout-services.json"
printf '%s\n' '{"format_version":1,"cluster_exists":true,"services":{"budget-postgres":{},"budget-valkey":{}}}' >"${evidence}/pre-rollout-platform.json"
for artifact in source-baseline live-audit diagnosis first-red terraform-bootstrap-plan terraform-prep-plan model-artifact model-install ml-smoke rtms-catchup news-bootstrap oauth-smoke ai-canary feature-smoke; do
  printf '%s\n' '{"status":"pass","release_tag":"v1.0.11","commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","created_at":"2026-08-02T00:00:00Z","checks":{},"redactions_applied":true}' >"${evidence}/${artifact}.json"
done
data='{"complex":{"rows":1,"identity_checksum":"a"},"complex_name_alias":{"rows":1,"identity_checksum":"b"},"parcel":{"rows":1,"identity_checksum":"c"},"trade":{"rows":1,"identity_checksum":"d"}}'
jq -n --argjson data "${data}" '{status:"pass",phase:"before",previous_version:39,target_version:40,failed:0,missing:0,out_of_order:0,data:$data}' >"${evidence}/migration-before.json"
jq -n --argjson data "${data}" '{status:"pass",phase:"after",previous_version:39,target_version:40,failed:0,missing:0,out_of_order:0,data:$data}' >"${evidence}/migration-after.json"
printf '%s\n' '{"status":"pass","order":["property-api","user-api","ai","chat-bff","public-gateway"],"services":[{"stable":true}]}' >"${evidence}/service-rollout.json"
printf '%s\n' '{"status":"pass","http_status":200,"response_is_array":true,"concurrent_requests":20,"concurrent_5xx":0,"post_concurrency_search_passed":true}' >"${evidence}/backend-search-smoke.json"
printf '%s\n' '{"status":"pass","http_status":200,"response_is_array":true}' >"${evidence}/public-search-smoke.json"
printf '%s\n' '{"resource_changes":[]}' >"${evidence}/final-zero-drift-plan.json"
printf '%s\n' '{"status":"pass","duration_minutes":60,"samples":60,"search_5xx":0,"exact_p95_seconds":0.4,"prefix_p95_seconds":0.8,"cpu_max_percent":70,"memory_max_percent":80}' >"${evidence}/observation.json"

bash "${script}" "${evidence}" "${evidence}/BUDGET_PRODUCTION_INCREMENTAL_READY.json" >/dev/null
jq -e '.status == "BUDGET_PRODUCTION_INCREMENTAL_READY" and .property_migration_target == 40 and .contract_impact == "compatible"' "${evidence}/BUDGET_PRODUCTION_INCREMENTAL_READY.json" >/dev/null
jq '.previous_version = 40' "${evidence}/migration-before.json" >"${evidence}/migration-before.next" && mv "${evidence}/migration-before.next" "${evidence}/migration-before.json"
jq '.previous_version = 40' "${evidence}/migration-after.json" >"${evidence}/migration-after.next" && mv "${evidence}/migration-after.next" "${evidence}/migration-after.json"
bash "${script}" "${evidence}" "${tmp_dir}/already-v40.json" >/dev/null
jq '.search_5xx = 1' "${evidence}/observation.json" >"${evidence}/observation.next" && mv "${evidence}/observation.next" "${evidence}/observation.json"
if bash "${script}" "${evidence}" "${tmp_dir}/unsafe.json" >/dev/null 2>&1; then
  echo '상태: Fail - 5xx observation evidence를 허용했습니다.' >&2
  exit 1
fi
echo '상태: Pass - 증분 readiness evidence의 baseline·migration·rollout·관찰 gate를 확인했습니다.'

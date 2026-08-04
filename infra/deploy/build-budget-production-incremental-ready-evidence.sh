#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

evidence_dir="${1:?evidence directory is required}"
output="${2:?incremental readiness output is required}"
security_audit_result="${SECURITY_AUDIT_RESULT:?SECURITY_AUDIT_RESULT=none|listed is required}"
[[ "${security_audit_result}" == none || "${security_audit_result}" == listed ]]
[[ -d "${evidence_dir}" && ! -L "${evidence_dir}" ]]
[[ ! -e "${output}" && ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]]

artifacts=(
  source-baseline.json live-audit.json diagnosis.json first-red.json release-manifest.json
  rollout-preflight.json terraform-bootstrap-plan.json terraform-prep-plan.json
  pre-rollout-services.json pre-rollout-platform.json migration-before.json migration-after.json
  model-artifact.json model-install.json ml-smoke.json rtms-catchup.json news-bootstrap.json
  oauth-smoke.json ai-canary.json service-rollout.json feature-smoke.json
  final-zero-drift-plan.json observation.json
)
for artifact in "${artifacts[@]}"; do
  path="${evidence_dir}/${artifact}"
  [[ -f "${path}" && ! -L "${path}" ]] || {
    echo "상태: Fail - 필수 증분 rollout evidence가 없습니다: ${artifact}" >&2
    exit 1
  }
  jq -e . "${path}" >/dev/null
  if grep -Eqi 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|(access|refresh)[_-]?token[" ]*[:=][" ]*[A-Za-z0-9._-]{16,}' "${path}"; then
    echo "상태: Fail - 증분 evidence에 secret 형식 값이 포함됐습니다: ${artifact}" >&2
    exit 1
  fi
done

release="${evidence_dir}/release-manifest.json"
tag="$(jq -er '.tag' "${release}")"
sha="$(jq -er '.commit_sha | select(test("^[0-9a-f]{40}$"))' "${release}")"
jq -e '
  ["admin-api","admin-gateway","admin-migration","admin-ops","ai","backup","chat-bff","ml","ops-bootstrap","property-api","property-batch","property-flyway","public-gateway","source-data-migration","user-api","user-flyway","user-insight-worker"] as $applications
  | ["budget-postgres","budget-valkey"] as $platform
  | .format_version == 2 and (.images | length) == 17 and (.platform_images | length) == 2
  and ((.images | keys | sort) == $applications) and ((.platform_images | keys | sort) == $platform)
  and all((.images + .platform_images) | to_entries[];
    (.value.digest | test("^sha256:[0-9a-f]{64}$"))
    and (.value.uri | test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$"))
    and .value.uri == ((.value.uri | split("/")[0]) + "/home-search/" + .key + "@" + .value.digest))
  and (.sbom_set_sha256 | test("^[0-9a-f]{64}$"))
  and (.vulnerability_set_sha256 | test("^[0-9a-f]{64}$"))
  and .vulnerability_critical_gate_passed == true and .vulnerability_policy_gate_passed == true
  and .build_flags.market_news_enabled == true
' "${release}" >/dev/null

common_artifacts=(source-baseline live-audit diagnosis first-red rollout-preflight terraform-bootstrap-plan terraform-prep-plan model-artifact model-install ml-smoke rtms-catchup news-bootstrap oauth-smoke ai-canary service-rollout feature-smoke observation)
for artifact in "${common_artifacts[@]}"; do
  jq -e --arg tag "${tag}" --arg sha "${sha}" '
    .status == "pass"
    and .release_tag == $tag and .commit_sha == $sha
    and (.created_at | type == "string" and length > 0)
    and (.checks | type == "object") and .redactions_applied == true
  ' "${evidence_dir}/${artifact}.json" >/dev/null
done

jq -e '.status == "pass" and .current_phase == "public" and .dns_unchanged == true
  and .platform_healthy == true and .application_services_stable == true
  and .backup_age_seconds <= 93600
  and .data_free_bytes >= 21474836480 and .root_free_bytes >= 8589934592
  and .platform_digest_preserved == true and .release_exact_match == true
  and .secrets_ready == true and .model_checksums_match == true' "${evidence_dir}/rollout-preflight.json" >/dev/null

application_names='["admin-api","admin-gateway","ai","chat-bff","ml","property-api","public-gateway","user-api"]'
platform_names='["budget-postgres","budget-valkey"]'
jq -e --argjson expected "${application_names}" '
  .format_version == 1 and .cluster_exists == true
  and (.services | keys | sort) == ($expected | sort)
  and all(.services[]; (.task_definition | type == "string") and (.desired_count | type == "number")
    and (.running_count | type == "number") and (.pending_count | type == "number")
    and (.deployment_state | type == "string"))
' "${evidence_dir}/pre-rollout-services.json" >/dev/null
jq -e --argjson expected "${platform_names}" '
  .format_version == 1 and .cluster_exists == true and (.services | keys | sort) == ($expected | sort)
' "${evidence_dir}/pre-rollout-platform.json" >/dev/null

for phase in before after; do
  jq -e --arg phase "${phase}" '.status == "pass" and .phase == $phase
    and (.previous_version == 39 or .previous_version == 40) and .target_version == 40
    and .failed == 0 and .missing == 0 and .out_of_order == 0
    and (.data | keys | sort) == ["complex","complex_name_alias","parcel","trade"]' "${evidence_dir}/migration-${phase}.json" >/dev/null
done
jq -e --slurp '.[0].previous_version == .[1].previous_version and .[0].data == .[1].data' \
  "${evidence_dir}/migration-before.json" "${evidence_dir}/migration-after.json" >/dev/null

jq -e '.status == "pass" and .checks.allowlist_exact == true and .checks.checksums_match == true
  and .checks.immutable_upload == true' "${evidence_dir}/model-artifact.json" >/dev/null
jq -e '.status == "pass" and .checks.atomic_install == true and .checks.uid_10001_readable == true
  and .checks.extra_files == 0 and .checks.symlinks == 0' "${evidence_dir}/model-install.json" >/dev/null
jq -e '.status == "pass" and .checks.ecs_healthy == true and .checks.health_http_status == 200
  and .checks.prediction_finite_positive == true and .checks.model_version_matches == true' "${evidence_dir}/ml-smoke.json" >/dev/null
jq -e '.status == "pass" and .checks.task_exit_code == 0 and .checks.all_steps_completed == true
  and .checks.raw_first == true and .checks.duplicate_normalized_trades == 0
  and .checks.nation_snapshot_fresh == true and .checks.seoul_snapshot_fresh == true' "${evidence_dir}/rtms-catchup.json" >/dev/null
jq -e 'if .status == "skipped" then (.skipped_reason | type == "string" and length > 0)
  else .status == "pass" and .checks.provider_failures == 0 and .checks.scope_snapshot_count == 18
  and .checks.nation_non_empty == true and .checks.seoul_non_empty == true
  and .checks.raw_first == true and .checks.duplicate_articles == 0 and .checks.quality_policy == "NEWS_V5" end' "${evidence_dir}/news-bootstrap.json" >/dev/null
jq -e 'if .status == "skipped" then (.skipped_reason | type == "string" and length > 0)
  else .status == "pass" and (.checks.providers | sort) == ["google","kakao","naver"]
  and .checks.redirects_passed == true and .checks.invalid_callbacks_controlled == true
  and .checks.full_logins_passed == true and .checks.logout_passed == true and .checks.cookie_policy_preserved == true end' "${evidence_dir}/oauth-smoke.json" >/dev/null
jq -e '.status == "pass" and .checks.running == true and .checks.healthy == true
  and .checks.cleaned_up == true and .checks.failure_evidence_capable == true' "${evidence_dir}/ai-canary.json" >/dev/null
jq -e '.status == "pass"
  and .order == ["ml","property-api","user-api","ai","chat-bff","admin-api","admin-gateway","public-gateway"]
  and all(.services[]; .stable == true)' "${evidence_dir}/service-rollout.json" >/dev/null
jq -e '.status == "pass" and .checks.news_passed == true and .checks.insight_passed == true
  and .checks.prediction_ready == true and .checks.oauth_three_providers_passed == true
  and .checks.search_exact_passed == true and .checks.search_prefix_passed == true
  and .checks.concurrent_requests == 20 and .checks.concurrent_5xx == 0
  and .checks.api_contract_compatible == true and .checks.platform_unchanged == true' "${evidence_dir}/feature-smoke.json" >/dev/null
jq -e '[.resource_changes[]? | select(.change.actions != ["no-op"] and .change.actions != ["read"])] | length == 0' \
  "${evidence_dir}/final-zero-drift-plan.json" >/dev/null
jq -e '.status == "pass" and .duration_minutes >= 15 and .checks.hard_gate_minutes >= 15
  and .checks.http_failures == 0 and .checks.public_5xx == 0 and .checks.task_crashes == 0 and .checks.readiness_failures == 0
  and .checks.secret_exposure_findings == 0 and .checks.platform_changes == 0
  and .statement_timeouts == 0' "${evidence_dir}/observation.json" >/dev/null

hash_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
hashes='{}'
for artifact in "${artifacts[@]}"; do
  hashes="$(jq --arg name "${artifact}" --arg hash "$(hash_file "${evidence_dir}/${artifact}")" '. + {($name):$hash}' <<<"${hashes}")"
done
temporary="$(mktemp "$(dirname "${output}")/.budget-incremental-ready.XXXXXX")"
trap 'unlink "${temporary}" 2>/dev/null || true' EXIT
jq -n --arg tag "${tag}" --arg sha "${sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg security_audit_result "${security_audit_result}" --argjson artifacts "${hashes}" '
  {status:"BUDGET_PRODUCTION_INCREMENTAL_READY",release_tag:$tag,commit_sha:$sha,created_at:$created_at,
   property_migration_target:40,artifacts:$artifacts,redactions_applied:true,
   contract_impact:"compatible",
   security_impact:"보안 영향: 검색 단계별 query와 budget-production 증분 rollout에 뉴스·RTMS scheduler·F37 model runtime·Google/Kakao/Naver OAuth를 추가한다. 공개 API shape·기존 trade 식별자·platform DB volume은 변경하지 않으며 RTMS는 raw-first/dedupe 방식으로 신규 행만 갱신한다.",
   security_audit:("security-audit: 지적사항 = " + $security_audit_result)}
' >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - BUDGET_PRODUCTION_INCREMENTAL_READY evidence bundle을 생성했습니다.'

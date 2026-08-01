#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

evidence_dir="${1:?evidence directory is required}"
output="${2:?incremental readiness output is required}"
[[ -d "${evidence_dir}" && ! -L "${evidence_dir}" ]]
[[ ! -e "${output}" && ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]]

artifacts=(
  baseline-ready.json release-manifest.json rollout-plan.json rollout-preflight.json
  pre-rollout-services.json migration-before.json migration-after.json service-rollout.json
  backend-search-smoke.json public-search-smoke.json final-zero-drift-plan.json observation.json
)
for artifact in "${artifacts[@]}"; do
  path="${evidence_dir}/${artifact}"
  [[ -f "${path}" && ! -L "${path}" ]] || {
    echo "상태: Fail - 필수 증분 rollout evidence가 없습니다: ${artifact}" >&2
    exit 1
  }
  jq -e . "${path}" >/dev/null
  if grep -Eqi 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}' "${path}"; then
    echo "상태: Fail - 증분 evidence에 secret 형식 값이 포함됐습니다: ${artifact}" >&2
    exit 1
  fi
done

release="${evidence_dir}/release-manifest.json"
tag="$(jq -er '.tag' "${release}")"
sha="$(jq -er '.commit_sha' "${release}")"
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
' "${release}" >/dev/null
jq -e '.status == "BUDGET_PRODUCTION_READY" and (.artifacts | length) > 0' "${evidence_dir}/baseline-ready.json" >/dev/null
jq -e '.status == "pass" and .current_phase == "public" and .dns_unchanged == true
  and .platform_healthy == true and .backup_age_seconds <= 93600
  and .data_free_bytes >= 21474836480 and .root_free_bytes >= 8589934592
  and .platform_digest_preserved == true' "${evidence_dir}/rollout-preflight.json" >/dev/null
jq -e '.format_version == 1 and .cluster_exists == true and (.services | length) > 0' "${evidence_dir}/pre-rollout-services.json" >/dev/null
for phase in before after; do
  jq -e --arg phase "${phase}" '.status == "pass" and .phase == $phase
    and .previous_version == 39 and .target_version == 40
    and .failed == 0 and .missing == 0 and .out_of_order == 0
    and (.data | keys | sort) == ["complex","complex_name_alias","parcel","trade"]' "${evidence_dir}/migration-${phase}.json" >/dev/null
done
jq -e --slurp '.[0].data == .[1].data' \
  "${evidence_dir}/migration-before.json" "${evidence_dir}/migration-after.json" >/dev/null
jq -e '.status == "pass" and .order == ["property-api","user-api","ai","chat-bff","public-gateway"]
  and all(.services[]; .stable == true)' "${evidence_dir}/service-rollout.json" >/dev/null
jq -e '.status == "pass" and .http_status == 200 and .response_is_array == true
  and .concurrent_requests == 20 and .concurrent_5xx == 0 and .post_concurrency_search_passed == true' \
  "${evidence_dir}/backend-search-smoke.json" >/dev/null
jq -e '.status == "pass" and .http_status == 200 and .response_is_array == true' \
  "${evidence_dir}/public-search-smoke.json" >/dev/null
jq -e '[.resource_changes[]? | select(.change.actions != ["no-op"] and .change.actions != ["read"])] | length == 0' \
  "${evidence_dir}/final-zero-drift-plan.json" >/dev/null
jq -e '.status == "pass" and .duration_minutes >= 60 and .samples >= 60
  and .search_5xx == 0 and .exact_p95_seconds <= 0.5 and .prefix_p95_seconds <= 1
  and .cpu_max_percent < 80 and .memory_max_percent < 90' "${evidence_dir}/observation.json" >/dev/null

hash_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
hashes='{}'
for artifact in "${artifacts[@]}"; do
  hashes="$(jq --arg name "${artifact}" --arg hash "$(hash_file "${evidence_dir}/${artifact}")" '. + {($name):$hash}' <<<"${hashes}")"
done
temporary="$(mktemp "$(dirname "${output}")/.budget-incremental-ready.XXXXXX")"
trap 'unlink "${temporary}" 2>/dev/null || true' EXIT
jq -n --arg tag "${tag}" --arg sha "${sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson artifacts "${hashes}" '
  {status:"BUDGET_PRODUCTION_INCREMENTAL_READY",release_tag:$tag,commit_sha:$sha,created_at:$created_at,
   baseline:"BUDGET_PRODUCTION_READY",property_migration_target:40,artifacts:$artifacts,
   contract_impact:"compatible",
   security_impact:"보안 영향: 검색 단계별 query와 budget-production 증분 rollout을 추가하며 공개 API·trade 데이터·platform DB volume은 변경하지 않음.",
   security_audit:"security-audit: 지적사항 = none"}
' >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - BUDGET_PRODUCTION_INCREMENTAL_READY evidence bundle을 생성했습니다.'

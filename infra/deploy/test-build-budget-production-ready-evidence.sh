#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/build-budget-production-ready-evidence.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
evidence="${tmp_dir}/evidence"
mkdir "${evidence}"

applications=(admin-api admin-gateway admin-migration admin-ops ai backup chat-bff ml ops-bootstrap property-api property-batch property-flyway public-gateway seo-renderer source-data-migration user-api user-flyway user-insight-worker)
platform=(budget-postgres budget-valkey)
jq -n --argjson apps "$(printf '%s\n' "${applications[@]}" | jq -Rsc 'split("\n")[:-1]')" --argjson platform "$(printf '%s\n' "${platform[@]}" | jq -Rsc 'split("\n")[:-1]')" '
  def image($name): {digest:("sha256:"+("a"*64))};
  {format_version:2,tag:"v2.0.0",commit_sha:("b"*40),build_architecture:"linux/amd64",vulnerability_critical_gate_passed:true,vulnerability_policy_gate_passed:true,
   images:(reduce $apps[] as $name ({}; .[$name]=image($name))),platform_images:(reduce $platform[] as $name ({}; .[$name]=image($name)))}
' >"${evidence}/release-manifest.json"
printf '%s\n' '{"resource_changes":[]}' >"${evidence}/foundation-plan.json"
printf '%s\n' '{"status":"pass","incremental_monthly_usd":"94.96","account_total_usd":"98.46"}' >"${evidence}/cost-estimate.json"
printf '%s\n' '{"status":"pass","phases":["registry","foundation","data","private","public","dns"],"cpu_credit_mode":"standard","cpu_credit_balance":216,"root_free_bytes":9000000000,"data_free_bytes":22000000000,"release_digest_match":true}' >"${evidence}/phase-evidence.json"
printf '%s\n' '{"status":"pass","findings":[],"invariants":{"normalizedDuplicateCount":0,"rawFirstViolationCount":0,"unqueryableFailedMatchCount":0}}' >"${evidence}/data-migration-reconciliation.json"
printf '%s\n' '{"release_tag":"v2.0.0","commit_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"pass","public_inbound_tcp":[80,443],"non_public_ports_blocked":true,"internal_routes_blocked":true,"container_imds_blocked":true,"cross_database_access_blocked":true,"valkey_acl_isolated":true,"marker_parity":true,"map_p95_seconds":1.2,"public_error_rate_percent":0.1,"memory_p95_percent":70}' >"${evidence}/acceptance.json"
printf '%s\n' '{"status":"pass","mode":"logical","duration_seconds":100,"checksum_match":true,"marker_parity":true}' >"${evidence}/logical-restore.json"
printf '%s\n' '{"status":"pass","mode":"ebs","duration_seconds":200,"checksum_match":true,"marker_parity":true}' >"${evidence}/ebs-restore.json"
printf '%s\n' '{"release_tag":"v2.0.0","commit_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"pass","findings":[],"checkpoint":"security-audit: 지적사항 = none","secret_exposure":false}' >"${evidence}/security.json"
printf '%s\n' '{"release_tag":"v2.0.0","commit_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"pass","sns_email_confirmed":true,"test_alarm_received":true}' >"${evidence}/observability.json"
printf '%s\n' '{"status":"pass","hostname":"homesearch.world","curl_resolve_passed":true,"public_dns_passed":true}' >"${evidence}/dns.json"
printf '%s\n' '{"release_tag":"v2.0.0","commit_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"pass","release_contains_staging_origin":false,"kakao_console_evidence":true,"oauth_contract_providers":["google","kakao","naver"]}' >"${evidence}/release-exceptions.json"

"${script}" "${evidence}" "${tmp_dir}/BUDGET_PRODUCTION_READY.json"
jq -e '.status == "BUDGET_PRODUCTION_READY" and (.artifacts | length) == 12 and .security_audit == "security-audit: 지적사항 = none"' \
  "${tmp_dir}/BUDGET_PRODUCTION_READY.json" >/dev/null
jq '.cpu_credit_mode="unlimited"' "${evidence}/phase-evidence.json" >"${tmp_dir}/bad-phase.json"
mv "${tmp_dir}/bad-phase.json" "${evidence}/phase-evidence.json"
if "${script}" "${evidence}" "${tmp_dir}/SHOULD_NOT_EXIST.json" >/dev/null 2>&1; then
  echo '상태: Fail - Unlimited credit 잔류를 readiness가 허용했습니다.' >&2
  exit 1
fi
jq '.cpu_credit_mode="standard"' "${evidence}/phase-evidence.json" >"${tmp_dir}/good-phase.json"
mv "${tmp_dir}/good-phase.json" "${evidence}/phase-evidence.json"
jq '.release_tag="v9.9.9"' "${evidence}/security.json" >"${tmp_dir}/bad-security.json"
mv "${tmp_dir}/bad-security.json" "${evidence}/security.json"
if "${script}" "${evidence}" "${tmp_dir}/SHOULD_NOT_EXIST_2.json" >/dev/null 2>&1; then
  echo '상태: Fail - 다른 release의 승인 evidence 재사용을 허용했습니다.' >&2
  exit 1
fi
echo '상태: Pass - BUDGET_PRODUCTION_READY의 비용·복구·보안·Standard credit gate를 확인했습니다.'

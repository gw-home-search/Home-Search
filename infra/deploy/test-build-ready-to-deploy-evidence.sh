#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/build-ready-to-deploy-evidence.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
evidence="${tmp_dir}/evidence"
mkdir -p "${evidence}"

jq -n '{format_version:2,tag:"v1.2.3",commit_sha:("a"*40),images:{},vulnerability_critical_gate_passed:true,vulnerability_policy_gate_passed:true}' >"${evidence}/release-manifest.json"
jq -n '{resource_changes:[{change:{actions:["create"]}}]}' >"${evidence}/production-plan.json"
jq -n '{status:"pass",invariants:{normalizedDuplicateCount:0,rawFirstViolationCount:0,unqueryableFailedMatchCount:0},findings:[]}' >"${evidence}/data-migration-reconciliation.json"
jq -n '{status:"pass",checksum_mismatch_count:0,marker_parity:"pass"}' >"${evidence}/restore.json"
jq -n '{status:"pass",peak_multiplier:2,map:{cold_p95_ms:1800,warm_p95_ms:400,error_rate:0.005,marker_parity:"pass"},db_cpu_percent:55,memory_headroom_percent:35}' >"${evidence}/performance.json"
jq -n '{status:"pass",outside_vpn:{admin:false,grafana:false,database:false,metrics:false,ai_direct:false},tls:"pass",waf:"pass"}' >"${evidence}/security.json"
jq -n '{status:"pass",amp_services_up:true,email_alarm_received:true,slack_alarm_received:true}' >"${evidence}/observability.json"
jq -n '{status:"pass",cases:120,terminal_violations:0,grounding_violations:0,citation_violations:0,sensitive_log_violations:0}' >"${evidence}/ai-golden.json"
jq -n '{status:"pass",graph_seconds:500,application_seconds:1700}' >"${evidence}/rollback.json"
jq -n '{status:"pass",approved:true,owner:"finops"}' >"${evidence}/cost-approval.json"
jq -n '{status:"pass",code_review:"pass",security_audit:"pass",security_findings:"none"}' >"${evidence}/reviews.json"
jq -n '{exceptions:[
  {gate:"staging_7_day_soak",owner:"release-owner",reason:"approved immediate release",release:"v1.2.3",approved_at:"2026-07-28T00:00:00Z",expires_at:"2026-07-29T00:00:00Z",rollback_trigger:"any production gate breach"},
  {gate:"supervisor_graph_canary",owner:"ai-owner",reason:"approved immediate release",release:"v1.2.3",approved_at:"2026-07-28T00:00:00Z",expires_at:"2026-07-29T00:00:00Z",rollback_trigger:"any contract breach"}
]}' >"${evidence}/release-exceptions.json"

"${script}" "${evidence}" "${tmp_dir}/READY_TO_DEPLOY.json"
jq -e '.status == "READY_TO_DEPLOY" and .release_tag == "v1.2.3" and (.artifacts | length) == 12' \
  "${tmp_dir}/READY_TO_DEPLOY.json" >/dev/null

jq '.map.cold_p95_ms = 2001' "${evidence}/performance.json" >"${tmp_dir}/bad-performance.json"
mv "${tmp_dir}/bad-performance.json" "${evidence}/performance.json"
set +e
"${script}" "${evidence}" "${tmp_dir}/blocked.json" >"${tmp_dir}/blocked.out" 2>"${tmp_dir}/blocked.err"
blocked_code=$?
set -e
[[ "${blocked_code}" == '1' ]]
grep -Fq 'performance gate' "${tmp_dir}/blocked.err"
[[ ! -e "${tmp_dir}/blocked.json" ]]
echo '상태: Pass - READY_TO_DEPLOY evidence fail-closed 계약을 확인했습니다.'

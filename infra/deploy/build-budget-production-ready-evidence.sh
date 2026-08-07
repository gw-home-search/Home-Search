#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

evidence_dir="${1:?evidence directory is required}"
output="${2:?BUDGET_PRODUCTION_READY output is required}"
[[ -d "${evidence_dir}" && ! -L "${evidence_dir}" ]] || exit 2
[[ ! -e "${output}" && ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]] || {
  echo '상태: Fail - BUDGET_PRODUCTION_READY output은 안전한 경로의 새 파일이어야 합니다.' >&2
  exit 1
}

artifacts=(
  release-manifest.json foundation-plan.json cost-estimate.json phase-evidence.json
  data-migration-reconciliation.json acceptance.json logical-restore.json ebs-restore.json
  security.json observability.json dns.json release-exceptions.json
)
for artifact in "${artifacts[@]}"; do
  path="${evidence_dir}/${artifact}"
  [[ -f "${path}" && ! -L "${path}" ]] || {
    echo "상태: Fail - 필수 budget-production evidence가 없습니다: ${artifact}" >&2
    exit 1
  }
  jq -e . "${path}" >/dev/null
  if grep -Eqi 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}' "${path}"; then
    echo "상태: Fail - evidence에 secret 형식 값이 포함됐습니다: ${artifact}" >&2
    exit 1
  fi
done

release="${evidence_dir}/release-manifest.json"
jq -e '
  .format_version == 2 and .tag != "v1.0.4"
  and (.commit_sha | test("^[0-9a-f]{40}$"))
  and .build_architecture == "linux/amd64"
  and (.images | length) == 18 and (.platform_images | length) == 2
  and all((.images + .platform_images)[]; (.digest | test("^sha256:[0-9a-f]{64}$")))
  and .vulnerability_critical_gate_passed == true
  and .vulnerability_policy_gate_passed == true
' "${release}" >/dev/null || { echo '상태: Fail - release 18+2 provenance gate가 pass가 아닙니다.' >&2; exit 1; }
tag="$(jq -er '.tag' "${release}")"
sha="$(jq -er '.commit_sha' "${release}")"
for artifact in acceptance.json security.json observability.json release-exceptions.json; do
  jq -e --arg tag "${tag}" --arg sha "${sha}" \
    '.release_tag == $tag and .commit_sha == $sha' "${evidence_dir}/${artifact}" >/dev/null || {
    echo "상태: Fail - 승인 evidence의 release binding이 일치하지 않습니다: ${artifact}" >&2
    exit 1
  }
done

jq -e '
  ["aws_db_instance","aws_db_cluster","aws_rds_cluster","aws_rds_cluster_instance","aws_msk_cluster",
   "aws_msk_serverless_cluster","aws_nat_gateway","aws_vpc_endpoint","aws_lb","aws_elasticache_cluster",
   "aws_elasticache_replication_group","aws_prometheus_workspace","aws_grafana_workspace","aws_ebs_fast_snapshot_restore"] as $forbidden
  | [.resource_changes[]? | . as $change | select((.change.actions | index("delete")) != null or ($forbidden | index($change.type)) != null)] | length == 0
' "${evidence_dir}/foundation-plan.json" >/dev/null || {
  echo '상태: Fail - foundation plan에 destroy 또는 금지 resource가 있습니다.' >&2
  exit 1
}
jq -e '
  .status == "pass"
  and (.incremental_monthly_usd | tonumber) <= 95
  and (.account_total_usd | tonumber) <= 99
' "${evidence_dir}/cost-estimate.json" >/dev/null
jq -e '
  .status == "pass"
  and .phases == ["registry","foundation","data","private","public","dns"]
  and .cpu_credit_mode == "standard"
  and .cpu_credit_balance >= 216
  and .root_free_bytes >= 8589934592
  and .data_free_bytes >= 21474836480
  and .release_digest_match == true
' "${evidence_dir}/phase-evidence.json" >/dev/null
jq -e '
  .status == "pass" and (.findings | length) == 0
  and all(.invariants[]; . == 0)
' "${evidence_dir}/data-migration-reconciliation.json" >/dev/null
jq -e '
  .status == "pass"
  and .public_inbound_tcp == [80,443]
  and .non_public_ports_blocked == true
  and .internal_routes_blocked == true
  and .container_imds_blocked == true
  and .cross_database_access_blocked == true
  and .valkey_acl_isolated == true
  and .marker_parity == true
  and .map_p95_seconds <= 2
  and .public_error_rate_percent < 1
  and .memory_p95_percent < 80
' "${evidence_dir}/acceptance.json" >/dev/null
for mode in logical ebs; do
  jq -e --arg mode "${mode}" '
    .status == "pass" and .mode == $mode and .duration_seconds <= 14400
    and .checksum_match == true and .marker_parity == true
  ' "${evidence_dir}/${mode}-restore.json" >/dev/null
done
jq -e '
  .status == "pass" and (.findings | length) == 0
  and .checkpoint == "security-audit: 지적사항 = none"
  and .secret_exposure == false
' "${evidence_dir}/security.json" >/dev/null
jq -e '.status == "pass" and .sns_email_confirmed == true and .test_alarm_received == true' \
  "${evidence_dir}/observability.json" >/dev/null
jq -e '.status == "pass" and .hostname == "homesearch.world" and .curl_resolve_passed == true and .public_dns_passed == true' \
  "${evidence_dir}/dns.json" >/dev/null
jq -e '
  .status == "pass"
  and .release_contains_staging_origin == false
  and .kakao_console_evidence == true
  and .oauth_contract_providers == ["google","kakao","naver"]
' "${evidence_dir}/release-exceptions.json" >/dev/null

hash_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
hashes='{}'
for artifact in "${artifacts[@]}"; do
  hashes="$(jq --arg name "${artifact}" --arg hash "$(hash_file "${evidence_dir}/${artifact}")" '. + {($name):$hash}' <<<"${hashes}")"
done
temporary="$(mktemp "$(dirname "${output}")/.budget-ready.XXXXXX")"
trap 'unlink "${temporary}" 2>/dev/null || true' EXIT
jq -n --arg tag "${tag}" --arg sha "${sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson artifacts "${hashes}" '
  {status:"BUDGET_PRODUCTION_READY",release_tag:$tag,commit_sha:$sha,created_at:$created_at,
   artifacts:$artifacts,
   security_impact:"보안 영향: 단일 노드 budget-production orchestration·공개 EIP·bridge network·host EBS·SSM 경계를 추가하며 기존 production root와 공개 API·저장 식별자 의미는 변경하지 않음.",
   security_audit:"security-audit: 지적사항 = none"}
' >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - BUDGET_PRODUCTION_READY evidence bundle을 생성했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

evidence_dir="${1:?evidence directory is required}"
output="${2:?READY_TO_DEPLOY output is required}"
[[ -d "${evidence_dir}" && ! -L "${evidence_dir}" ]] || {
  echo '상태: Fail - evidence directory가 안전하지 않습니다.' >&2
  exit 1
}
[[ ! -e "${output}" && ! -L "${output}" && -d "$(dirname "${output}")" && ! -L "$(dirname "${output}")" ]] || {
  echo '상태: Fail - READY_TO_DEPLOY output은 새 regular file이어야 합니다.' >&2
  exit 1
}

artifacts=(
  release-manifest.json production-plan.json data-migration-reconciliation.json restore.json
  performance.json security.json observability.json ai-golden.json rollback.json
  cost-approval.json reviews.json release-exceptions.json
)
for artifact in "${artifacts[@]}"; do
  path="${evidence_dir}/${artifact}"
  [[ -f "${path}" && ! -L "${path}" ]] || {
    echo "상태: Fail - 필수 evidence가 없습니다: ${artifact}" >&2
    exit 1
  }
  jq -e . "${path}" >/dev/null || {
    echo "상태: Fail - JSON evidence가 유효하지 않습니다: ${artifact}" >&2
    exit 1
  }
done

release="${evidence_dir}/release-manifest.json"
release_tag="$(jq -er '.tag | select(test("^v[0-9]+[.][0-9]+[.][0-9]+$"))' "${release}")"
jq -e '
  .format_version == 2
  and (.commit_sha | test("^[0-9a-f]{40}$"))
  and ((.images | keys | sort) == ["admin-api","admin-gateway","admin-migration","admin-ops","ai","backup","chat-bff","ml","ops-bootstrap","property-api","property-batch","property-flyway","public-gateway","seo-renderer","source-data-migration","user-api","user-flyway","user-insight-worker"])
  and all(.images[];
    (.digest | test("^sha256:[0-9a-f]{64}$"))
    and (.uri | test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$")))
  and .vulnerability_critical_gate_passed == true
  and .vulnerability_policy_gate_passed == true
' "${release}" >/dev/null || {
  echo '상태: Fail - release/SBOM/vulnerability gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '[.resource_changes[]? | select(.change.actions | index("delete"))] | length == 0' \
  "${evidence_dir}/production-plan.json" >/dev/null || {
  echo '상태: Fail - Terraform plan에 destroy가 있습니다.' >&2
  exit 1
}
jq -e '.status == "pass" and (.findings | length == 0) and all(.invariants[]?; . == 0)' \
  "${evidence_dir}/data-migration-reconciliation.json" >/dev/null || {
  echo '상태: Fail - migration reconciliation gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '.status == "pass" and .checksum_mismatch_count == 0 and .marker_parity == "pass"' \
  "${evidence_dir}/restore.json" >/dev/null || {
  echo '상태: Fail - restore checksum/parity gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '
  .status == "pass" and .peak_multiplier >= 2
  and .map.cold_runs >= 3 and .map.warm_runs >= 3
  and .map.cold_p95_ms <= 2000 and .map.warm_p95_ms <= 2000
  and .map.error_rate < 0.01 and .map.marker_parity == "pass"
  and .db_cpu_percent <= 60 and .memory_headroom_percent >= 30
' "${evidence_dir}/performance.json" >/dev/null || {
  echo '상태: Fail - performance gate가 목표를 충족하지 않습니다.' >&2
  exit 1
}
jq -e '
  .status == "pass" and .tls == "pass" and .waf == "pass"
  and ([.outside_vpn.admin,.outside_vpn.grafana,.outside_vpn.database,.outside_vpn.metrics,.outside_vpn.ai_direct] | all(. == false))
' "${evidence_dir}/security.json" >/dev/null || {
  echo '상태: Fail - VPN/public security boundary gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '.status == "pass" and .amp_services_up == true and .email_alarm_received == true and .slack_alarm_received == true' \
  "${evidence_dir}/observability.json" >/dev/null || {
  echo '상태: Fail - AMP/SNS observability gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '
  .status == "pass" and .cases >= 120 and .terminal_violations == 0
  and .grounding_violations == 0 and .citation_violations == 0
  and .numeric_unit_violations == 0 and .candidate_membership_violations == 0
  and .sensitive_log_violations == 0 and .top3_stability_passed == true
' "${evidence_dir}/ai-golden.json" >/dev/null || {
  echo '상태: Fail - AI golden/terminal/privacy gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e '.status == "pass" and .graph_seconds <= 600 and .application_seconds <= 1800' \
  "${evidence_dir}/rollback.json" >/dev/null || {
  echo '상태: Fail - rollback rehearsal gate가 목표 시간을 충족하지 않습니다.' >&2
  exit 1
}
jq -e '.status == "pass" and .approved == true and (.owner | type == "string" and length > 0)' \
  "${evidence_dir}/cost-approval.json" >/dev/null || {
  echo '상태: Fail - 월비용 승인이 없습니다.' >&2
  exit 1
}
jq -e '.status == "pass" and .code_review == "pass" and .security_audit == "pass" and .security_findings == "none"' \
  "${evidence_dir}/reviews.json" >/dev/null || {
  echo '상태: Fail - code-review/security-audit gate가 pass가 아닙니다.' >&2
  exit 1
}
jq -e --arg release "${release_tag}" '
  (.exceptions | length) == 2
  and ([.exceptions[].gate] | sort) == ["staging_7_day_soak","supervisor_graph_canary"]
  and all(.exceptions[];
    .release == $release
    and ([.owner,.reason,.approved_at,.expires_at,.rollback_trigger] | all(type == "string" and length > 0))
    and .approved_at < .expires_at)
' "${evidence_dir}/release-exceptions.json" >/dev/null || {
  echo '상태: Fail - release exception 승인/만료 계약이 유효하지 않습니다.' >&2
  exit 1
}

artifact_hashes='{}'
for artifact in "${artifacts[@]}"; do
  if command -v sha256sum >/dev/null 2>&1; then
    checksum="$(sha256sum "${evidence_dir}/${artifact}" | awk '{print $1}')"
  else
    checksum="$(shasum -a 256 "${evidence_dir}/${artifact}" | awk '{print $1}')"
  fi
  artifact_hashes="$(jq --arg name "${artifact}" --arg checksum "${checksum}" '. + {($name):$checksum}' <<<"${artifact_hashes}")"
done
temporary="$(mktemp "$(dirname "${output}")/.ready-to-deploy.XXXXXX")"
cleanup() { unlink "${temporary}" 2>/dev/null || true; }
trap cleanup EXIT
jq -n --arg release_tag "${release_tag}" --arg commit_sha "$(jq -r '.commit_sha' "${release}")" \
  --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson artifacts "${artifact_hashes}" \
  '{status:"READY_TO_DEPLOY",release_tag:$release_tag,commit_sha:$commit_sha,created_at:$created_at,artifacts:$artifacts,security_impact:"보안 영향: Production orchestration·network·관측·migration 경계 추가, 공개 property API와 저장 식별자 의미 변경 없음",security_audit:"security-audit: 지적사항 = none"}' \
  >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo '상태: Pass - READY_TO_DEPLOY evidence bundle을 생성했습니다.'

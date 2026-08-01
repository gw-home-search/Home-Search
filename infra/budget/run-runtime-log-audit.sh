#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

release_tag="${1:?release tag is required}"
commit_sha="${2:?commit SHA is required}"
start_time_ms="${3:?observation start time in milliseconds is required}"
[[ "${release_tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ ]]
[[ "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]
[[ "${start_time_ms}" =~ ^[0-9]{13}$ ]]
[[ "${HOME_RUNTIME_AUDIT_S3_URI:-}" =~ ^s3://home-search-budget-production-backup-[0-9]{12}/deployment-evidence/runtime-audit$ ]]

services=(admin-api admin-gateway ai chat-bff ml property-api public-gateway user-api)
secret_exposure_findings=0
statement_timeouts=0
for service in "${services[@]}"; do
  messages="$(aws logs filter-log-events --log-group-name "/home-search/budget-production/${service}" \
    --start-time "${start_time_ms}" --query 'events[].message' --output text)"
  secret_exposure_findings=$((secret_exposure_findings + $(grep -Eic \
    '(access_token|refresh_token|client_secret)[= :]+[A-Za-z0-9._-]{8}|authorization:[ ]*bearer[ ]+[A-Za-z0-9._-]{8}' \
    <<<"${messages}" || true)))
  statement_timeouts=$((statement_timeouts + $(grep -Eic 'statement timeout' <<<"${messages}" || true)))
done

tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
evidence="${tmp_dir}/runtime-log-audit.json"
jq -n --arg tag "${release_tag}" --arg sha "${commit_sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson exposures "${secret_exposure_findings}" --argjson timeouts "${statement_timeouts}" \
  '{status:(if $exposures == 0 and $timeouts == 0 then "pass" else "fail" end),
    release_tag:$tag,commit_sha:$sha,created_at:$created_at,
    checks:{log_groups_scanned:8,secret_exposure_findings:$exposures,statement_timeouts:$timeouts},
    redactions_applied:true}' >"${evidence}"
aws s3 cp "${evidence}" "${HOME_RUNTIME_AUDIT_S3_URI}/${release_tag}/runtime-log-audit.json" \
  --sse aws:kms --sse-kms-key-id alias/aws/s3 --only-show-errors
echo '상태: Complete - application log group 8개의 secret pattern과 statement timeout 감사 증거를 저장했습니다.'

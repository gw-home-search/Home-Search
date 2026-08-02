#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
cleanup() { find "${work}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${work}/bin"
cat >"${work}/bin/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$1 $2" == 'logs filter-log-events' ]]; then
  printf 'normal structured application log\n'
elif [[ "$1 $2" == 's3 cp' ]]; then
  cp "$3" "${MOCK_CAPTURE_FILE}"
else
  exit 2
fi
SCRIPT
chmod 0555 "${work}/bin/aws"
export PATH="${work}/bin:${PATH}"
export MOCK_CAPTURE_FILE="${work}/runtime-log-audit.json"
export HOME_RUNTIME_AUDIT_S3_URI=s3://home-search-budget-production-backup-123456789012/deployment-evidence/runtime-audit
"${root}/infra/budget/run-runtime-log-audit.sh" v1.0.24 "$(printf 'a%.0s' {1..40})" 1770000000000 >/dev/null
jq -e '.status == "pass" and .checks.log_groups_scanned == 8 and .checks.secret_exposure_findings == 0 and .checks.statement_timeouts == 0' \
  "${MOCK_CAPTURE_FILE}" >/dev/null
echo '상태: Pass - runtime log audit는 8개 application log group의 민감값/timeout 탐지 결과를 기록합니다.'

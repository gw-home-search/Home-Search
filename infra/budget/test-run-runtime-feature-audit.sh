#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
cleanup() { find "${work}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${work}/bin" "${work}/captured"

cat >"${work}/bin/psql" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
cat >/dev/null
count=0
[[ ! -f "${MOCK_COUNT_FILE}" ]] || count="$(cat "${MOCK_COUNT_FILE}")"
count=$((count + 1)); printf '%s' "${count}" >"${MOCK_COUNT_FILE}"
if [[ "${count}" == 1 ]]; then
  cat "${MOCK_RTMS_JSON}"
else
  cat "${MOCK_NEWS_JSON}"
fi
SCRIPT
cat >"${work}/bin/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$1 $2" == 's3 cp' ]]
cp "$3" "${MOCK_CAPTURE_DIR}/$(basename "$4")"
SCRIPT
chmod 0555 "${work}/bin/psql" "${work}/bin/aws"

cat >"${work}/rtms.json" <<'JSON'
{"execution_count":2,"executions_terminal":true,"execution_dates_current":true,"failed_work_units":0,"unfinished_work_units":0,"raw_first_violations":0,"repeat_normalized_inserted_count":0,"unexplained_failed_matches":0,"nation_daily_fresh":true,"seoul_daily_fresh":true,"nation_weekly_fresh":true,"seoul_weekly_fresh":true,"seoul_weekly_item_count":20}
JSON
cat >"${work}/news.json" <<'JSON'
{"bootstrap_execution_count":1,"bootstrap_terminal":true,"provider_failures":0,"bootstrap_raw_item_count":20,"bootstrap_article_count":20,"scope_snapshot_count":18,"nation_item_count":20,"seoul_item_count":20,"raw_first_violations":0,"duplicate_articles":0,"invalid_titles_or_urls":0,"quality_review_count":1,"quality_snapshot_count":18}
JSON

export PATH="${work}/bin:${PATH}"
export MOCK_COUNT_FILE="${work}/count"
export MOCK_RTMS_JSON="${work}/rtms.json"
export MOCK_NEWS_JSON="${work}/news.json"
export MOCK_CAPTURE_DIR="${work}/captured"
export HOME_BACKUP_PGHOST=127.0.0.1 HOME_BACKUP_PGPORT=15432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=secret
export HOME_RUNTIME_AUDIT_S3_URI=s3://home-search-budget-production-backup-123456789012/deployment-evidence/runtime-audit

id1=11111111-1111-4111-8111-111111111111
id2=22222222-2222-4222-8222-222222222222
id3=33333333-3333-4333-8333-333333333333
id4=44444444-4444-4444-8444-444444444444
"${root}/infra/budget/run-runtime-feature-audit.sh" v1.0.24 "$(printf 'a%.0s' {1..40})" "${id1}" "${id2}" "${id3}" "${id4}" >/dev/null
jq -e '.status == "pass" and .checks.raw_first and .checks.duplicate_normalized_trades == 0' "${work}/captured/rtms-catchup.json" >/dev/null
jq -e '.status == "pass" and .checks.scope_snapshot_count == 18 and .checks.quality_policy == "NEWS_V5"' "${work}/captured/news-bootstrap.json" >/dev/null

echo '상태: Pass - runtime feature audit는 DB 계산값만으로 RTMS/news 증거를 생성합니다.'

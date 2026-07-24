#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_ROOT="$(cd "${OPS_DIR}/.." && pwd)"
REPOSITORY_ROOT="$(cd "${SERVICE_ROOT}/../.." && pwd)"
RUN_DATE="${MARKET_INSIGHT_E2E_RUN_DATE:-$(TZ=Asia/Seoul date +%F)}"
RTMS_REQUEST_ID="${MARKET_INSIGHT_E2E_RTMS_REQUEST_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
EVIDENCE_DIR="${MARKET_INSIGHT_E2E_EVIDENCE_DIR:-${REPOSITORY_ROOT}/tmp/market-insight-e2e/${RUN_DATE}-${RTMS_REQUEST_ID}}"
BATCH_JAR="${PROPERTY_DATA_BATCH_JAR:-${SERVICE_ROOT}/batch/build/libs/property-data-batch.jar}"
PSQL_DSN="${MARKET_INSIGHT_E2E_PSQL_DSN:-}"
MAX_DAILY_ATTEMPTS="${MARKET_INSIGHT_E2E_MAX_DAILY_ATTEMPTS:-5}"

usage() {
  printf '사용법: 필요한 private env를 주입한 뒤 %s\n' "$0" >&2
  printf '선택: MARKET_INSIGHT_E2E_RUN_DATE, MARKET_INSIGHT_E2E_RTMS_REQUEST_ID, MARKET_INSIGHT_E2E_API_URL\n' >&2
  exit 2
}

require_environment() {
  local name
  local -a required=(
    APT_SERVICE_KEY DB_JDBC_URL DB_USERNAME DB_PASSWORD
    COORDINATE_SOURCE_DB_JDBC_URL COORDINATE_SOURCE_DB_USERNAME COORDINATE_SOURCE_DB_PASSWORD
    PROPERTY_MIGRATOR_JDBC_URL PROPERTY_MIGRATOR_DB_USERNAME PROPERTY_MIGRATOR_DB_PASSWORD
    MARKET_INSIGHT_E2E_PSQL_DSN
  )
  for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      printf '누락된 환경 변수: %s\n' "${name}" >&2
      exit 2
    fi
  done
  if [[ -n "${HOME_INGEST_RTMS_DAILY_LAWD_CDS:-}" ]]; then
    printf '거부됨: HOME_INGEST_RTMS_DAILY_LAWD_CDS는 전국 실행을 위해 공백이어야 합니다.\n' >&2
    exit 2
  fi
  if [[ "${PSQL_DSN}" == *"password="* || "${PSQL_DSN}" =~ ://[^/@]+:[^/@]+@ ]]; then
    printf '거부됨: MARKET_INSIGHT_E2E_PSQL_DSN에 password를 포함하지 마세요. DB_PASSWORD를 사용합니다.\n' >&2
    exit 2
  fi
  [[ "${RUN_DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || usage
  [[ "${RTMS_REQUEST_ID}" =~ ^[0-9a-f-]{36}$ ]] || usage
  [[ "${MAX_DAILY_ATTEMPTS}" =~ ^[1-5]$ ]] || {
    printf '거부됨: MARKET_INSIGHT_E2E_MAX_DAILY_ATTEMPTS는 1~5여야 합니다.\n' >&2
    exit 2
  }
  command -v psql >/dev/null
  command -v curl >/dev/null
}

record() {
  printf '%s=%s\n' "$1" "$2" >> "${EVIDENCE_DIR}/summary.txt"
}

run_batch() {
  local job_name="$1"
  local request_id="$2"
  local log_name="$3"
  local restart_attempt="${4:-}"
  local -a job_arguments=("runDate=${RUN_DATE}" "requestId=${request_id}")
  if [[ -n "${restart_attempt}" ]]; then
    job_arguments+=("restartAttempt=${restart_attempt}")
  fi
  SPRING_BATCH_JOB_NAME="${job_name}" \
  HOME_INGEST_RTMS_DAILY_LAWD_CDS="" \
  HOME_INGEST_RTMS_DAILY_LOOKBACK_MONTHS=2 \
  HOME_INSIGHT_TRADE_ENABLED=true \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
    "${OPS_DIR}/run-batch-jar.sh" \
      "${job_arguments[@]}" \
      > "${EVIDENCE_DIR}/${log_name}" 2>&1
}

daily_coverage_complete() {
  local coverage state scope planned completed partial failed
  coverage="$(PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
    -v execution_id="${RTMS_REQUEST_ID}" -v run_date="${RUN_DATE}" -c "
      SELECT state, scope_type, planned_work_unit_count,
             count(*) FILTER (WHERE work.state = 'COMPLETED'),
             count(*) FILTER (WHERE work.state = 'PARTIAL'),
             count(*) FILTER (WHERE work.state = 'FAILED')
      FROM rtms_collection_execution execution
      JOIN rtms_collection_work_unit work USING (execution_id)
      WHERE execution.execution_id = :'execution_id'::uuid
        AND execution.collection_mode = 'DAILY'
        AND execution.run_date = :'run_date'::date
      GROUP BY state, scope_type, planned_work_unit_count;
    ")" || return 1
  IFS='|' read -r state scope planned completed partial failed <<< "${coverage}"
  [[ "${state}" == "COMPLETED" && "${scope}" == "NATIONWIDE" && -n "${planned}" \
    && "${planned}" == "${completed}" && "${partial}" == "0" && "${failed}" == "0" ]]
}

run_daily_with_retries() {
  local attempt=0 log_name restart_attempt
  while (( attempt < MAX_DAILY_ATTEMPTS )); do
    log_name="rtms-daily.log"
    restart_attempt=""
    if (( attempt > 0 )); then
      log_name="rtms-daily-restart-${attempt}.log"
      restart_attempt="${attempt}"
    fi
    if run_batch rtmsDailyRefreshJob "${RTMS_REQUEST_ID}" "${log_name}" "${restart_attempt}"; then
      :
    fi
    if daily_coverage_complete; then
      return 0
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

verify_daily_coverage() {
  local coverage state scope planned completed partial failed
  coverage="$(PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
    -v execution_id="${RTMS_REQUEST_ID}" -v run_date="${RUN_DATE}" -c "
      SELECT state, scope_type, planned_work_unit_count,
             count(*) FILTER (WHERE work.state = 'COMPLETED'),
             count(*) FILTER (WHERE work.state = 'PARTIAL'),
             count(*) FILTER (WHERE work.state = 'FAILED')
      FROM rtms_collection_execution execution
      JOIN rtms_collection_work_unit work USING (execution_id)
      WHERE execution.execution_id = :'execution_id'::uuid
        AND execution.collection_mode = 'DAILY'
        AND execution.run_date = :'run_date'::date
      GROUP BY state, scope_type, planned_work_unit_count;
    ")"
  IFS='|' read -r state scope planned completed partial failed <<< "${coverage}"
  if [[ "${state}" != "COMPLETED" || "${scope}" != "NATIONWIDE" || -z "${planned}" \
      || "${planned}" != "${completed}" || "${partial}" != "0" || "${failed}" != "0" ]]; then
    printf '거부됨: DAILY 전국 coverage가 완전하지 않습니다. requestId=%s\n' "${RTMS_REQUEST_ID}" >&2
    exit 1
  fi
  record daily_state "${state}"
  record daily_scope "${scope}"
  record daily_planned "${planned}"
  record daily_completed "${completed}"
}

verify_published_snapshots() {
  local snapshot_counts published rejected building
  snapshot_counts="$(PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
    -v execution_id="${RTMS_REQUEST_ID}" -c "
      SELECT count(*) FILTER (WHERE build_status = 'PUBLISHED'),
             count(*) FILTER (WHERE build_status = 'REJECTED'),
             count(*) FILTER (WHERE build_status = 'BUILDING')
      FROM market_insight_snapshot
      WHERE source_execution_id = :'execution_id'::uuid
        AND period_type = 'ROLLING_7D';
    ")"
  IFS='|' read -r published rejected building <<< "${snapshot_counts}"
  if [[ "${published}" != "18" || "${rejected}" != "0" || "${building}" != "0" ]]; then
    printf '거부됨: 전국 1개와 시도 17개가 원자적으로 PUBLISHED되지 않았습니다. requestId=%s\n' "${RTMS_REQUEST_ID}" >&2
    exit 1
  fi
  record published_scope_count "${published}"
  PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '=' \
    -v execution_id="${RTMS_REQUEST_ID}" -c "
      WITH metric(metric_type) AS (VALUES
        ('ROLLING_7D_NEW_TRADE'), ('ROLLING_7D_HIGHEST_DEAL'), ('AREA_RECORD_HIGH'),
        ('AREA_PREVIOUS_RISE'), ('AREA_PREVIOUS_FALL'), ('CANCELLATION_CORRECTION')
      )
      SELECT metric.metric_type, count(item.*)
      FROM metric
      LEFT JOIN market_insight_trade_item item
        ON item.metric_type = metric.metric_type
       AND item.snapshot_id IN (
         SELECT snapshot_id FROM market_insight_snapshot
         WHERE source_execution_id = :'execution_id'::uuid
           AND period_type = 'ROLLING_7D'
           AND build_status = 'PUBLISHED'
       )
      GROUP BY metric.metric_type
      ORDER BY metric.metric_type;
    " > "${EVIDENCE_DIR}/metric-counts.txt"
}

main() {
  [[ "$#" -eq 0 ]] || usage
  require_environment
  mkdir -p "${EVIDENCE_DIR}"
  : > "${EVIDENCE_DIR}/summary.txt"
  record run_date "${RUN_DATE}"
  record rtms_request_id "${RTMS_REQUEST_ID}"
  record started_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  cd "${SERVICE_ROOT}"
  ./gradlew :api:bootJar :batch:bootJar --no-daemon --stacktrace \
    > "${EVIDENCE_DIR}/build.log" 2>&1
  [[ -f "${BATCH_JAR}" ]] || {
    printf 'batch jar가 없습니다: %s\n' "${BATCH_JAR}" >&2
    exit 2
  }
  "${OPS_DIR}/property-flyway.sh" validate \
    > "${EVIDENCE_DIR}/flyway-validate.log" 2>&1

  run_daily_with_retries || true
  verify_daily_coverage
  verify_published_snapshots

  if [[ -n "${MARKET_INSIGHT_E2E_API_URL:-}" ]]; then
    curl --fail --silent --show-error \
      "${MARKET_INSIGHT_E2E_API_URL%/}/api/v1/insights/trades/weekly?scope=NATIONWIDE&limit=10" \
      > "${EVIDENCE_DIR}/api-response.json"
  fi
  record completed_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '완료: requestId=%s evidence=%s\n' "${RTMS_REQUEST_ID}" "${EVIDENCE_DIR}"
}

main "$@"

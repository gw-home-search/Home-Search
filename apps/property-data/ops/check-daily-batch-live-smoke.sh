#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_DIR="$(cd "${OPS_DIR}/.." && pwd)"
EVIDENCE_ROOT="${API_DIR}/.live-smoke"
DB_CONTAINER="${HOME_SEARCH_DB_CONTAINER:-home-search-postgis}"
DB_USER="${HOME_SEARCH_DB_USERNAME:-home_search}"
DB_NAME="${HOME_SEARCH_DB_NAME:-home_search}"

snapshot() {
  local run_dir="$1"
  local started_at request_id
  started_at="$(cat "${run_dir}/started-at")"
  request_id="$(cat "${run_dir}/request-id")"
  docker exec -i "${DB_CONTAINER}" psql -X -v ON_ERROR_STOP=1 \
    -U "${DB_USER}" -d "${DB_NAME}" \
    -v started_at="${started_at}" -v request_id="${request_id}" \
    < "${OPS_DIR}/batch-live-smoke-queries.sql" > "${run_dir}/result.snapshot"
}

value_for() {
  local label="$1"
  local field="$2"
  awk -F '|' -v label="${label}" -v field="${field}" '$1 == label { print $field }' "${RUN_DIR}/result.snapshot"
}

run_self_test() {
  local temp_dir
  temp_dir="$(mktemp -d)"
  trap "rm -rf '${temp_dir}'" EXIT
  RUN_DIR="${temp_dir}"
  cat > "${RUN_DIR}/result.snapshot" <<'EOF'
RTMS_RUN|10|2026-06-14 00:00:00+00|3|COMPLETED
BATCH_JOB_EXECUTION|2|2026-06-14 00:00:00|1|COMPLETED
BATCH_STEP_EXECUTION|6|2026-06-14 00:00:00|3|COMPLETED
CORRELATION|1|3|0
EOF
  [[ "$(value_for RTMS_RUN 4)" == "3" ]]
  [[ "$(value_for BATCH_JOB_EXECUTION 4)" == "1" ]]
  [[ "$(value_for CORRELATION 2)" == "1" ]]
  echo "self-test passed: daily batch live smoke checker"
}

if [[ "${1:-}" == "--self-test" ]]; then
  run_self_test
  exit 0
fi
if [[ "${1:-}" == "--snapshot" ]]; then
  RUN_DIR="${2:-}"
  [[ -d "${RUN_DIR}" ]] || { echo "ERROR: run directory not found" >&2; exit 2; }
  snapshot "${RUN_DIR}"
  exit 0
fi

RUN_DIR="${1:-}"
if [[ -z "${RUN_DIR}" ]]; then
  RUN_DIR="$(find "${EVIDENCE_ROOT}" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)"
fi
if [[ -z "${RUN_DIR}" || ! -d "${RUN_DIR}" ]]; then
  echo "상태: Fail"
  echo "차단 사유: live smoke run directory가 없습니다."
  exit 2
fi
if [[ -f "${RUN_DIR}/worker-error" ]]; then
  echo "상태: Fail"
  echo "run_dir: ${RUN_DIR}"
  echo "차단 사유: $(cat "${RUN_DIR}/worker-error")"
  exit 1
fi
if [[ ! -f "${RUN_DIR}/completed-at" ]]; then
  check_after="$(cat "${RUN_DIR}/check-after-epoch" 2>/dev/null || echo 0)"
  if [[ "$(date +%s)" -lt "${check_after}" ]]; then
    echo "상태: Running"
    echo "run_dir: ${RUN_DIR}"
    echo "검증 공백: 아직 확인 시각 전입니다."
    exit 2
  fi
  echo "상태: Partial"
  echo "run_dir: ${RUN_DIR}"
  echo "검증 공백: worker가 예정 시각까지 완료되지 않았습니다."
  exit 1
fi

[[ -f "${RUN_DIR}/result.snapshot" ]] || snapshot "${RUN_DIR}"
rtms_since="$(value_for RTMS_RUN 4)"
batch_jobs_since="$(value_for BATCH_JOB_EXECUTION 4)"
batch_steps_since="$(value_for BATCH_STEP_EXECUTION 4)"
correlated_batch_executions="$(value_for CORRELATION 2)"
correlated_rtms_runs="$(value_for CORRELATION 3)"
missing_correlation_runs="$(value_for CORRELATION 4)"
status="Pass"
gaps=()
[[ "${rtms_since:-0}" -gt 0 ]] || { status="Fail"; gaps+=("RTMS run 증가 없음"); }
[[ "${batch_jobs_since:-0}" -gt 0 ]] || { status="Fail"; gaps+=("Batch JobExecution 증가 없음"); }
[[ "${batch_steps_since:-0}" -gt 0 ]] || { status="Fail"; gaps+=("Batch StepExecution 증가 없음"); }
[[ "${correlated_batch_executions:-0}" == "1" ]] || { status="Fail"; gaps+=("requestId Batch execution이 정확히 한 건이 아님"); }
[[ "${correlated_rtms_runs:-0}" -gt 0 ]] || { status="Fail"; gaps+=("requestId와 일치하는 RTMS run 없음"); }
[[ "${missing_correlation_runs:-1}" == "0" ]] || { status="Fail"; gaps+=("신규 RTMS run correlation 누락"); }
if [[ "$(cat "${RUN_DIR}/application-exit-code" 2>/dev/null || echo 1)" != "0" ]]; then
  status="Fail"
  gaps+=("batch process exit code non-zero")
fi
if grep -Eq "Hermes Slack notification failed|notification_status=FAILED" "${RUN_DIR}/application.log" 2>/dev/null; then
  status="Fail"
  gaps+=("Hermes notification 실패 로그 발견")
fi

echo "상태: ${status}"
echo "run_dir: ${RUN_DIR}"
echo "검증 근거 확인: rtmsRuns=${rtms_since:-0}, batchJobs=${batch_jobs_since:-0}, batchSteps=${batch_steps_since:-0}, correlatedBatchExecutions=${correlated_batch_executions:-0}, correlatedRtmsRuns=${correlated_rtms_runs:-0}"
if [[ "${#gaps[@]}" -gt 0 ]]; then
  printf '검증 공백: %s\n' "$(IFS=', '; echo "${gaps[*]}")"
else
  echo "검증 공백: Slack 실제 도착은 채널에서 육안 확인 필요"
fi
[[ "${status}" != "Fail" ]]

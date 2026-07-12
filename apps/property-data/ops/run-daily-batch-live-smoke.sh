#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_DIR="$(cd "${OPS_DIR}/.." && pwd)"
EVIDENCE_ROOT="${API_DIR}/.live-smoke"
DB_CONTAINER="${HOME_SEARCH_DB_CONTAINER:-home-search-postgis}"
DB_USER="${HOME_SEARCH_DB_USERNAME:-home_search}"
DB_NAME="${HOME_SEARCH_DB_NAME:-home_search}"
PRIVATE_ENV_FILE="${HOME_SEARCH_API_ENV_FILE:-}"

usage() {
  cat <<'EOF'
Usage:
  HOME_SEARCH_API_ENV_FILE=/absolute/private.env ./ops/run-daily-batch-live-smoke.sh
  ./ops/run-daily-batch-live-smoke.sh --self-test

The command starts a run-and-exit batch worker and never prints secret values.
Check the result later with ./ops/check-daily-batch-live-smoke.sh.
EOF
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]] || [[ "${value}" == "0" ]]; then
    echo "ERROR: ${name} must be a positive integer" >&2
    exit 2
  fi
}

snapshot() {
  local started_at="$1"
  local request_id="$2"
  local output="$3"
  docker exec -i "${DB_CONTAINER}" psql -X -v ON_ERROR_STOP=1 \
    -U "${DB_USER}" -d "${DB_NAME}" \
    -v started_at="${started_at}" -v request_id="${request_id}" \
    < "${OPS_DIR}/batch-live-smoke-queries.sql" > "${output}"
}

validate_private_env() {
  (
    set -a
    source "${PRIVATE_ENV_FILE}"
    set +a
    local required name
    required=(APT_SERVICE_KEY HERMES_SLACK_URL HERMES_AUTH_TOKEN HERMES_SLACK_CHANNEL)
    for name in "${required[@]}"; do
      if [[ -z "${!name:-}" ]]; then
        echo "ERROR: private env is missing required value: ${name}" >&2
        exit 2
      fi
    done
  )
}

run_self_test() {
  [[ -f "${OPS_DIR}/batch-live-smoke-queries.sql" ]]
  [[ -x "${OPS_DIR}/run-batch-jar.sh" ]]
  grep -q "SPRING_BATCH_JOB_NAME=rtmsDailyRefreshJob" "$0"
  grep -q "HOME_OPS_HERMES_ENABLED=true" "$0"
  grep -q ":batch:printBatchBootJarPath" "$0"
  grep -q 'run-batch-jar.sh' "$0"
  grep -q 'requestId=' "$0"
  grep -q "COORDINATE_SOURCE_DB_JDBC_URL=.*localhost:15435/home_search_coordinate_source" "$0"
  "${OPS_DIR}/verify-batch-packaged-process.sh" --self-test
  echo "self-test passed: daily batch live smoke runner"
}

if [[ "${1:-}" == "--self-test" ]]; then
  run_self_test
  exit 0
fi
if [[ "$#" -ne 0 ]]; then
  usage >&2
  exit 2
fi

if [[ -z "${PRIVATE_ENV_FILE}" || ! -f "${PRIVATE_ENV_FILE}" ]]; then
  echo "ERROR: HOME_SEARCH_API_ENV_FILE must point to an existing private env file" >&2
  exit 2
fi
case "${PRIVATE_ENV_FILE}" in
  *example)
    echo "ERROR: example env files cannot be used for live smoke secrets" >&2
    exit 2
    ;;
esac
if ! docker inspect "${DB_CONTAINER}" >/dev/null 2>&1; then
  echo "ERROR: database container is not running: ${DB_CONTAINER}" >&2
  exit 2
fi
validate_private_env

BOOT_JAR_OUTPUT="$(cd "${API_DIR}" && ./gradlew -q :batch:printBatchBootJarPath --no-daemon)"
BATCH_JAR="$(printf '%s\n' "${BOOT_JAR_OUTPUT}" | sed -n 's/^BATCH_BOOT_JAR=//p')"
if [[ -z "${BATCH_JAR}" || "${BATCH_JAR}" == *$'\n'* || ! -f "${BATCH_JAR}" ]]; then
  echo "ERROR: :batch:printBatchBootJarPath did not produce exactly one existing jar" >&2
  exit 1
fi

STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RUN_ID="$(date -u +"%Y%m%dT%H%M%SZ")"
RUN_DATE="$(TZ=Asia/Seoul date +"%Y-%m-%d")"
REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${EVIDENCE_ROOT}/${RUN_ID}"
mkdir -p "${RUN_DIR}"
printf '%s\n' "${STARTED_AT}" > "${RUN_DIR}/started-at"
printf '%s\n' "${RUN_DATE}" > "${RUN_DIR}/run-date"
printf '%s\n' "${REQUEST_ID}" > "${RUN_DIR}/request-id"
printf '%s\n' "$(( $(date +%s) + 300 ))" > "${RUN_DIR}/check-after-epoch"
printf '%s\n' "${BATCH_JAR}" > "${RUN_DIR}/batch-jar-path"
shasum -a 256 "${BATCH_JAR}" | awk '{print $1}' > "${RUN_DIR}/batch-jar-sha256"

snapshot "${STARTED_AT}" "${REQUEST_ID}" "${RUN_DIR}/baseline.snapshot"

WORKER="${RUN_DIR}/worker.sh"
cat > "${WORKER}" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
set -a
source "${PRIVATE_ENV_FILE}"
set +a
required=(APT_SERVICE_KEY HERMES_SLACK_URL HERMES_AUTH_TOKEN HERMES_SLACK_CHANNEL COORDINATE_SOURCE_DB_USERNAME COORDINATE_SOURCE_DB_PASSWORD)
for name in "\${required[@]}"; do
  if [[ -z "\${!name:-}" ]]; then
    echo "missing required private env: \${name}" > "${RUN_DIR}/worker-error"
    printf '2\n' > "${RUN_DIR}/exit-code"
    exit 2
  fi
done

cd "${API_DIR}"
PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
DB_JDBC_URL="\${DB_JDBC_URL:-jdbc:postgresql://localhost:15432/${DB_NAME}}" \
DB_USERNAME="\${DB_USERNAME:-${DB_USER}}" \
DB_PASSWORD="\${DB_PASSWORD:-home_search_local_password}" \
COORDINATE_SOURCE_DB_JDBC_URL="\${COORDINATE_SOURCE_DB_JDBC_URL:-jdbc:postgresql://localhost:15435/home_search_coordinate_source}" \
COORDINATE_SOURCE_DB_USERNAME="\${COORDINATE_SOURCE_DB_USERNAME}" \
COORDINATE_SOURCE_DB_PASSWORD="\${COORDINATE_SOURCE_DB_PASSWORD}" \
SPRING_BATCH_JOB_NAME=rtmsDailyRefreshJob \
HOME_INGEST_RTMS_DAILY_LAWD_CDS=11680,11710,11650 \
HOME_OPS_HERMES_ENABLED=true \
"${OPS_DIR}/run-batch-jar.sh" \
  runDate="${RUN_DATE}" \
  requestId="${REQUEST_ID}" \
  > "${RUN_DIR}/application.log" 2>&1 &
app_pid=\$!
printf '%s\n' "\${app_pid}" > "${RUN_DIR}/application.pid"
set +e
wait "\${app_pid}"
application_exit_code="\$?"
printf '%s\n' "\${application_exit_code}" > "${RUN_DIR}/application-exit-code"
set -e
"${OPS_DIR}/check-daily-batch-live-smoke.sh" --snapshot "${RUN_DIR}" || true
printf '%s\n' "\${application_exit_code}" > "${RUN_DIR}/exit-code"
date -u +"%Y-%m-%dT%H:%M:%SZ" > "${RUN_DIR}/completed-at"
exit "\${application_exit_code}"
EOF
chmod 700 "${WORKER}"
nohup "${WORKER}" > "${RUN_DIR}/worker.log" 2>&1 &
printf '%s\n' "$!" > "${RUN_DIR}/worker.pid"

echo "상태: Running"
echo "run_dir: ${RUN_DIR}"
echo "check_after_epoch: $(cat "${RUN_DIR}/check-after-epoch")"
echo "다음 행동: 시간이 지난 뒤 ./ops/check-daily-batch-live-smoke.sh 를 한 번 실행하세요."

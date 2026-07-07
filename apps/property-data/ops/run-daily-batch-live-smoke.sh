#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_DIR="$(cd "${OPS_DIR}/.." && pwd)"
EVIDENCE_ROOT="${API_DIR}/.live-smoke"
DB_CONTAINER="${HOME_SEARCH_DB_CONTAINER:-home-search-postgis}"
DB_USER="${HOME_SEARCH_DB_USERNAME:-home_search}"
DB_NAME="${HOME_SEARCH_DB_NAME:-home_search}"
DURATION_SECONDS="${HOME_BATCH_LIVE_SMOKE_DURATION_SECONDS:-420}"
SERVER_PORT="${HOME_BATCH_LIVE_SMOKE_SERVER_PORT:-18080}"
PRIVATE_ENV_FILE="${HOME_SEARCH_API_ENV_FILE:-}"

usage() {
  cat <<'EOF'
Usage:
  HOME_SEARCH_API_ENV_FILE=/absolute/private.env ./ops/run-daily-batch-live-smoke.sh
  ./ops/run-daily-batch-live-smoke.sh --self-test

The command starts a bounded background worker and never prints secret values.
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
  local output="$2"
  docker exec -i "${DB_CONTAINER}" psql -X -v ON_ERROR_STOP=1 \
    -U "${DB_USER}" -d "${DB_NAME}" -v started_at="${started_at}" \
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
  require_positive_integer "HOME_BATCH_LIVE_SMOKE_DURATION_SECONDS" "${DURATION_SECONDS}"
  require_positive_integer "HOME_BATCH_LIVE_SMOKE_SERVER_PORT" "${SERVER_PORT}"
  [[ -f "${OPS_DIR}/batch-live-smoke-queries.sql" ]]
  grep -q "HOME_INGEST_RTMS_DAILY_ENABLED=true" "$0"
  grep -q "COORDINATE_SOURCE_DB_JDBC_URL=.*localhost:15435/home_search_coordinate_source" "$0"
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

require_positive_integer "HOME_BATCH_LIVE_SMOKE_DURATION_SECONDS" "${DURATION_SECONDS}"
require_positive_integer "HOME_BATCH_LIVE_SMOKE_SERVER_PORT" "${SERVER_PORT}"
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

STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RUN_ID="$(date -u +"%Y%m%dT%H%M%SZ")"
RUN_DIR="${EVIDENCE_ROOT}/${RUN_ID}"
mkdir -p "${RUN_DIR}"
printf '%s\n' "${STARTED_AT}" > "${RUN_DIR}/started-at"
printf '%s\n' "$(( $(date +%s) + DURATION_SECONDS + 30 ))" > "${RUN_DIR}/check-after-epoch"

snapshot "${STARTED_AT}" "${RUN_DIR}/baseline.snapshot"

WORKER="${RUN_DIR}/worker.sh"
cat > "${WORKER}" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
set -a
source "${PRIVATE_ENV_FILE}"
set +a
required=(APT_SERVICE_KEY HERMES_SLACK_URL HERMES_AUTH_TOKEN HERMES_SLACK_CHANNEL)
for name in "\${required[@]}"; do
  if [[ -z "\${!name:-}" ]]; then
    echo "missing required private env: \${name}" > "${RUN_DIR}/worker-error"
    printf '2\n' > "${RUN_DIR}/exit-code"
    exit 2
  fi
done

cd "${API_DIR}"
DB_JDBC_URL="\${DB_JDBC_URL:-jdbc:postgresql://localhost:15432/${DB_NAME}}" \
DB_USERNAME="\${DB_USERNAME:-${DB_USER}}" \
DB_PASSWORD="\${DB_PASSWORD:-home_search_local_password}" \
COORDINATE_SOURCE_DB_JDBC_URL="\${COORDINATE_SOURCE_DB_JDBC_URL:-jdbc:postgresql://localhost:15435/home_search_coordinate_source}" \
COORDINATE_SOURCE_DB_USERNAME="\${COORDINATE_SOURCE_DB_USERNAME:-${DB_USER}}" \
COORDINATE_SOURCE_DB_PASSWORD="\${COORDINATE_SOURCE_DB_PASSWORD:-home_search_local_password}" \
SERVER_PORT="${SERVER_PORT}" \
HOME_INGEST_RTMS_DAILY_ENABLED=true \
HOME_INGEST_RTMS_DAILY_LAWD_CDS=11680,11710,11650 \
HOME_INGEST_RTMS_DAILY_CRON="0 * * * * *" \
HOME_INGEST_RTMS_DAILY_HERMES_ENABLED=true \
./gradlew :api:bootRun --no-daemon > "${RUN_DIR}/application.log" 2>&1 &
app_pid=\$!
printf '%s\n' "\${app_pid}" > "${RUN_DIR}/application.pid"
sleep "${DURATION_SECONDS}"
kill -TERM "\${app_pid}" 2>/dev/null || true
set +e
wait "\${app_pid}"
printf '%s\n' "\$?" > "${RUN_DIR}/application-exit-code"
set -e
"${OPS_DIR}/check-daily-batch-live-smoke.sh" --snapshot "${RUN_DIR}" || true
printf '0\n' > "${RUN_DIR}/exit-code"
date -u +"%Y-%m-%dT%H:%M:%SZ" > "${RUN_DIR}/completed-at"
EOF
chmod 700 "${WORKER}"
nohup "${WORKER}" > "${RUN_DIR}/worker.log" 2>&1 &
printf '%s\n' "$!" > "${RUN_DIR}/worker.pid"

echo "상태: Running"
echo "run_dir: ${RUN_DIR}"
echo "check_after_epoch: $(cat "${RUN_DIR}/check-after-epoch")"
echo "다음 행동: 시간이 지난 뒤 ./ops/check-daily-batch-live-smoke.sh 를 한 번 실행하세요."

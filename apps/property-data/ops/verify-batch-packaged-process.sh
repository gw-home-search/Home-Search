#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${OPS_DIR}/run-batch-jar.sh"
BATCH_JAR="${PROPERTY_DATA_BATCH_JAR:-}"

expect_exit_code() {
  local expected="$1"
  shift
  local actual
  set +e
  "$@"
  actual="$?"
  set -e
  if [[ "${actual}" -ne "${expected}" ]]; then
    echo "ERROR: expected exit code ${expected}, got ${actual}: $*" >&2
    exit 1
  fi
}

run_self_test() {
  local temp_dir fake_jar expected
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  fake_jar="${temp_dir}/fake.jar"
  : > "${fake_jar}"
  cat > "${temp_dir}/java" <<'EOF'
#!/usr/bin/env bash
set -eu
[[ "$1" == "-jar" ]]
[[ -f "$2" ]]
exit "$3"
EOF
  chmod 700 "${temp_dir}/java"
  for expected in 0 1 2; do
    expect_exit_code "${expected}" env \
      PATH="${temp_dir}:${PATH}" \
      PROPERTY_DATA_BATCH_JAR="${fake_jar}" \
      "${WRAPPER}" "${expected}"
  done
  echo "self-test passed: batch jar wrapper preserves child exit codes 0/1/2"
}

if [[ "${1:-}" == "--self-test" ]]; then
  run_self_test
  exit 0
fi
if [[ "$#" -ne 0 ]]; then
  echo "Usage: PROPERTY_DATA_BATCH_JAR=/absolute/property-data-batch.jar $0 [--self-test]" >&2
  exit 2
fi
if [[ -z "${BATCH_JAR}" || ! -f "${BATCH_JAR}" ]]; then
  echo "ERROR: PROPERTY_DATA_BATCH_JAR must identify an existing packaged batch jar" >&2
  exit 2
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

expect_exit_code 2 env -u SPRING_BATCH_JOB_NAME \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
  "${WRAPPER}" > "${temp_dir}/argument-error.log" 2>&1

expect_exit_code 1 env \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
  SPRING_BATCH_JOB_NAME=rtmsDailyRefreshJob \
  DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/home_search_unreachable \
  DB_USERNAME=packaged_smoke \
  DB_PASSWORD=packaged_smoke \
  COORDINATE_SOURCE_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/coordinate_source_unreachable \
  COORDINATE_SOURCE_DB_USERNAME=packaged_smoke \
  COORDINATE_SOURCE_DB_PASSWORD=packaged_smoke \
  "${WRAPPER}" \
  runDate=2026-07-10 \
  requestId=123e4567-e89b-12d3-a456-426614174099 \
  > "${temp_dir}/application-error.log" 2>&1

expect_exit_code 1 env \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
  SPRING_BATCH_JOB_NAME=complexBuildingMetadataJob \
  DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/home_search_unreachable \
  DB_USERNAME=packaged_smoke \
  DB_PASSWORD=packaged_smoke \
  ODC_SERVICE_KEY=packaged-smoke \
  BLD_SERVICE_KEY=packaged-smoke \
  "${WRAPPER}" \
  mode=missing \
  runDate=2026-07-10 \
  maxRequests=1 \
  requestId=123e4567-e89b-12d3-a456-426614174098 \
  > "${temp_dir}/building-metadata-application-error.log" 2>&1

expect_exit_code 1 env \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
  SPRING_BATCH_JOB_NAME=complexOdcMetadataGapFillJob \
  DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/home_search_unreachable \
  DB_USERNAME=packaged_smoke \
  DB_PASSWORD=packaged_smoke \
  ODC_SERVICE_KEY=packaged-smoke \
  "${WRAPPER}" \
  runDate=2026-07-10 \
  maxTargets=1 \
  toComplexId=1 \
  requestId=123e4567-e89b-12d3-a456-426614174097 \
  > "${temp_dir}/odc-metadata-application-error.log" 2>&1

echo "packaged-process smoke passed: argument=2 rtms=1 building-metadata=1 odc-metadata=1"

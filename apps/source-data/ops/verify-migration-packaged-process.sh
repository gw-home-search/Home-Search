#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${OPS_DIR}/run-migration-jar.sh"
MIGRATION_JAR="${SOURCE_DATA_MIGRATION_JAR:-}"

expect_exit_code() {
  local expected="$1"; shift
  local actual
  set +e; "$@"; actual="$?"; set -e
  if [[ "${actual}" -ne "${expected}" ]]; then
    echo "ERROR: expected exit code ${expected}, got ${actual}: $*" >&2
    exit 1
  fi
}

run_self_test() {
  local temp_dir fake_jar expected
  temp_dir="$(mktemp -d)"; trap 'rm -rf "${temp_dir}"' RETURN
  fake_jar="${temp_dir}/fake.jar"; : > "${fake_jar}"
  cat > "${temp_dir}/java" <<'EOF'
#!/usr/bin/env bash
set -eu
[[ "$1" == "-jar" ]]
[[ -f "$2" ]]
exit "$4"
EOF
  chmod 700 "${temp_dir}/java"
  for expected in 0 1 2; do
    expect_exit_code "${expected}" env PATH="${temp_dir}:${PATH}" SOURCE_DATA_MIGRATION_JAR="${fake_jar}" \
      "${WRAPPER}" --operation=info "${expected}"
  done
  expect_exit_code 2 env PATH="${temp_dir}:${PATH}" SOURCE_DATA_MIGRATION_JAR="${fake_jar}" \
    "${WRAPPER}" --operation=unknown
  echo "self-test passed: source-data migration wrapper preserves child exit codes and rejects invalid operations"
}

if [[ "${1:-}" == "--self-test" ]]; then run_self_test; exit 0; fi
if [[ "$#" -ne 0 || -z "${MIGRATION_JAR}" || ! -f "${MIGRATION_JAR}" ]]; then
  echo "Usage: SOURCE_DATA_MIGRATION_JAR=/absolute/source-data-migration.jar $0 [--self-test]" >&2
  exit 2
fi

temp_dir="$(mktemp -d)"; trap 'rm -rf "${temp_dir}"' EXIT
expect_exit_code 2 env SOURCE_DATA_MIGRATION_JAR="${MIGRATION_JAR}" "${WRAPPER}" --operation=unknown \
  > "${temp_dir}/argument-error.log" 2>&1
expect_exit_code 1 env SOURCE_DATA_MIGRATION_JAR="${MIGRATION_JAR}" \
  SOURCE_DATA_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/home_search_coordinate_source \
  SOURCE_DATA_DB_USERNAME=packaged_smoke SOURCE_DATA_DB_PASSWORD=packaged_smoke \
  "${WRAPPER}" --operation=info > "${temp_dir}/application-error.log" 2>&1
echo "packaged-process smoke passed: argument=2 application=1"

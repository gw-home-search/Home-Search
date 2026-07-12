#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${OPS_DIR}/run-migration-jar.sh"
MIGRATION_JAR="${PROPERTY_DATA_MIGRATION_JAR:-}"

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
  local temp_dir fake_jar evidence_file expected
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  fake_jar="${temp_dir}/fake.jar"
  : > "${fake_jar}"
  cat > "${temp_dir}/java" <<'EOF'
#!/usr/bin/env bash
set -eu
[[ "$1" == "-jar" ]]
[[ -f "$2" ]]
exit "${!#}"
EOF
  chmod 700 "${temp_dir}/java"
  evidence_file="${temp_dir}/migration-evidence.txt"
  for expected in 0 1 2; do
    expect_exit_code "${expected}" env \
      PATH="${temp_dir}:${PATH}" \
      PROPERTY_DATA_MIGRATION_JAR="${fake_jar}" \
      "${WRAPPER}" "${expected}"
  done
  expect_exit_code 2 env \
    PATH="${temp_dir}:${PATH}" \
    PROPERTY_DATA_MIGRATION_JAR="${fake_jar}" \
    "${WRAPPER}" --operation=migrate --target=7 --confirm=7 --confirm-database=home_search
  expect_exit_code 0 env \
    PATH="${temp_dir}:${PATH}" \
    PROPERTY_DATA_MIGRATION_JAR="${fake_jar}" \
    MIGRATION_EVIDENCE_FILE="${evidence_file}" \
    "${WRAPPER}" --operation=migrate --target=7 --confirm=7 --confirm-database=home_search 0
  grep -Eq '^git_sha=[0-9a-f]{40}$' "${evidence_file}"
  grep -Eq '^migration_jar_sha256=[0-9a-f]{64}$' "${evidence_file}"
  grep -Fxq 'operation=migrate' "${evidence_file}"
  echo "self-test passed: migration jar wrapper preserves child exit codes 0/1/2"
}

if [[ "${1:-}" == "--self-test" ]]; then
  run_self_test
  exit 0
fi
if [[ "$#" -ne 0 ]]; then
  echo "Usage: PROPERTY_DATA_MIGRATION_JAR=/absolute/property-data-migration.jar $0 [--self-test]" >&2
  exit 2
fi
if [[ -z "${MIGRATION_JAR}" || ! -f "${MIGRATION_JAR}" ]]; then
  echo "ERROR: PROPERTY_DATA_MIGRATION_JAR must identify an existing packaged migration jar" >&2
  exit 2
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

expect_exit_code 2 env \
  PROPERTY_DATA_MIGRATION_JAR="${MIGRATION_JAR}" \
  "${WRAPPER}" --operation=unknown > "${temp_dir}/argument-error.log" 2>&1

expect_exit_code 1 env \
  PROPERTY_DATA_MIGRATION_JAR="${MIGRATION_JAR}" \
  DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/home_search_unreachable \
  DB_USERNAME=packaged_smoke \
  DB_PASSWORD=packaged_smoke \
  "${WRAPPER}" --operation=info > "${temp_dir}/application-error.log" 2>&1

echo "packaged-process smoke passed: argument=2 application=1"

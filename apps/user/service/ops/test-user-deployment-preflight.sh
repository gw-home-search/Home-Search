#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PREFLIGHT="${SERVICE_ROOT}/ops/user-deployment-preflight.sh"
readonly TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

mkdir -p "${TEST_ROOT}/bin"
cat > "${TEST_ROOT}/bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_DOCKER_LOG}"
case "$*" in
  *current_database*)
    [[ "${FAKE_DB_FAILURE:-false}" == "false" ]] || exit 1
    printf '%s\n' "${FAKE_CURRENT_DATABASE:-home_search_user}"
    ;;
  *to_regclass*)
    printf '%s\n' "${FAKE_HISTORY_PRESENT:-false}"
    ;;
  *service_owned_relations*)
    printf '%s\n' "${FAKE_RELATION_COUNT:-0}"
    ;;
  *preflight_history_rows*)
    printf '%b' "${FAKE_HISTORY_ROWS:-}"
    ;;
  *'-outputType=json info'*)
    printf '%s\n' "${FAKE_INFO_JSON:-{\"migrations\":[]}}"
    ;;
  *'-outputType=json validate'*)
    [[ "${FAKE_VALIDATE_FAILURE:-false}" == "false" ]] || exit 1
    printf '%s\n' "${FAKE_VALIDATE_JSON:-{\"validationSuccessful\":true,\"invalidMigrations\":[]}}"
    ;;
  *)
    printf 'unexpected fake docker invocation: %s\n' "$*" >&2
    exit 1
    ;;
esac
FAKE_DOCKER
chmod +x "${TEST_ROOT}/bin/docker"
: > "${TEST_ROOT}/docker.log"

readonly PENDING_INFO='{"migrations":[{"version":"1","type":"SQL","state":"Pending"},{"version":"2","type":"SQL","state":"Pending"},{"version":"3","type":"SQL","state":"Pending"},{"version":"4","type":"SQL","state":"Pending"},{"version":"5","type":"SQL","state":"Pending"}]}'
readonly SUCCESS_INFO='{"migrations":[{"version":"1","type":"SQL","state":"Success"},{"version":"2","type":"SQL","state":"Success"},{"version":"3","type":"SQL","state":"Success"},{"version":"4","type":"SQL","state":"Success"},{"version":"5","type":"SQL","state":"Success"}]}'
readonly REPEATABLE_INFO='{"migrations":[{"version":"1","type":"SQL","state":"Pending"},{"version":"2","type":"SQL","state":"Pending"},{"version":"3","type":"SQL","state":"Pending"},{"version":"4","type":"SQL","state":"Pending"},{"version":"5","type":"SQL","state":"Pending"},{"category":"Repeatable","version":null,"type":"SQL","state":"Pending"}]}'
readonly SUCCESS_HISTORY=$'<null>|SCHEMA|t\n1|SQL|t\n2|SQL|t\n3|SQL|t\n4|SQL|t\n5|SQL|t\n'

invoke() {
    env \
        PATH="${TEST_ROOT}/bin:${PATH}" \
        FAKE_DOCKER_LOG="${TEST_ROOT}/docker.log" \
        USER_MIGRATOR_JDBC_URL=jdbc:postgresql://postgis:5432/home_search_user \
        USER_MIGRATOR_DB_USERNAME=migrator \
        USER_MIGRATOR_DB_PASSWORD=sentinel-value \
        "$@"
}

expect_exit() {
    local expected="$1"
    shift
    set +e
    "$@" > "${TEST_ROOT}/stdout" 2> "${TEST_ROOT}/stderr"
    local actual=$?
    set -e
    if [[ "${actual}" -ne "${expected}" ]]; then
        printf 'expected exit %s, got %s: %s\n' "${expected}" "${actual}" "$*" >&2
        sed -n '1,80p' "${TEST_ROOT}/stderr" >&2
        exit 1
    fi
    if grep -Fq 'sentinel-value' "${TEST_ROOT}/stdout" "${TEST_ROOT}/stderr"; then
        printf 'credential leaked from preflight output\n' >&2
        exit 1
    fi
    return 0
}

bash -n "${PREFLIGHT}"
expect_exit 2 env -u USER_MIGRATOR_DB_PASSWORD "${PREFLIGHT}" before 5
expect_exit 2 invoke "${PREFLIGHT}" before not-a-number
expect_exit 2 env \
    PATH="${TEST_ROOT}/bin:${PATH}" \
    FAKE_DOCKER_LOG="${TEST_ROOT}/docker.log" \
    USER_MIGRATOR_JDBC_URL=jdbc:postgresql://postgis:5432/wrong_database \
    USER_MIGRATOR_DB_USERNAME=migrator \
    USER_MIGRATOR_DB_PASSWORD=sentinel-value \
    "${PREFLIGHT}" before 5
: > "${TEST_ROOT}/docker.log"
expect_exit 2 invoke env USER_MIGRATOR_JDBC_URL='jdbc:postgresql://migrator:authority-sentinel@postgis:5432/home_search_user' \
    "${PREFLIGHT}" before 5
[[ ! -s "${TEST_ROOT}/docker.log" ]]
expect_exit 2 invoke env USER_MIGRATOR_JDBC_URL='jdbc:postgresql://postgis:5432/home_search_user?Password=query-sentinel' \
    "${PREFLIGHT}" before 5
[[ ! -s "${TEST_ROOT}/docker.log" ]]
! grep -Fq authority-sentinel "${TEST_ROOT}/stdout" "${TEST_ROOT}/stderr" "${TEST_ROOT}/docker.log"
! grep -Fq query-sentinel "${TEST_ROOT}/stdout" "${TEST_ROOT}/stderr" "${TEST_ROOT}/docker.log"

expect_exit 2 invoke env FAKE_CURRENT_DATABASE=wrong_database FAKE_INFO_JSON="${PENDING_INFO}" \
    "${PREFLIGHT}" before 5
expect_exit 1 invoke env FAKE_DB_FAILURE=true FAKE_INFO_JSON="${PENDING_INFO}" \
    "${PREFLIGHT}" before 5
expect_exit 2 invoke env FAKE_INFO_JSON='{"migrations":[{"version":"6","type":"SQL","state":"Pending"}]}' \
    "${PREFLIGHT}" before 5
expect_exit 2 invoke env FAKE_INFO_JSON="${REPEATABLE_INFO}" \
    "${PREFLIGHT}" before 5
expect_exit 2 invoke env FAKE_RELATION_COUNT=1 FAKE_INFO_JSON="${PENDING_INFO}" \
    "${PREFLIGHT}" before 5

before_output="$(invoke env FAKE_INFO_JSON="${PENDING_INFO}" "${PREFLIGHT}" before 5)"
if [[ "${before_output}" != 'service=user-service phase=before target=5 state=EMPTY' ]]; then
    printf 'unexpected before success output: %s\n' "${before_output}" >&2
    exit 1
fi

after_output="$(invoke env \
    FAKE_HISTORY_PRESENT=true \
    FAKE_RELATION_COUNT=5 \
    FAKE_INFO_JSON="${SUCCESS_INFO}" \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" \
    "${PREFLIGHT}" after 5)"
if [[ "${after_output}" != 'service=user-service phase=after target=5 state=READY' ]]; then
    printf 'unexpected after success output: %s\n' "${after_output}" >&2
    exit 1
fi

expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"JDBC","state":"Success"}]}' \
    FAKE_HISTORY_ROWS=$'2|JDBC|t\n' "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"SQL","state":"Deleted"}]}' \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"SQL","state":"Out of Order"}]}' \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"1","type":"BASELINE","state":"Success"}]}' \
    FAKE_HISTORY_ROWS=$'1|BASELINE|t\n' "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON="${SUCCESS_INFO}" \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}"$'<null>|JDBC|t\n' "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"SQL","state":"Success"},{"version":"2","type":"SQL","state":"Success"}]}' \
    FAKE_HISTORY_ROWS=$'2|SQL|t\n2|SQL|t\n' "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"SQL","state":"Missing"}]}' \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON='{"migrations":[{"version":"2","type":"SQL","state":"Ignored"}]}' \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" "${PREFLIGHT}" after 5
expect_exit 2 invoke env FAKE_HISTORY_PRESENT=true FAKE_INFO_JSON="${SUCCESS_INFO}" \
    FAKE_HISTORY_ROWS=$'1|SQL|t\n2|SQL|f\n3|SQL|t\n4|SQL|t\n5|SQL|t\n' "${PREFLIGHT}" after 5
expect_exit 2 invoke env \
    FAKE_HISTORY_PRESENT=true \
    FAKE_INFO_JSON="${SUCCESS_INFO}" \
    FAKE_HISTORY_ROWS="${SUCCESS_HISTORY}" \
    FAKE_VALIDATE_JSON='{"validationSuccessful":false,"invalidMigrations":[{"version":"2"}]}' \
    "${PREFLIGHT}" after 5

if grep -Fq 'sentinel-value' "${TEST_ROOT}/docker.log"; then
    printf 'credential leaked into docker arguments\n' >&2
    exit 1
fi

printf 'user deployment preflight contract passed\n'

#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly WRAPPER="${SERVICE_ROOT}/ops/user-flyway.sh"
readonly COMPOSE_FILE="${SERVICE_ROOT}/../../../infra/docker-compose.local.yml"
readonly TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

expect_rejected() {
    if "$@" >/dev/null 2>&1; then
        printf '예상한 거부가 발생하지 않았습니다: %s\n' "$*" >&2
        exit 1
    fi
}

bash -n "${WRAPPER}"
expect_rejected "${WRAPPER}"
expect_rejected "${WRAPPER}" latest
expect_rejected "${WRAPPER}" clean
expect_rejected "${WRAPPER}" repair
expect_rejected "${WRAPPER}" baseline
expect_rejected "${WRAPPER}" migrate
expect_rejected "${WRAPPER}" migrate latest
expect_rejected "${WRAPPER}" migrate 5 -cleanDisabled=false
expect_rejected env \
    USER_MIGRATOR_JDBC_URL=jdbc:postgresql://localhost:5432/wrong_database \
    USER_MIGRATOR_DB_USERNAME=migrator \
    USER_MIGRATOR_DB_PASSWORD=redacted \
    "${WRAPPER}" info

grep -Fq 'image: redgate/flyway:12.4.0' "${COMPOSE_FILE}"
grep -Fq '../apps/user/service/db/migration/user:/flyway/sql:ro' "${COMPOSE_FILE}"
grep -Fq '../apps/user/service/db:/flyway/conf:ro' "${COMPOSE_FILE}"

mkdir -p "${TEST_ROOT}/bin"
cat > "${TEST_ROOT}/bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_DOCKER_LOG}"
if [[ " $* " == *' -outputType=json info '* ]]; then
    printf '{"migrations":[{"version":"5","state":"Success"},{"version":"6","state":"Pending"}]}\n'
fi
FAKE_DOCKER
chmod +x "${TEST_ROOT}/bin/docker"
: > "${TEST_ROOT}/docker.log"
: > "${TEST_ROOT}/evidence.log"

wrapper_environment=(
    env
    PATH="${TEST_ROOT}/bin:${PATH}"
    FAKE_DOCKER_LOG="${TEST_ROOT}/docker.log"
    USER_MIGRATOR_JDBC_URL=jdbc:postgresql://localhost:5432/home_search_user
    USER_MIGRATOR_DB_USERNAME=migrator
    USER_MIGRATOR_DB_PASSWORD=not-recorded
    MIGRATION_EVIDENCE_FILE="${TEST_ROOT}/evidence.log"
)

expect_rejected "${wrapper_environment[@]}" "${WRAPPER}" migrate 5
"${wrapper_environment[@]}" "${WRAPPER}" migrate 6 >/dev/null
grep -Fq -- '-target=6 migrate' "${TEST_ROOT}/docker.log"
grep -Fq -- 'redgate/flyway:12.4.0 validate' "${TEST_ROOT}/docker.log"
grep -Fq -- '/db/migration/user:/flyway/sql:ro' "${TEST_ROOT}/docker.log"
grep -Eq 'service=user-service target=6 image=redgate/flyway:12\.4\.0 git_sha=[0-9a-f]+' \
    "${TEST_ROOT}/evidence.log"
if grep -Fq 'not-recorded' "${TEST_ROOT}/evidence.log"; then
    printf 'migration evidence에 credential이 기록됐습니다.\n' >&2
    exit 1
fi

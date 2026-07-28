#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
suffix="${RANDOM}-$$"
api_image="home-search-property-api:test-${suffix}"
batch_image="home-search-property-batch:test-${suffix}"
flyway_image="home-search-property-flyway:test-${suffix}"
tmp_dir="$(mktemp -d)"

cleanup() {
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

docker build --tag "${api_image}" --file apps/property-data/api/Dockerfile .
docker build --tag "${batch_image}" --file apps/property-data/batch/Dockerfile .
docker build --tag "${flyway_image}" --file apps/property-data/db/Dockerfile .

assert_user() {
  local image="$1"
  [[ "$(docker inspect --format '{{.Config.User}}' "${image}")" == '10001:10001' ]]
}
assert_user "${api_image}"
assert_user "${batch_image}"
assert_user "${flyway_image}"

[[ "$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "${api_image}")" != 'null' ]]
docker run --rm --entrypoint sh "${api_image}" -c \
  'command -v sh >/dev/null && command -v wget >/dev/null'

[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${api_image}")" == '["java","-jar","/app/application.jar"]' ]]
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${batch_image}")" == '["java","-jar","/app/application.jar"]' ]]
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${flyway_image}")" == '["/flyway/flyway"]' ]]
[[ "$(docker inspect --format '{{json .Config.Cmd}}' "${flyway_image}")" == '["migrate"]' ]]

api_container="$(docker create "${api_image}")"
batch_container="$(docker create "${batch_image}")"
docker cp "${api_container}:/app/application.jar" "${tmp_dir}/api.jar"
docker cp "${batch_container}:/app/application.jar" "${tmp_dir}/batch.jar"
docker rm "${api_container}" "${batch_container}" >/dev/null

jar tf "${tmp_dir}/api.jar" | grep -q 'BOOT-INF/classes/com/home/HomeSearchApiApplication.class'
if jar tf "${tmp_dir}/api.jar" | grep -q 'BOOT-INF/classes/com/home/batch/'; then
  echo '상태: Fail - property API image에 batch class가 포함되었습니다.' >&2
  exit 1
fi
jar tf "${tmp_dir}/batch.jar" | grep -q 'BOOT-INF/classes/com/home/batch/PropertyDataBatchApplication.class'
if jar tf "${tmp_dir}/batch.jar" | grep -q 'BOOT-INF/classes/com/home/HomeSearchApiApplication.class'; then
  echo '상태: Fail - property batch image에 API application class가 포함되었습니다.' >&2
  exit 1
fi

docker run --rm --entrypoint sh "${api_image}" -c \
  'test -f /app/application.jar && test ! -e /flyway/sql && test "$(find /app -maxdepth 1 -type f | wc -l)" -eq 1'
docker run --rm --entrypoint sh "${batch_image}" -c \
  'test -f /app/application.jar && test ! -e /flyway/sql && test "$(find /app -maxdepth 1 -type f | wc -l)" -eq 1'
docker run --rm --entrypoint sh "${flyway_image}" -c \
  'test -d /flyway/sql && test -f /flyway/conf/flyway.conf && test ! -e /app/application.jar && test ! -e /flyway/lib/aad && test ! -e /flyway/lib/opentelemetry && test ! -e /flyway/lib/oracle_wallet && test ! -e /flyway/lib/sqlfluff'
docker run --rm "${flyway_image}" -v >/dev/null

echo '상태: Pass - property API, batch, Flyway image의 command, JAR 경계, non-root를 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

suffix="${RANDOM}-$$"
tmp_dir="$(mktemp -d)"
admin_api="home-search-admin-api:test-${suffix}"
admin_migration="home-search-admin-migration:test-${suffix}"
admin_ops="home-search-admin-ops:test-${suffix}"
user_api="home-search-user-api:test-${suffix}"
user_worker="home-search-user-insight-worker:test-${suffix}"
user_flyway="home-search-user-flyway:test-${suffix}"
source_migration="home-search-source-migration:test-${suffix}"

cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

docker build --target api --tag "${admin_api}" --file apps/admin/service/Dockerfile .
docker build --target migration --tag "${admin_migration}" --file apps/admin/service/Dockerfile .
docker build --target ops --tag "${admin_ops}" --file apps/admin/service/Dockerfile .
docker build --target app --tag "${user_api}" --file apps/user/service/Dockerfile .
docker build --target worker --tag "${user_worker}" --file apps/user/service/Dockerfile .
docker build --target flyway --tag "${user_flyway}" --file apps/user/service/Dockerfile .
docker build --tag "${source_migration}" --file apps/source-data/Dockerfile .

for image in "${admin_api}" "${admin_migration}" "${admin_ops}" "${user_api}" "${user_worker}" "${user_flyway}" "${source_migration}"; do
  [[ "$(docker inspect --format '{{.Config.User}}' "${image}")" == '10001:10001' ]]
done
for image in "${admin_api}" "${user_api}"; do
  [[ "$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "${image}")" != 'null' ]]
  docker run --rm --entrypoint sh "${image}" -c \
    'command -v timeout >/dev/null && command -v bash >/dev/null'
done
for image in "${admin_api}" "${admin_migration}" "${admin_ops}" "${user_api}" "${user_worker}" "${source_migration}"; do
  [[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${image}")" == '["java","-jar","/app/application.jar"]' ]]
done
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${user_flyway}")" == '["/flyway/flyway"]' ]]

copy_jar() {
  local image="$1"
  local destination="$2"
  local container
  container="$(docker create "${image}")"
  docker cp "${container}:/app/application.jar" "${destination}"
  docker rm "${container}" >/dev/null
}
copy_jar "${admin_api}" "${tmp_dir}/admin-api.jar"
copy_jar "${admin_migration}" "${tmp_dir}/admin-migration.jar"
copy_jar "${admin_ops}" "${tmp_dir}/admin-ops.jar"
copy_jar "${user_api}" "${tmp_dir}/user-api.jar"
copy_jar "${user_worker}" "${tmp_dir}/user-worker.jar"
copy_jar "${source_migration}" "${tmp_dir}/source-migration.jar"

jar tf "${tmp_dir}/admin-api.jar" | grep -q 'BOOT-INF/classes/com/home/admin/AdminServiceApplication.class'
if jar tf "${tmp_dir}/admin-api.jar" | grep -Eq 'BOOT-INF/classes/com/home/admin/(migration|ops)/'; then
  echo '상태: Fail - admin API image에 migration/ops class가 포함되었습니다.' >&2
  exit 1
fi
jar tf "${tmp_dir}/admin-migration.jar" | grep -q 'BOOT-INF/classes/com/home/admin/migration/AdminMigrationApplication.class'
jar tf "${tmp_dir}/admin-migration.jar" | grep -q 'BOOT-INF/classes/db/migration/admin/'
jar tf "${tmp_dir}/admin-ops.jar" | grep -q 'BOOT-INF/classes/com/home/admin/ops/AdminOpsApplication.class'
if jar tf "${tmp_dir}/admin-ops.jar" | grep -q 'BOOT-INF/classes/db/migration/'; then
  echo '상태: Fail - admin ops image에 migration SQL이 포함되었습니다.' >&2
  exit 1
fi
jar tf "${tmp_dir}/user-api.jar" | grep -q 'BOOT-INF/classes/com/home/user/UserServiceApplication.class'
if jar tf "${tmp_dir}/user-api.jar" | grep -q 'BOOT-INF/classes/db/migration/'; then
  echo '상태: Fail - user API image에 migration SQL이 포함되었습니다.' >&2
  exit 1
fi
jar tf "${tmp_dir}/user-worker.jar" | grep -q 'BOOT-INF/classes/com/home/user/worker/UserInsightWorkerApplication.class'
if jar tf "${tmp_dir}/user-worker.jar" | grep -q 'BOOT-INF/classes/db/migration/'; then
  echo '상태: Fail - user insight worker image에 migration SQL이 포함되었습니다.' >&2
  exit 1
fi
jar tf "${tmp_dir}/source-migration.jar" | grep -q 'BOOT-INF/classes/com/home/sourcedata/migration/SourceDataMigrationRunner.class'

for image in "${admin_api}" "${admin_migration}" "${admin_ops}" "${user_api}" "${user_worker}" "${source_migration}"; do
  docker run --rm --entrypoint sh "${image}" -c \
    'test -f /app/application.jar && test ! -e /flyway/sql && test "$(find /app -maxdepth 1 -type f | wc -l)" -eq 1'
done
docker run --rm --entrypoint sh "${user_flyway}" -c \
  'test -d /flyway/sql && test -f /flyway/conf/flyway.conf && test ! -e /app/application.jar'

[[ "$(docker inspect --format '{{json .Config.Cmd}}' "${user_flyway}")" == '["migrate"]' ]]
echo '상태: Pass - admin/user/source-data image의 artifact, entrypoint, non-root 경계를 확인했습니다.'

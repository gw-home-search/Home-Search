#!/usr/bin/env bash
set -Eeuo pipefail

suffix="${RANDOM}-$$"
postgres_image="home-search-budget-postgres:test-${suffix}"
valkey_image="home-search-budget-valkey:test-${suffix}"
postgres_container="home-search-budget-postgres-runtime-${suffix}"
valkey_container="home-search-budget-valkey-runtime-${suffix}"
cleanup() {
  docker stop --time 1 "${postgres_container}" "${valkey_container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker build --tag "${postgres_image}" --file infra/budget/postgres/Dockerfile .
docker build --tag "${valkey_image}" --file infra/budget/valkey/Dockerfile .

[[ "$(docker inspect --format '{{.Config.User}}' "${postgres_image}")" == '70:70' ]]
[[ "$(docker inspect --format '{{.Config.User}}' "${valkey_image}")" == '999:1000' ]]
for image in "${postgres_image}" "${valkey_image}"; do
  [[ "$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "${image}")" != 'null' ]]
done

docker run --rm --entrypoint sh "${postgres_image}" -c \
  'postgres --version | grep -Eq "PostgreSQL[)]? 17[.]" && test -f /usr/local/share/postgresql/extension/postgis.control && test ! -e /usr/local/bin/gosu && ! apk info -e tiff && test ! -e /usr/local/lib/postgresql/postgis_raster-3.so'
docker run --rm --entrypoint sh "${valkey_image}" -c \
  'valkey-server --version | grep -Eq "v=8[.]1[.]" && grep -Fxq "appendonly no" /etc/valkey/valkey.conf && grep -Fxq "save \"\"" /etc/valkey/valkey.conf && grep -Fxq "maxmemory 256mb" /etc/valkey/valkey.conf'

password_a='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
password_b='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
password_c='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
password_d='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'

docker run --rm --detach --name "${postgres_container}" \
  --tmpfs /var/lib/postgresql/data:rw,uid=70,gid=70,mode=0700 \
  --tmpfs /run/home-search-postgres-tls:rw,uid=70,gid=70,mode=0700 \
  --env POSTGRES_USER=home_search_bootstrap \
  --env POSTGRES_DB=home_search \
  --env POSTGRES_PASSWORD="${password_a}" \
  --env PROPERTY_RUNTIME_DB_PASSWORD="${password_b}" \
  --env PROPERTY_MIGRATOR_DB_PASSWORD="${password_c}" \
  --env PROPERTY_IMPORTER_DB_PASSWORD="${password_d}" \
  --env AI_PROPERTY_READER_DB_PASSWORD="${password_b}" \
  --env USER_RUNTIME_DB_PASSWORD="${password_b}" \
  --env USER_MIGRATOR_DB_PASSWORD="${password_c}" \
  --env ADMIN_RUNTIME_DB_PASSWORD="${password_b}" \
  --env ADMIN_MIGRATOR_DB_PASSWORD="${password_c}" \
  --env AI_DATA_RUNTIME_DB_PASSWORD="${password_b}" \
  --env AI_DATA_MIGRATOR_DB_PASSWORD="${password_c}" \
  --env AI_DATA_IMPORTER_DB_PASSWORD="${password_d}" \
  --env BACKUP_DB_PASSWORD="${password_d}" \
  "${postgres_image}" >/dev/null

postgres_ready=false
for _ in $(seq 1 90); do
  if docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
    psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=require' \
      -X -Atqc 'SELECT 1' >/dev/null 2>&1; then
    postgres_ready=true
    break
  fi
  sleep 1
done
if [[ "${postgres_ready}" != 'true' ]]; then
  docker logs "${postgres_container}" >&2
  echo '상태: Fail - budget PostgreSQL가 준비되지 않았습니다.' >&2
  exit 1
fi

[[ "$(docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=require' \
    -X -Atqc 'SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()')" == 't' ]]
[[ "$(docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=require' \
    -X -Atqc "SELECT string_agg(datname, ',' ORDER BY datname) FROM pg_database WHERE datname LIKE 'home_search%'")" \
  == 'home_search,home_search_admin,home_search_ai,home_search_user' ]]
[[ "$(docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=require' \
    -X -Atqc "SELECT count(*) FROM pg_tables WHERE schemaname = 'news' AND tablename LIKE 'region_month_signal_%'")" == '3' ]]
[[ "$(docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=require' \
    -X -Atqc "SELECT count(*) FROM pg_tables WHERE schemaname = 'news' AND tableowner <> 'home_search_property_migrator'")" == '0' ]]
if docker exec --env PGPASSWORD="${password_b}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_user_runtime sslmode=require' \
    -X -Atqc 'SELECT 1' >/dev/null 2>&1; then
  echo '상태: Fail - User runtime role이 Property DB에 cross-access했습니다.' >&2
  exit 1
fi
if docker exec --env PGPASSWORD="${password_a}" "${postgres_container}" \
  psql 'host=127.0.0.1 dbname=home_search user=home_search_bootstrap sslmode=disable' \
    -X -Atqc 'SELECT 1' >/dev/null 2>&1; then
  echo '상태: Fail - non-SSL PostgreSQL 연결이 허용됐습니다.' >&2
  exit 1
fi

docker run --rm --detach --name "${valkey_container}" \
  --tmpfs /data:rw,uid=999,gid=1000,mode=0700 \
  --env VALKEY_ADMIN_PASSWORD="${password_a}" \
  --env VALKEY_PROPERTY_PASSWORD="${password_b}" \
  --env VALKEY_BFF_PASSWORD="${password_c}" \
  "${valkey_image}" >/dev/null

valkey_ready=false
for _ in $(seq 1 30); do
  if docker exec "${valkey_container}" valkey-cli --no-auth-warning \
    --user admin -a "${password_a}" ping 2>/dev/null | grep -qx PONG; then
    valkey_ready=true
    break
  fi
  sleep 1
done
if [[ "${valkey_ready}" != 'true' ]]; then
  docker logs "${valkey_container}" >&2
  echo '상태: Fail - budget Valkey가 준비되지 않았습니다.' >&2
  exit 1
fi

docker exec "${valkey_container}" valkey-cli --no-auth-warning \
  --user property -a "${password_b}" SET home-search:map:smoke value | grep -qx OK
docker exec "${valkey_container}" valkey-cli --no-auth-warning \
  --user property -a "${password_b}" GET home-search:map:smoke | grep -qx value
docker exec "${valkey_container}" valkey-cli --no-auth-warning \
  --user bff -a "${password_c}" SET home-search:chatbot:rate-limit:smoke 1 | grep -qx OK
docker exec "${valkey_container}" valkey-cli --no-auth-warning \
  --user property -a "${password_b}" FLUSHALL | grep -Eq 'NOPERM|permission'
docker exec "${valkey_container}" valkey-cli --no-auth-warning \
  --user bff -a "${password_c}" GET home-search:map:smoke | grep -Eq 'NOPERM|permission'

echo '상태: Pass - budget PostgreSQL/PostGIS SSL·role 격리와 Valkey ACL을 포함한 pinned non-root 경계를 확인했습니다.'

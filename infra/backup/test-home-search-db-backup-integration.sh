#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
image="${HOME_BACKUP_POSTGRES_IMAGE:-postgres:16-alpine}"
suffix="${RANDOM}-$$"
network="home-search-backup-test-${suffix}"
source_container="home-search-backup-source-${suffix}"
test_dir="$(mktemp -d)"
backup_dir="${test_dir}/backups"
password='integration-backup-password'

cleanup() {
  docker stop --time 1 "${source_container}" >/dev/null 2>&1 || true
  docker network remove "${network}" >/dev/null 2>&1 || true
  find "${test_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
mkdir -p "${backup_dir}"
chmod 0777 "${backup_dir}"

docker network create "${network}" >/dev/null
docker run --rm --detach --name "${source_container}" --network "${network}" \
  --network-alias source-postgres \
  --env POSTGRES_DB=home_search \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD="${password}" \
  "${image}" >/dev/null

ready=false
for _ in $(seq 1 60); do
  readiness_count="$(docker logs "${source_container}" 2>&1 | grep -c 'database system is ready to accept connections' || true)"
  if (( readiness_count >= 2 )) && \
      docker exec "${source_container}" psql -X -At -U postgres -d home_search -c 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 0.5
done
[[ "${ready}" == 'true' ]] || { echo '상태: Fail - source PostgreSQL이 준비되지 않았습니다.' >&2; exit 1; }

docker exec "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -c 'CREATE DATABASE home_search_admin' \
  -c 'CREATE DATABASE home_search_user' \
  -c 'CREATE DATABASE home_search_ai' \
  -c 'CREATE DATABASE home_search_coordinate_source' >/dev/null
docker exec -i "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d home_search <<'SQL' >/dev/null
CREATE TABLE public.flyway_schema_history (installed_rank integer PRIMARY KEY, success boolean NOT NULL);
CREATE TABLE public.raw_trade_ingest (id bigint PRIMARY KEY);
INSERT INTO public.flyway_schema_history VALUES (1, true), (2, true), (3, true);
INSERT INTO public.raw_trade_ingest VALUES (1), (2);
SQL
docker exec -i "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d home_search_admin <<'SQL' >/dev/null
CREATE SCHEMA admin;
CREATE TABLE admin.flyway_schema_history (installed_rank integer PRIMARY KEY, success boolean NOT NULL);
CREATE TABLE admin.admin_account (id bigint PRIMARY KEY);
INSERT INTO admin.flyway_schema_history VALUES (1, true);
INSERT INTO admin.admin_account VALUES (10);
SQL
docker exec -i "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d home_search_user <<'SQL' >/dev/null
CREATE SCHEMA users;
CREATE TABLE users.flyway_schema_history (installed_rank integer PRIMARY KEY, success boolean NOT NULL);
CREATE TABLE users.user_account (id bigint PRIMARY KEY);
INSERT INTO users.flyway_schema_history VALUES (1, true), (2, true);
INSERT INTO users.user_account VALUES (20), (21), (22);
SQL
docker exec -i "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d home_search_ai <<'SQL' >/dev/null
CREATE TABLE public.ai_schema_history (version integer PRIMARY KEY, description text NOT NULL, checksum char(64) NOT NULL);
CREATE TABLE public.dataset_source (source_id text PRIMARY KEY);
INSERT INTO public.ai_schema_history VALUES (1, 'fixture', repeat('a', 64));
INSERT INTO public.dataset_source VALUES ('academy'), ('rail');
SQL
docker exec -i "${source_container}" psql -X -v ON_ERROR_STOP=1 -U postgres -d home_search_coordinate_source <<'SQL' >/dev/null
CREATE SCHEMA reference;
CREATE TABLE reference.flyway_schema_history (installed_rank integer PRIMARY KEY, success boolean NOT NULL);
CREATE TABLE reference.parcel_coordinate_snapshot (pnu text PRIMARY KEY);
INSERT INTO reference.flyway_schema_history VALUES (1, true), (2, true), (3, true), (4, true);
INSERT INTO reference.parcel_coordinate_snapshot VALUES ('1111010100100010000'), ('1111010100100020000');
SQL

docker run --rm --network "${network}" \
  --volume "${repo_root}:/workspace:ro" \
  --volume "${backup_dir}:/backup" \
  --env HOME_BACKUP_PGHOST=source-postgres \
  --env HOME_BACKUP_PGPORT=5432 \
  --env HOME_BACKUP_PGUSER=postgres \
  --env HOME_BACKUP_PGPASSWORD="${password}" \
  --env HOME_BACKUP_LOGICAL_DATABASES=property,admin,user,ai,coordinate \
  --env HOME_BACKUP_TIMESTAMP=20260716T020304Z \
  "${image}" bash /workspace/infra/backup/home-search-db-backup.sh --backup-all /backup

for logical in property admin user ai coordinate; do
  docker run --rm --user postgres \
    --volume "${repo_root}:/workspace:ro" \
    --volume "${backup_dir}:/backup:ro" \
    "${image}" bash /workspace/infra/backup/home-search-db-backup.sh \
      --verify-restore "/backup/${logical}-20260716T020304Z.manifest.tsv"
done

property_count="$(docker exec "${source_container}" psql -X -At -U postgres -d home_search -c 'SELECT count(*) FROM public.raw_trade_ingest')"
admin_count="$(docker exec "${source_container}" psql -X -At -U postgres -d home_search_admin -c 'SELECT count(*) FROM admin.admin_account')"
user_count="$(docker exec "${source_container}" psql -X -At -U postgres -d home_search_user -c 'SELECT count(*) FROM users.user_account')"
ai_count="$(docker exec "${source_container}" psql -X -At -U postgres -d home_search_ai -c 'SELECT count(*) FROM public.dataset_source')"
coordinate_count="$(docker exec "${source_container}" psql -X -At -U postgres -d home_search_coordinate_source -c 'SELECT count(*) FROM reference.parcel_coordinate_snapshot')"
[[ "${property_count}|${admin_count}|${user_count}|${ai_count}|${coordinate_count}" == '2|1|3|2|2' ]]

docker run --rm \
  --volume "${backup_dir}:/backup" \
  "${image}" sh -c 'printf "tamper\n" >> "$1"' sh \
  /backup/property-20260716T020304Z.dump
if docker run --rm --user postgres \
    --volume "${repo_root}:/workspace:ro" \
    --volume "${backup_dir}:/backup:ro" \
    "${image}" bash /workspace/infra/backup/home-search-db-backup.sh \
      --verify-restore /backup/property-20260716T020304Z.manifest.tsv >/dev/null 2>&1; then
  echo '상태: Fail - tampered dump checksum 검증이 성공했습니다.' >&2
  exit 1
fi

echo '상태: Pass - 실제 PostgreSQL custom backup, ephemeral restore, row-count, checksum failure를 확인했습니다.'

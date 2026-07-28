#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
suffix="${RANDOM}$$"
image="${HOME_MIGRATION_TEST_POSTGRES_IMAGE:-postgres:16-alpine}"
network="home-search-migration-test-${suffix}"
container="home-search-migration-source-${suffix}"
property_target="migration_property_${suffix}"
reference_target="migration_reference_${suffix}"
test_dir="$(mktemp -d)"
fake_bin="${test_dir}/bin"
catalog="${test_dir}/catalog.json"
export_dir="${test_dir}/export"
final_export_dir="${test_dir}/final-export"

cleanup() {
  docker exec "${container}" psql -X -qAt -U home_search -d postgres \
    -c "DROP DATABASE IF EXISTS \"${property_target}\" WITH (FORCE)" >/dev/null 2>&1 || true
  docker exec "${container}" psql -X -qAt -U home_search -d postgres \
    -c "DROP DATABASE IF EXISTS \"${reference_target}\" WITH (FORCE)" >/dev/null 2>&1 || true
  docker stop --time 1 "${container}" >/dev/null 2>&1 || true
  docker network remove "${network}" >/dev/null 2>&1 || true
  find "${test_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

docker network create "${network}" >/dev/null
docker run --rm --detach --name "${container}" --network "${network}" \
  --env POSTGRES_DB=home_search --env POSTGRES_USER=home_search --env POSTGRES_PASSWORD=migration-test-only \
  "${image}" >/dev/null
ready=false
for _ in $(seq 1 60); do
  readiness_count="$(docker logs "${container}" 2>&1 | grep -c 'database system is ready to accept connections' || true)"
  if (( readiness_count >= 2 )) && \
      docker exec "${container}" psql -X -qAt -U home_search -d home_search -c 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 0.5
done
[[ "${ready}" == 'true' ]] || { echo '상태: Fail - migration source PostgreSQL이 준비되지 않았습니다.' >&2; exit 1; }

docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d postgres \
  -c 'CREATE DATABASE home_search_ai'
docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d home_search <<'SQL'
CREATE TABLE public.region (
  id bigint PRIMARY KEY, parent_id bigint, code varchar(20), name varchar(255), region_type varchar(50),
  center_lat double precision, center_lng double precision, created_at timestamptz, updated_at timestamptz,
  unit_cnt_sum bigint
);
INSERT INTO public.region VALUES
  (1, NULL, '11', '서울특별시', 'sido', 37.56, 126.97, now(), now(), 1000),
  (2, 1, '11110', '종로구', 'si-gun-gu', 37.57, 126.98, now(), now(), 500);
SQL
docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d home_search_ai <<'SQL'
CREATE TABLE public.dataset_source (source_id text PRIMARY KEY, provider text NOT NULL, created_at timestamptz NOT NULL);
INSERT INTO public.dataset_source VALUES ('academy', 'public-data', now()), ('rail', 'public-data', now());
SQL
mkdir -p "${fake_bin}" "${export_dir}"

cat >"${fake_bin}/psql" <<'WRAPPER'
#!/usr/bin/env bash
set -Eeuo pipefail
arguments=()
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    -h|-p) shift 2 ;;
    *) arguments+=("$1"); shift ;;
  esac
done
exec docker exec -i "${HOME_MIGRATION_TEST_POSTGRES_CONTAINER}" psql "${arguments[@]}"
WRAPPER
chmod +x "${fake_bin}/psql"

python3 - "${script_dir}/data-only-allowlist.json" "${catalog}" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
wanted = {"property:public.region", "reference:public.dataset_source"}
source["datasets"] = [
    item for item in source["datasets"]
    if f"{item['logicalDatabase']}:{item['schema']}.{item['table']}" in wanted
]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d postgres \
  -c "CREATE DATABASE \"${property_target}\"" \
  -c "CREATE DATABASE \"${reference_target}\""
docker exec "${container}" pg_dump -U home_search -d home_search --schema-only --section=pre-data --no-owner --no-acl \
  --table public.region | docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${property_target}"
docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${property_target}" \
  -c 'ALTER TABLE public.region ADD PRIMARY KEY (id)'
docker exec "${container}" pg_dump -U home_search -d home_search_ai --schema-only --section=pre-data --no-owner --no-acl \
  --table public.dataset_source | docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${reference_target}"
docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${reference_target}" \
  -c 'ALTER TABLE public.dataset_source ADD PRIMARY KEY (source_id)'

export HOME_MIGRATION_TEST_POSTGRES_CONTAINER="${container}"
export HOME_MIGRATION_PROPERTY_SOURCE_HOST=unused
export HOME_MIGRATION_PROPERTY_SOURCE_DATABASE=home_search
export HOME_MIGRATION_PROPERTY_SOURCE_USER=home_search
export HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD=unused
export HOME_MIGRATION_REFERENCE_SOURCE_HOST=unused
export HOME_MIGRATION_REFERENCE_SOURCE_DATABASE=home_search_ai
export HOME_MIGRATION_REFERENCE_SOURCE_USER=home_search
export HOME_MIGRATION_REFERENCE_SOURCE_PASSWORD=unused
export HOME_MIGRATION_PROPERTY_TARGET_HOST=unused
export HOME_MIGRATION_PROPERTY_TARGET_DATABASE="${property_target}"
export HOME_MIGRATION_PROPERTY_TARGET_USER=home_search
export HOME_MIGRATION_PROPERTY_TARGET_PASSWORD=unused
export HOME_MIGRATION_REFERENCE_TARGET_HOST=unused
export HOME_MIGRATION_REFERENCE_TARGET_DATABASE="${reference_target}"
export HOME_MIGRATION_REFERENCE_TARGET_USER=home_search
export HOME_MIGRATION_REFERENCE_TARGET_PASSWORD=unused

PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" export --output "${export_dir}"
manifest="${export_dir}/data-only-manifest.json"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${manifest}"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${manifest}"
docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d home_search \
  -c "UPDATE public.region SET unit_cnt_sum=777, updated_at=now() WHERE id=2"
mkdir -p "${final_export_dir}"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" export --output "${final_export_dir}"
final_manifest="${final_export_dir}/data-only-manifest.json"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${final_manifest}"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${final_manifest}"
PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" reconcile \
  --manifest "${final_manifest}" --report "${test_dir}/reconciliation.json"

property_source_count="$(docker exec "${container}" psql -X -qAt -U home_search -d home_search -c 'SELECT count(*) FROM public.region')"
property_target_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${property_target}" -c 'SELECT count(*) FROM public.region')"
reference_source_count="$(docker exec "${container}" psql -X -qAt -U home_search -d home_search_ai -c 'SELECT count(*) FROM public.dataset_source')"
reference_target_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${reference_target}" -c 'SELECT count(*) FROM public.dataset_source')"
[[ "${property_source_count}|${reference_source_count}" == "${property_target_count}|${reference_target_count}" ]]
[[ "$(docker exec "${container}" psql -X -qAt -U home_search -d "${property_target}" -c 'SELECT unit_cnt_sum FROM public.region WHERE id=2')" == '777' ]]
grep -Fq '"status":"pass"' "${test_dir}/reconciliation.json"

echo '상태: Pass - repeatable snapshot export, zstd/checksum, resume, final correction upsert, reconciliation을 확인했습니다.'

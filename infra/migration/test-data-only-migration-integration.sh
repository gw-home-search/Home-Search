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
real_zstd="$(command -v zstd)"
catalog="${test_dir}/catalog.json"
export_dir="${test_dir}/export"
final_export_dir="${test_dir}/final-export"
raw_source_file="${test_dir}/raw-source.zip"
raw_target_file="${test_dir}/raw-target.zip"
interrupted_import_pid=""

cleanup() {
  if [[ -n "${interrupted_import_pid}" ]]; then
    kill "${interrupted_import_pid}" >/dev/null 2>&1 || true
    wait "${interrupted_import_pid}" 2>/dev/null || true
  fi
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
printf 'immutable-reference-raw-fixture\n' >"${raw_source_file}"
raw_checksum="$(shasum -a 256 "${raw_source_file}" | awk '{print $1}')"
raw_length="$(wc -c <"${raw_source_file}" | tr -d ' ')"
docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d home_search_ai \
  -v raw_checksum="${raw_checksum}" -v raw_length="${raw_length}" <<'SQL'
CREATE TABLE public.dataset_raw_object (
  checksum char(64) PRIMARY KEY, content bytea, byte_length bigint NOT NULL, collected_at timestamptz NOT NULL,
  storage_backend text NOT NULL, object_key text, object_version_id text, content_type text,
  checksum_algorithm text NOT NULL
);
INSERT INTO public.dataset_raw_object VALUES (
  :'raw_checksum', NULL, :raw_length, now(), 'S3',
  'raw/v1/academy/fixture.zip', 'source-v1', 'application/zip', 'SHA256'
);
SQL

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

cat >"${fake_bin}/aws" <<'PY'
#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

args = sys.argv[1:]
def option(name):
    return args[args.index(name) + 1]

operation = args[args.index("s3api") + 1]
bucket = option("--bucket")
source = Path(os.environ["HOME_MIGRATION_TEST_RAW_SOURCE_FILE"])
target = Path(os.environ["HOME_MIGRATION_TEST_RAW_TARGET_FILE"])
if operation == "get-object":
    if bucket != "source-raw" or option("--version-id") != "source-v1":
        raise SystemExit(2)
    shutil.copyfile(source, Path(args[-1]))
    print(json.dumps({"VersionId": "source-v1"}))
elif operation == "put-object":
    if bucket != "target-raw":
        raise SystemExit(2)
    shutil.copyfile(Path(option("--body")), target)
    print(json.dumps({"VersionId": "target-v1"}))
elif operation == "head-object":
    if bucket != "target-raw" or not target.exists():
        print("not found", file=sys.stderr)
        raise SystemExit(254)
    content = target.read_bytes()
    print(json.dumps({
        "VersionId": "target-v1",
        "ContentLength": len(content),
        "ContentType": "application/zip",
        "ChecksumSHA256": base64.b64encode(hashlib.sha256(content).digest()).decode("ascii"),
    }))
else:
    raise SystemExit(2)
PY
chmod +x "${fake_bin}/aws"

cat >"${fake_bin}/zstd" <<'WRAPPER'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ " $* " == *' -dc '* ]]; then
  sleep "${HOME_MIGRATION_TEST_ZSTD_DELAY_SECONDS:-0}"
fi
exec "${HOME_MIGRATION_TEST_REAL_ZSTD}" "$@"
WRAPPER
chmod +x "${fake_bin}/zstd"

python3 - "${script_dir}/data-only-allowlist.json" "${catalog}" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
wanted = {
    "property:public.region",
    "reference:public.dataset_source",
    "reference:public.dataset_raw_object",
}
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
  --table public.dataset_source --table public.dataset_raw_object | docker exec -i "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${reference_target}"
docker exec "${container}" psql -X -qAt -v ON_ERROR_STOP=1 -U home_search -d "${reference_target}" \
  -c 'ALTER TABLE public.dataset_source ADD PRIMARY KEY (source_id)' \
  -c 'ALTER TABLE public.dataset_raw_object ADD PRIMARY KEY (checksum)'

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
export HOME_MIGRATION_RAW_SOURCE_BUCKET=source-raw
export HOME_MIGRATION_RAW_SOURCE_REGION=ap-northeast-2
export HOME_MIGRATION_RAW_SOURCE_ENDPOINT=http://127.0.0.1:9000
export HOME_MIGRATION_RAW_TARGET_BUCKET=target-raw
export HOME_MIGRATION_RAW_TARGET_REGION=ap-northeast-2
export HOME_MIGRATION_RAW_TARGET_ENDPOINT=http://127.0.0.1:9000
export HOME_MIGRATION_TEST_RAW_SOURCE_FILE="${raw_source_file}"
export HOME_MIGRATION_TEST_RAW_TARGET_FILE="${raw_target_file}"
export HOME_MIGRATION_TEST_REAL_ZSTD="${real_zstd}"

PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" export --output "${export_dir}"
manifest="${export_dir}/data-only-manifest.json"
HOME_MIGRATION_TEST_ZSTD_DELAY_SECONDS=2 PATH="${fake_bin}:${PATH}" \
  python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${manifest}" \
  >"${test_dir}/interrupted-import.log" 2>&1 &
interrupted_import_pid=$!
interrupted=false
for _ in $(seq 1 100); do
  property_progress_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${property_target}" -c 'SELECT count(*) FROM home_migration.import_progress' 2>/dev/null || true)"
  reference_progress_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${reference_target}" -c 'SELECT count(*) FROM home_migration.import_progress' 2>/dev/null || true)"
  total_progress_count=$((${property_progress_count:-0} + ${reference_progress_count:-0}))
  if [[ "${total_progress_count}" == '1' ]]; then
    kill "${interrupted_import_pid}"
    wait "${interrupted_import_pid}" 2>/dev/null || true
    interrupted_import_pid=""
    interrupted=true
    break
  fi
  sleep 0.1
done
[[ "${interrupted}" == 'true' ]] || { cat "${test_dir}/interrupted-import.log" >&2; exit 1; }

resume_output="$(PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${manifest}")"
[[ "$(grep -c '상태: resume - durable chunk checkpoint' <<<"${resume_output}")" == '1' ]]
property_progress_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${property_target}" -c 'SELECT count(*) FROM home_migration.import_progress')"
reference_progress_count="$(docker exec "${container}" psql -X -qAt -U home_search -d "${reference_target}" -c 'SELECT count(*) FROM home_migration.import_progress')"
[[ "${property_progress_count}|${reference_progress_count}" == '1|2' ]]
second_resume_output="$(PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" import --manifest "${manifest}")"
[[ "$(grep -c '상태: resume - durable chunk checkpoint' <<<"${second_resume_output}")" == '3' ]]
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
[[ "$(docker exec "${container}" psql -X -qAt -U home_search -d "${reference_target}" -c 'SELECT object_version_id FROM public.dataset_raw_object')" == 'target-v1' ]]
[[ "$(shasum -a 256 "${raw_target_file}" | awk '{print $1}')" == "${raw_checksum}" ]]
grep -Fq '"status":"pass"' "${test_dir}/reconciliation.json"
printf 'tampered-target-raw\n' >"${raw_target_file}"
if PATH="${fake_bin}:${PATH}" python3 "${script_dir}/data_only_migration.py" --catalog "${catalog}" reconcile \
  --manifest "${final_manifest}" --report "${test_dir}/tampered-reconciliation.json" >/dev/null 2>&1; then
  echo '상태: Fail - target raw object checksum tampering was accepted.' >&2
  exit 1
fi

echo '상태: Pass - snapshot export, raw S3 version remap, resume, correction upsert, reconciliation을 확인했습니다.'

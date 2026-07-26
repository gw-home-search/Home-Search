#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Usage: $0 archive|verify|restore-verify|cleanup"
  echo "Required: PROFILE_COLLECTION_ID PROFILE_PARSE_RUN_ID PROFILE_ANALYSIS_RUN_ID PROFILE_PROJECTION_RUN_ID PROFILE_ARCHIVE_ID PROFILE_ARCHIVE_DIRECTORY"
}

fail() {
  echo "차단 사유: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || { usage; exit 2; }
action="$1"
case "$action" in archive|verify|restore-verify|cleanup) ;; *) usage; exit 2 ;; esac

source_container="${PROPERTY_DB_CONTAINER:-home-search-postgis}"
verify_container="${PROFILE_VERIFY_DB_CONTAINER:-home-search-profile-analysis-postgis-arm64}"
docker_network="${PROFILE_ARCHIVE_DOCKER_NETWORK:-home-search-local_home-search-local}"
db_name="${PROPERTY_DB_NAME:-home_search}"
db_user="${PROPERTY_DB_USER:-home_search}"
verify_db="home_search_profile_archive_verify"

collection_id="${PROFILE_COLLECTION_ID:-}"
parse_run_id="${PROFILE_PARSE_RUN_ID:-}"
analysis_run_id="${PROFILE_ANALYSIS_RUN_ID:-}"
projection_run_id="${PROFILE_PROJECTION_RUN_ID:-}"
archive_id="${PROFILE_ARCHIVE_ID:-}"
archive_directory="${PROFILE_ARCHIVE_DIRECTORY:-}"

uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
for value in "$collection_id" "$parse_run_id" "$analysis_run_id" "$projection_run_id" "$archive_id"; do
  [[ "$value" =~ $uuid_pattern ]] || fail "모든 run/archive id는 canonical lowercase UUID여야 합니다"
done
[[ "$archive_directory" = /* ]] || fail "PROFILE_ARCHIVE_DIRECTORY는 절대 경로여야 합니다"

archive_path="${archive_directory}/building-profile-${archive_id}.dump"

psql_source() {
  docker exec -i "$source_container" psql -X -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" "$@"
}

manifest_value() {
  local column="$1"
  psql_source -At -c \
    "SELECT ${column} FROM building_register_profile_archive_manifest WHERE archive_id='${archive_id}'::uuid"
}

verify_file() {
  [[ -f "$archive_path" ]] || fail "archive 파일이 없습니다: ${archive_path}"
  local expected_sha expected_bytes actual_sha actual_bytes
  expected_sha="$(manifest_value archive_sha256)"
  expected_bytes="$(manifest_value archive_byte_count)"
  [[ -n "$expected_sha" && -n "$expected_bytes" ]] || fail "archive manifest가 없습니다"
  actual_sha="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
  actual_bytes="$(stat -f %z "$archive_path")"
  [[ "$actual_sha" = "$expected_sha" ]] || fail "archive SHA-256 불일치"
  [[ "$actual_bytes" = "$expected_bytes" ]] || fail "archive byte count 불일치"
}

if [[ "$action" = "archive" ]]; then
  status="$(psql_source -At -c \
    "SELECT status FROM building_register_profile_projection_run WHERE projection_run_id='${projection_run_id}'::uuid")"
  [[ "$status" = "COMPLETED" ]] || fail "COMPLETED projection run이 필요합니다"
  mkdir -p "$archive_directory"
  if [[ -e "$archive_path" ]]; then
    [[ "$(manifest_value archive_id)" = "" ]] || fail "archive manifest가 이미 존재합니다"
    docker exec -i "$verify_container" pg_restore --list < "$archive_path" >/dev/null \
      || fail "기존 archive 파일의 custom-format TOC를 읽을 수 없습니다"
  else
    : "${PROPERTY_DB_PASSWORD:?PROPERTY_DB_PASSWORD is required to create an archive}"
    archive_name="$(basename "$archive_path")"
    temporary_name="${archive_name}.tmp"
    docker run --rm --network "$docker_network" --user "$(id -u):$(id -g)" \
      -e PGPASSWORD="$PROPERTY_DB_PASSWORD" -v "${archive_directory}:/archive" postgres:16 \
      pg_dump -h "$source_container" -U "$db_user" -d "$db_name" --format=custom \
        --compress=6 --no-owner --no-privileges -f "/archive/${temporary_name}"
    mv "${archive_directory}/${temporary_name}" "$archive_path"
  fi
  archive_sha="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
  archive_bytes="$(stat -f %z "$archive_path")"
  database_bytes="$(psql_source -At -c "SELECT pg_database_size(current_database())")"
  psql_source -v archive="$archive_id" -v collection="$collection_id" -v parse="$parse_run_id" \
    -v analysis="$analysis_run_id" -v projection="$projection_run_id" -v uri="$archive_path" \
    -v sha="$archive_sha" -v bytes="$archive_bytes" -v database_bytes="$database_bytes" \
    -v row_counts='{}' <<'SQL'
INSERT INTO building_register_profile_archive_manifest
  (archive_id,collection_id,parse_run_id,analysis_run_id,projection_run_id,archive_uri,
   archive_sha256,archive_byte_count,source_database_size_bytes,row_counts,status,verified_at)
VALUES (:'archive'::uuid,:'collection'::uuid,:'parse'::uuid,:'analysis'::uuid,:'projection'::uuid,
        :'uri',:'sha',:'bytes'::bigint,:'database_bytes'::bigint,:'row_counts'::jsonb,'VERIFIED',now())
ON CONFLICT (archive_id) DO NOTHING;
SQL
  verify_file
  echo "ARCHIVE_PATH=${archive_path}"
  echo "ARCHIVE_SHA256=${archive_sha}"
  echo "ARCHIVE_BYTES=${archive_bytes}"
  exit 0
fi

verify_file

if [[ "$action" = "verify" ]]; then
  echo "archive SHA-256과 byte count 검증 완료"
  exit 0
fi

if [[ "$action" = "restore-verify" ]]; then
  docker exec "$verify_container" dropdb -U "$db_user" --if-exists "$verify_db"
  docker exec "$verify_container" createdb -U "$db_user" "$verify_db"
  if ! docker exec -i "$verify_container" pg_restore -U "$db_user" -d "$verify_db" \
      --exit-on-error --no-owner --no-privileges < "$archive_path"; then
    docker exec "$verify_container" dropdb -U "$db_user" --if-exists "$verify_db"
    fail "ARM archive restore가 실패했습니다"
  fi
  restored_rows="$(docker exec -i "$verify_container" psql -X -At -v ON_ERROR_STOP=1 \
    -U "$db_user" -d "$verify_db" -v projection="$projection_run_id" <<'SQL'
SELECT jsonb_build_object(
  'building_register_collection_campaign',(SELECT count(*) FROM building_register_collection_campaign),
  'building_register_collection_target',(SELECT count(*) FROM building_register_collection_target),
  'building_register_endpoint_snapshot',(SELECT count(*) FROM building_register_endpoint_snapshot),
  'building_register_raw_page',(SELECT count(*) FROM building_register_raw_page),
  'building_register_profile_sample_stratum',(SELECT count(*) FROM building_register_profile_sample_stratum),
  'building_register_profile_sample_pnu',(SELECT count(*) FROM building_register_profile_sample_pnu),
  'building_register_profile_parse_run',(SELECT count(*) FROM building_register_profile_parse_run),
  'building_register_profile_parse_page',(SELECT count(*) FROM building_register_profile_parse_page),
  'building_register_profile_record',(SELECT count(*) FROM building_register_profile_record),
  'building_register_profile_value',(SELECT count(*) FROM building_register_profile_value),
  'building_register_profile_schema_observation',(SELECT count(*) FROM building_register_profile_schema_observation),
  'building_register_profile_hierarchy_reason',(SELECT count(*) FROM building_register_profile_hierarchy_reason),
  'building_register_profile_scope_assignment',(SELECT count(*) FROM building_register_profile_scope_assignment),
  'building_register_profile_complex_match',(SELECT count(*) FROM building_register_profile_complex_match),
  'building_register_profile_code_lookup',(SELECT count(*) FROM building_register_profile_code_lookup),
  'building_register_profile_analysis_run',(SELECT count(*) FROM building_register_profile_analysis_run),
  'building_register_profile_comparison',(SELECT count(*) FROM building_register_profile_comparison),
  'building_register_profile_field_quality',(SELECT count(*) FROM building_register_profile_field_quality),
  'building_register_profile_projection_run',(SELECT count(*) FROM building_register_profile_projection_run),
  'complex_building_register_profile',(SELECT count(*) FROM complex_building_register_profile WHERE projection_run_id=:'projection'::uuid),
  'complex_building_register_building',(SELECT count(*) FROM complex_building_register_building WHERE projection_run_id=:'projection'::uuid),
  'building_register_profile_projected_quality',(SELECT count(*) FROM building_register_profile_projected_quality WHERE projection_run_id=:'projection'::uuid),
  'complex',(SELECT count(*) FROM complex)
)::text;
SQL
)"
  restored_consistent="$(docker exec -i "$verify_container" psql -X -At -v ON_ERROR_STOP=1 \
    -U "$db_user" -d "$verify_db" -v projection="$projection_run_id" <<'SQL'
SELECT CASE WHEN
  (SELECT count(*) FROM complex_building_register_profile WHERE projection_run_id=:'projection'::uuid)
    = (SELECT complex_count FROM building_register_profile_projection_run WHERE projection_run_id=:'projection'::uuid)
  AND (SELECT count(*) FROM complex_building_register_profile WHERE projection_run_id=:'projection'::uuid AND projectable)
    = (SELECT projectable_complex_count FROM building_register_profile_projection_run WHERE projection_run_id=:'projection'::uuid)
  AND (SELECT count(*) FROM complex_building_register_building WHERE projection_run_id=:'projection'::uuid)
    = (SELECT building_count FROM building_register_profile_projection_run WHERE projection_run_id=:'projection'::uuid)
  AND (SELECT count(*) FROM building_register_profile_projected_quality WHERE projection_run_id=:'projection'::uuid)
    = (SELECT eligible_field_count FROM building_register_profile_projection_run WHERE projection_run_id=:'projection'::uuid)
THEN 'yes' ELSE 'no' END;
SQL
)"
  docker exec "$verify_container" dropdb -U "$db_user" "$verify_db"
  [[ "$restored_consistent" = "yes" ]] || fail "ARM restore의 projection 행 수가 run manifest와 다릅니다"
  psql_source -v rows="$restored_rows" <<SQL
UPDATE building_register_profile_archive_manifest
SET status='RESTORE_VERIFIED',row_counts=:'rows'::jsonb,restore_verified_at=now()
WHERE archive_id='${archive_id}'::uuid AND status='VERIFIED';
SQL
  echo "ARM archive 복원 및 행 수 검증 완료"
  exit 0
fi

manifest_status="$(manifest_value status)"
[[ "$manifest_status" = "RESTORE_VERIFIED" ]] || fail "cleanup에는 RESTORE_VERIFIED manifest가 필요합니다"
exclusive="$(psql_source -At -v collection="$collection_id" -v parse="$parse_run_id" -v analysis="$analysis_run_id" <<'SQL'
SELECT CASE WHEN
  EXISTS (
    SELECT 1 FROM building_register_collection_campaign
    WHERE collection_id=:'collection'::uuid AND purpose='PROFILE_DISCOVERY' AND status='COMPLETED')
  AND EXISTS (
    SELECT 1 FROM building_register_profile_parse_run
    WHERE parse_run_id=:'parse'::uuid AND status='COMPLETED')
  AND EXISTS (
    SELECT 1 FROM building_register_profile_analysis_run
    WHERE analysis_run_id=:'analysis'::uuid AND status='COMPLETED')
  AND NOT EXISTS (
    SELECT 1 FROM building_register_collection_campaign
    WHERE purpose='PROFILE_DISCOVERY' AND status<>'COMPLETED')
  AND NOT EXISTS (SELECT 1 FROM building_register_profile_parse_run WHERE status='RUNNING')
  AND NOT EXISTS (SELECT 1 FROM building_register_profile_analysis_run WHERE status='RUNNING')
THEN 'yes' ELSE 'no' END;
SQL
)"
[[ "$exclusive" = "yes" ]] || fail "profile campaign/run이 terminal 상태가 아니어서 staging을 정리할 수 없습니다"

psql_source -v collection="$collection_id" <<'SQL'
BEGIN;
TRUNCATE TABLE
  building_register_profile_value,
  building_register_profile_comparison,
  building_register_profile_scope_assignment,
  building_register_profile_schema_observation,
  building_register_profile_record,
  building_register_profile_parse_page,
  building_register_profile_complex_match,
  building_register_profile_code_lookup,
  building_register_profile_hierarchy_reason
RESTART IDENTITY;
UPDATE building_register_raw_page raw
SET response_body=NULL
FROM building_register_endpoint_snapshot snapshot
JOIN building_register_collection_campaign campaign ON campaign.collection_id=snapshot.collection_id
WHERE snapshot.id=raw.endpoint_snapshot_id
  AND campaign.purpose='PROFILE_DISCOVERY'
  AND raw.response_body IS NOT NULL;
COMMIT;
SQL
psql_source -c "VACUUM (FULL, ANALYZE) building_register_raw_page"
psql_source -c "VACUUM (ANALYZE) complex_building_register_profile, complex_building_register_building"
psql_source -c \
  "UPDATE building_register_profile_archive_manifest SET status='CLEANED',cleaned_at=now() WHERE archive_id='${archive_id}'::uuid AND status='RESTORE_VERIFIED'"
echo "profile staging 상세와 raw body 정리 및 공간 회수 완료"

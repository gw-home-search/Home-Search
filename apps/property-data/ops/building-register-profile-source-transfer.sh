#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Usage: $0 export|verify|import"
  echo "Required: PROFILE_COLLECTION_ID PROFILE_PARSE_RUN_ID PROFILE_ANALYSIS_RUN_ID PROFILE_SOURCE_TRANSFER_DIRECTORY"
}

fail() {
  echo "차단 사유: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || { usage; exit 2; }
action="$1"
case "$action" in export|verify|import) ;; *) usage; exit 2 ;; esac

collection_id="${PROFILE_COLLECTION_ID:-}"
parse_run_id="${PROFILE_PARSE_RUN_ID:-}"
analysis_run_id="${PROFILE_ANALYSIS_RUN_ID:-}"
transfer_directory="${PROFILE_SOURCE_TRANSFER_DIRECTORY:-}"
uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
for value in "$collection_id" "$parse_run_id" "$analysis_run_id"; do
  [[ "$value" =~ $uuid_pattern ]] || fail "collection/parse/analysis id는 canonical lowercase UUID여야 합니다"
done
[[ "$transfer_directory" = /* ]] || fail "PROFILE_SOURCE_TRANSFER_DIRECTORY는 절대 경로여야 합니다"
[[ "$transfer_directory" =~ ^/[A-Za-z0-9._/-]+$ ]] || fail "transfer directory에는 영문, 숫자, ., _, -, /만 사용할 수 있습니다"

source_container="${PROFILE_SOURCE_DB_CONTAINER:-home-search-profile-analysis-postgis-arm64}"
target_container="${PROPERTY_DB_CONTAINER:-home-search-postgis}"
db_name="${PROPERTY_DB_NAME:-home_search}"
db_user="${PROPERTY_DB_USER:-home_search}"
bundle_directory="${transfer_directory}/building-profile-source-${analysis_run_id}"
manifest_path="${bundle_directory}/manifest.tsv"
manifest_sha_path="${bundle_directory}/manifest.sha256"

tables=(
  building_register_profile_parse_page
  building_register_profile_record
  building_register_profile_value
  building_register_profile_schema_observation
  building_register_profile_hierarchy_reason
  building_register_profile_scope_assignment
  building_register_profile_complex_match
)

psql_source() {
  docker exec -i "$source_container" psql -X -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" "$@"
}

psql_target() {
  docker exec -i "$target_container" psql -X -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" "$@"
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

file_bytes() {
  if stat -f %z "$1" >/dev/null 2>&1; then
    stat -f %z "$1"
  else
    stat -c %s "$1"
  fi
}

filter_for() {
  case "$1" in
    building_register_profile_parse_page) echo "parse_run_id='${parse_run_id}'::uuid" ;;
    building_register_profile_record) echo "parse_run_id='${parse_run_id}'::uuid" ;;
    building_register_profile_value) echo "profile_record_id IN (SELECT id FROM building_register_profile_record WHERE parse_run_id='${parse_run_id}'::uuid)" ;;
    building_register_profile_schema_observation) echo "parse_run_id='${parse_run_id}'::uuid" ;;
    building_register_profile_hierarchy_reason) echo "collection_id='${collection_id}'::uuid" ;;
    building_register_profile_scope_assignment) echo "analysis_run_id='${analysis_run_id}'::uuid" ;;
    building_register_profile_complex_match) echo "analysis_run_id='${analysis_run_id}'::uuid" ;;
    *) fail "허용되지 않은 source table: $1" ;;
  esac
}

order_for() {
  case "$1" in
    building_register_profile_parse_page) echo "parse_run_id,raw_page_id" ;;
    building_register_profile_record) echo "id" ;;
    building_register_profile_value) echo "profile_record_id,field_id" ;;
    building_register_profile_schema_observation) echo "id" ;;
    building_register_profile_hierarchy_reason) echo "collection_id,pnu,reason" ;;
    building_register_profile_scope_assignment) echo "analysis_run_id,profile_record_id" ;;
    building_register_profile_complex_match) echo "analysis_run_id,complex_id" ;;
  esac
}

verify_bundle() {
  [[ -f "$manifest_path" && -f "$manifest_sha_path" ]] || fail "manifest 파일이 없습니다"
  expected_manifest_sha="$(awk 'NF {print $1; exit}' "$manifest_sha_path")"
  [[ "$expected_manifest_sha" =~ ^[0-9a-f]{64}$ ]] || fail "manifest SHA-256 형식이 잘못되었습니다"
  [[ "$(sha256_file "$manifest_path")" = "$expected_manifest_sha" ]] || fail "manifest SHA-256 불일치"
  seen=0
  while IFS=$'\t' read -r table file rows bytes sha; do
    [[ "$table" = "table" ]] && continue
    [[ " ${tables[*]} " = *" ${table} "* ]] || fail "허용되지 않은 manifest table: ${table}"
    [[ "$file" = "${table}.csv" ]] || fail "manifest file 이름이 table과 다릅니다"
    [[ "$rows" =~ ^[0-9]+$ && "$bytes" =~ ^[0-9]+$ && "$sha" =~ ^[0-9a-f]{64}$ ]] \
      || fail "manifest row 형식이 잘못되었습니다: ${table}"
    path="${bundle_directory}/${file}"
    [[ -f "$path" ]] || fail "transfer 파일이 없습니다: ${file}"
    [[ "$(file_bytes "$path")" = "$bytes" ]] || fail "byte count 불일치: ${file}"
    [[ "$(sha256_file "$path")" = "$sha" ]] || fail "SHA-256 불일치: ${file}"
    seen=$((seen + 1))
  done < "$manifest_path"
  [[ "$seen" -eq "${#tables[@]}" ]] || fail "manifest table 수가 ${#tables[@]}개가 아닙니다"
  for expected_table in "${tables[@]}"; do
    occurrences="$(awk -F $'\t' -v expected="$expected_table" '$1==expected {count++} END {print count+0}' "$manifest_path")"
    [[ "$occurrences" -eq 1 ]] || fail "manifest table은 정확히 한 번만 있어야 합니다: ${expected_table}"
  done
}

if [[ "$action" = "export" ]]; then
  source_ready="$(psql_source -At -v collection="$collection_id" -v parse="$parse_run_id" \
    -v analysis="$analysis_run_id" <<'SQL'
SELECT CASE WHEN
  EXISTS (SELECT 1 FROM building_register_collection_campaign
          WHERE collection_id=:'collection'::uuid AND status='COMPLETED')
  AND EXISTS (SELECT 1 FROM building_register_profile_parse_run
             WHERE parse_run_id=:'parse'::uuid AND source_collection_id=:'collection'::uuid AND status='COMPLETED')
  AND EXISTS (SELECT 1 FROM building_register_profile_analysis_run
             WHERE analysis_run_id=:'analysis'::uuid AND collection_id=:'collection'::uuid
               AND parse_run_id=:'parse'::uuid AND status='COMPLETED')
THEN 'yes' ELSE 'no' END;
SQL
)"
  [[ "$source_ready" = "yes" ]] || fail "동일 lineage의 COMPLETED collection/parse/analysis가 필요합니다"
  mkdir -p "$transfer_directory"
  mkdir "$bundle_directory" || fail "기존 bundle을 덮어쓰지 않습니다: ${bundle_directory}"
  printf 'table\tfile\trows\tbytes\tsha256\n' > "$manifest_path"
  for table in "${tables[@]}"; do
    filter="$(filter_for "$table")"
    order="$(order_for "$table")"
    file="${table}.csv"
    path="${bundle_directory}/${file}"
    psql_source -c "COPY (SELECT * FROM ${table} WHERE ${filter} ORDER BY ${order}) TO STDOUT WITH (FORMAT csv,HEADER true)" > "$path"
    rows="$(psql_source -At -c "SELECT count(*) FROM ${table} WHERE ${filter}")"
    printf '%s\t%s\t%s\t%s\t%s\n' "$table" "$file" "$rows" "$(file_bytes "$path")" \
      "$(sha256_file "$path")" >> "$manifest_path"
  done
  printf '%s  manifest.tsv\n' "$(sha256_file "$manifest_path")" > "$manifest_sha_path"
  verify_bundle
  echo "SOURCE_TRANSFER_DIRECTORY=${bundle_directory}"
  echo "SOURCE_TRANSFER_MANIFEST_SHA256=$(sha256_file "$manifest_path")"
  exit 0
fi

verify_bundle
if [[ "$action" = "verify" ]]; then
  echo "profile source transfer SHA-256과 byte count 검증 완료"
  exit 0
fi

target_ready="$(psql_target -At -v collection="$collection_id" -v parse="$parse_run_id" \
  -v analysis="$analysis_run_id" <<'SQL'
SELECT CASE WHEN
  EXISTS (SELECT 1 FROM building_register_profile_parse_run
          WHERE parse_run_id=:'parse'::uuid AND source_collection_id=:'collection'::uuid AND status='COMPLETED')
  AND EXISTS (SELECT 1 FROM building_register_profile_analysis_run
             WHERE analysis_run_id=:'analysis'::uuid AND collection_id=:'collection'::uuid
               AND parse_run_id=:'parse'::uuid AND status='COMPLETED')
  AND EXISTS (SELECT 1 FROM building_register_profile_projection_run
             WHERE analysis_run_id=:'analysis'::uuid AND parse_run_id=:'parse'::uuid AND status='COMPLETED')
THEN 'yes' ELSE 'no' END;
SQL
)"
[[ "$target_ready" = "yes" ]] || fail "target에 동일 lineage의 COMPLETED parse/analysis/projection이 필요합니다"
for table in "${tables[@]}"; do
  bundle_header="$(sed -n '1p' "${bundle_directory}/${table}.csv")"
  target_header="$(psql_target -c \
    "COPY (SELECT * FROM ${table} LIMIT 0) TO STDOUT WITH (FORMAT csv,HEADER true)")"
  [[ "$bundle_header" = "$target_header" ]] || fail "source/target column 순서가 다릅니다: ${table}"
done

{
  echo "BEGIN;"
  for table in "${tables[@]}"; do
    echo "CREATE TEMP TABLE transfer_${table} (LIKE ${table} INCLUDING DEFAULTS) ON COMMIT DROP;"
    echo "COPY transfer_${table} FROM STDIN WITH (FORMAT csv,HEADER true);"
    sed -n '1,$p' "${bundle_directory}/${table}.csv"
    echo '\.'
  done
  cat <<SQL
DO \$transfer\$
BEGIN
  IF EXISTS (SELECT 1 FROM transfer_building_register_profile_parse_page
             WHERE parse_run_id<>'${parse_run_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_record
                WHERE parse_run_id<>'${parse_run_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_value value
                LEFT JOIN transfer_building_register_profile_record record ON record.id=value.profile_record_id
                WHERE record.id IS NULL)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_schema_observation
                WHERE parse_run_id<>'${parse_run_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_hierarchy_reason
                WHERE collection_id<>'${collection_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_scope_assignment
                WHERE analysis_run_id<>'${analysis_run_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_complex_match
                WHERE analysis_run_id<>'${analysis_run_id}'::uuid) THEN
    RAISE EXCEPTION 'source transfer contains rows outside the frozen lineage';
  END IF;
END
\$transfer\$;

INSERT INTO building_register_profile_parse_page SELECT * FROM transfer_building_register_profile_parse_page ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_record SELECT * FROM transfer_building_register_profile_record ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_value SELECT * FROM transfer_building_register_profile_value ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_schema_observation SELECT * FROM transfer_building_register_profile_schema_observation ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_hierarchy_reason SELECT * FROM transfer_building_register_profile_hierarchy_reason ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_scope_assignment SELECT * FROM transfer_building_register_profile_scope_assignment ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_complex_match SELECT * FROM transfer_building_register_profile_complex_match ON CONFLICT DO NOTHING;

DO \$transfer\$
BEGIN
  IF EXISTS (SELECT 1 FROM transfer_building_register_profile_parse_page source
             LEFT JOIN building_register_profile_parse_page target USING (parse_run_id,raw_page_id)
             WHERE target.raw_page_id IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_record source
                LEFT JOIN building_register_profile_record target USING (id)
                WHERE target.id IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_value source
                LEFT JOIN building_register_profile_value target USING (profile_record_id,field_id)
                WHERE target.profile_record_id IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_schema_observation source
                LEFT JOIN building_register_profile_schema_observation target USING (id)
                WHERE target.id IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_hierarchy_reason source
                LEFT JOIN building_register_profile_hierarchy_reason target USING (collection_id,pnu,reason)
                WHERE target.pnu IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_scope_assignment source
                LEFT JOIN building_register_profile_scope_assignment target USING (analysis_run_id,profile_record_id)
                WHERE target.profile_record_id IS NULL OR to_jsonb(target)<>to_jsonb(source))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_complex_match source
                LEFT JOIN building_register_profile_complex_match target USING (analysis_run_id,complex_id)
                WHERE target.complex_id IS NULL OR to_jsonb(target)<>to_jsonb(source)) THEN
    RAISE EXCEPTION 'target source staging differs from transfer';
  END IF;
  IF (SELECT count(*) FROM building_register_profile_parse_page WHERE parse_run_id='${parse_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_parse_page)
     OR (SELECT count(*) FROM building_register_profile_record WHERE parse_run_id='${parse_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_record)
     OR (SELECT count(*) FROM building_register_profile_value value JOIN building_register_profile_record record
         ON record.id=value.profile_record_id WHERE record.parse_run_id='${parse_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_value)
     OR (SELECT count(*) FROM building_register_profile_schema_observation
         WHERE parse_run_id='${parse_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_schema_observation)
     OR (SELECT count(*) FROM building_register_profile_hierarchy_reason
         WHERE collection_id='${collection_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_hierarchy_reason)
     OR (SELECT count(*) FROM building_register_profile_scope_assignment WHERE analysis_run_id='${analysis_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_scope_assignment)
     OR (SELECT count(*) FROM building_register_profile_complex_match WHERE analysis_run_id='${analysis_run_id}'::uuid)
       <> (SELECT count(*) FROM transfer_building_register_profile_complex_match) THEN
    RAISE EXCEPTION 'target source staging row counts differ from transfer';
  END IF;
END
\$transfer\$;

SELECT setval(pg_get_serial_sequence('building_register_profile_record','id'),
              greatest(1,(SELECT coalesce(max(id),1) FROM building_register_profile_record)),true);
SELECT setval(pg_get_serial_sequence('building_register_profile_schema_observation','id'),
              greatest(1,(SELECT coalesce(max(id),1) FROM building_register_profile_schema_observation)),true);
COMMIT;
SQL
} | psql_target

echo "profile source staging import 완료: raw body 변경 0건"

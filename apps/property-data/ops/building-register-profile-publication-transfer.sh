#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Usage: $0 export|verify|import"
  echo "Required: PROFILE_PUBLICATION_ID PROFILE_PUBLICATION_TRANSFER_DIRECTORY"
  echo "Optional: PROPERTY_DB_CONTAINER PROPERTY_DB_NAME PROPERTY_DB_USER"
}

fail() {
  echo "차단 사유: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || { usage; exit 2; }
action="$1"
case "$action" in export|verify|import) ;; *) usage; exit 2 ;; esac

publication_id="${PROFILE_PUBLICATION_ID:-}"
transfer_directory="${PROFILE_PUBLICATION_TRANSFER_DIRECTORY:-}"
uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
[[ "$publication_id" =~ $uuid_pattern ]] || fail "PROFILE_PUBLICATION_ID는 canonical lowercase UUID여야 합니다"
[[ "$transfer_directory" = /* ]] || fail "PROFILE_PUBLICATION_TRANSFER_DIRECTORY는 절대 경로여야 합니다"
[[ "$transfer_directory" =~ ^/[A-Za-z0-9._/-]+$ ]] || fail "transfer directory에는 영문, 숫자, ., _, -, /만 사용할 수 있습니다"

db_container="${PROPERTY_DB_CONTAINER:-home-search-postgis}"
db_name="${PROPERTY_DB_NAME:-home_search}"
db_user="${PROPERTY_DB_USER:-home_search}"
bundle_directory="${transfer_directory}/building-profile-publication-${publication_id}"
manifest_path="${bundle_directory}/manifest.tsv"
manifest_sha_path="${bundle_directory}/manifest.sha256"

tables=(
  building_register_profile_publication
  building_register_profile_site
  building_register_profile_building
  building_register_profile_hierarchy
  building_register_profile_field_evidence
  complex_building_register_profile_summary
)

psql_db() {
  docker exec -i "$db_container" psql -X -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" "$@"
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
  status="$(psql_db -At -v publication="$publication_id" -c \
    "SELECT status FROM building_register_profile_publication WHERE publication_id=:'publication'::uuid")"
  case "$status" in VALIDATED|PUBLISHED|SUPERSEDED) ;; *) fail "검증 완료 publication만 export할 수 있습니다" ;; esac
  mkdir -p "$transfer_directory"
  mkdir "$bundle_directory" || fail "기존 bundle을 덮어쓰지 않습니다: ${bundle_directory}"
  printf 'table\tfile\trows\tbytes\tsha256\n' > "$manifest_path"
  for table in "${tables[@]}"; do
    file="${table}.csv"
    path="${bundle_directory}/${file}"
    order_by="publication_id"
    case "$table" in
      building_register_profile_site) order_by="publication_id,pnu,root_management_key" ;;
      building_register_profile_building) order_by="publication_id,management_key" ;;
      building_register_profile_hierarchy) order_by="publication_id,pnu,source_record_key" ;;
      building_register_profile_field_evidence) order_by="publication_id,evidence_id" ;;
      complex_building_register_profile_summary) order_by="publication_id,complex_id" ;;
    esac
    psql_db -v publication="$publication_id" -c \
      "COPY (SELECT * FROM ${table} WHERE publication_id=:'publication'::uuid ORDER BY ${order_by}) TO STDOUT WITH (FORMAT csv,HEADER true)" \
      > "$path"
    rows="$(psql_db -At -v publication="$publication_id" -c \
      "SELECT count(*) FROM ${table} WHERE publication_id=:'publication'::uuid")"
    printf '%s\t%s\t%s\t%s\t%s\n' "$table" "$file" "$rows" "$(file_bytes "$path")" \
      "$(sha256_file "$path")" >> "$manifest_path"
  done
  printf '%s  manifest.tsv\n' "$(sha256_file "$manifest_path")" > "$manifest_sha_path"
  verify_bundle
  echo "PUBLICATION_TRANSFER_DIRECTORY=${bundle_directory}"
  echo "PUBLICATION_TRANSFER_MANIFEST_SHA256=$(sha256_file "$manifest_path")"
  exit 0
fi

verify_bundle
if [[ "$action" = "verify" ]]; then
  echo "publication transfer SHA-256과 byte count 검증 완료"
  exit 0
fi

for table in "${tables[@]}"; do
  bundle_header="$(sed -n '1p' "${bundle_directory}/${table}.csv")"
  target_header="$(psql_db -c \
    "COPY (SELECT * FROM ${table} LIMIT 0) TO STDOUT WITH (FORMAT csv,HEADER true)")"
  [[ "$bundle_header" = "$target_header" ]] || fail "publication source/target column 순서가 다릅니다: ${table}"
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
  IF (SELECT count(*) FROM transfer_building_register_profile_publication) <> 1
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_publication
                WHERE publication_id<>'${publication_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_site
                WHERE publication_id<>'${publication_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_building
                WHERE publication_id<>'${publication_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_hierarchy
                WHERE publication_id<>'${publication_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_field_evidence
                WHERE publication_id<>'${publication_id}'::uuid)
     OR EXISTS (SELECT 1 FROM transfer_complex_building_register_profile_summary
                WHERE publication_id<>'${publication_id}'::uuid) THEN
    RAISE EXCEPTION 'publication transfer contains a different publication_id';
  END IF;
END
\$transfer\$;

INSERT INTO building_register_profile_publication(
  publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,
  source_projection_run_id,rules_version,parser_version,status,expected_site_count,
  expected_building_count,expected_hierarchy_count,expected_evidence_count,expected_summary_count)
SELECT publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,
       source_projection_run_id,rules_version,parser_version,'PREPARING',expected_site_count,
       expected_building_count,expected_hierarchy_count,expected_evidence_count,expected_summary_count
FROM transfer_building_register_profile_publication
ON CONFLICT (publication_id) DO NOTHING;

DO \$transfer\$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM building_register_profile_publication target
    JOIN transfer_building_register_profile_publication source USING (publication_id)
    WHERE target.publication_id='${publication_id}'::uuid
      AND target.source_collection_id=source.source_collection_id
      AND target.source_parse_run_id=source.source_parse_run_id
      AND target.source_analysis_run_id=source.source_analysis_run_id
      AND target.source_projection_run_id=source.source_projection_run_id
      AND target.rules_version=source.rules_version
      AND target.parser_version=source.parser_version
      AND target.expected_site_count=source.expected_site_count
      AND target.expected_building_count=source.expected_building_count
      AND target.expected_hierarchy_count=source.expected_hierarchy_count
      AND target.expected_evidence_count=source.expected_evidence_count
      AND target.expected_summary_count=source.expected_summary_count) THEN
    RAISE EXCEPTION 'existing publication inputs differ from transfer';
  END IF;
END
\$transfer\$;

INSERT INTO building_register_profile_site SELECT * FROM transfer_building_register_profile_site ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_building SELECT * FROM transfer_building_register_profile_building ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_hierarchy SELECT * FROM transfer_building_register_profile_hierarchy ON CONFLICT DO NOTHING;
INSERT INTO building_register_profile_field_evidence SELECT * FROM transfer_building_register_profile_field_evidence ON CONFLICT DO NOTHING;
INSERT INTO complex_building_register_profile_summary SELECT * FROM transfer_complex_building_register_profile_summary ON CONFLICT DO NOTHING;

DO \$transfer\$
DECLARE source building_register_profile_publication%ROWTYPE;
DECLARE target building_register_profile_publication%ROWTYPE;
BEGIN
  SELECT * INTO source FROM transfer_building_register_profile_publication;
  SELECT * INTO target FROM building_register_profile_publication WHERE publication_id=source.publication_id FOR UPDATE;
  IF EXISTS (SELECT 1 FROM transfer_building_register_profile_site source_row
             LEFT JOIN building_register_profile_site target_row
               USING (publication_id,pnu,root_management_key)
             WHERE target_row.publication_id IS NULL OR to_jsonb(target_row)<>to_jsonb(source_row))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_building source_row
                LEFT JOIN building_register_profile_building target_row
                  USING (publication_id,management_key)
                WHERE target_row.publication_id IS NULL OR to_jsonb(target_row)<>to_jsonb(source_row))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_hierarchy source_row
                LEFT JOIN building_register_profile_hierarchy target_row
                  USING (publication_id,pnu,source_record_key)
                WHERE target_row.publication_id IS NULL OR to_jsonb(target_row)<>to_jsonb(source_row))
     OR EXISTS (SELECT 1 FROM transfer_building_register_profile_field_evidence source_row
                LEFT JOIN building_register_profile_field_evidence target_row USING (evidence_id)
                WHERE target_row.evidence_id IS NULL OR to_jsonb(target_row)<>to_jsonb(source_row))
     OR EXISTS (SELECT 1 FROM transfer_complex_building_register_profile_summary source_row
                LEFT JOIN complex_building_register_profile_summary target_row
                  USING (publication_id,complex_id)
                WHERE target_row.publication_id IS NULL OR to_jsonb(target_row)<>to_jsonb(source_row)) THEN
    RAISE EXCEPTION 'target publication rows differ from transfer';
  END IF;
  IF (SELECT count(*) FROM building_register_profile_site WHERE publication_id=source.publication_id)<>source.expected_site_count
     OR (SELECT count(*) FROM building_register_profile_building WHERE publication_id=source.publication_id)<>source.expected_building_count
     OR (SELECT count(*) FROM building_register_profile_hierarchy WHERE publication_id=source.publication_id)<>source.expected_hierarchy_count
     OR (SELECT count(*) FROM building_register_profile_field_evidence WHERE publication_id=source.publication_id)<>source.expected_evidence_count
     OR (SELECT count(*) FROM complex_building_register_profile_summary WHERE publication_id=source.publication_id)<>source.expected_summary_count THEN
    RAISE EXCEPTION 'imported publication row counts are incomplete';
  END IF;
  IF target.status='PREPARING' THEN
    PERFORM validate_building_register_profile(source.publication_id,source.content_sha256);
    SELECT * INTO target FROM building_register_profile_publication WHERE publication_id=source.publication_id;
  END IF;
  IF target.status NOT IN ('VALIDATED','PUBLISHED','SUPERSEDED') OR target.content_sha256<>source.content_sha256 THEN
    RAISE EXCEPTION 'imported publication digest or status differs from transfer';
  END IF;
END
\$transfer\$;
COMMIT;
SQL
} | psql_db

echo "publication transfer import 완료: VALIDATED 또는 기존 발행 상태 유지"

#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Usage: $0 export|import|verify"
  echo "Required: PROFILE_COLLECTION_ID PROFILE_ANALYSIS_RUN_ID PROFILE_ARCHIVE_ID PROFILE_RATIO_IMPORT_ID PROFILE_RATIO_WORK_DIRECTORY"
}

fail() {
  echo "차단 사유: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || { usage; exit 2; }
action="$1"
case "$action" in export|import|verify) ;; *) usage; exit 2 ;; esac

collection_id="${PROFILE_COLLECTION_ID:-}"
analysis_run_id="${PROFILE_ANALYSIS_RUN_ID:-}"
archive_id="${PROFILE_ARCHIVE_ID:-}"
import_id="${PROFILE_RATIO_IMPORT_ID:-}"
work_directory="${PROFILE_RATIO_WORK_DIRECTORY:-}"
rules_version="${PROFILE_RATIO_RULES_VERSION:-PROFILE_RATIO_BACKFILL_V1}"
source_container="${PROPERTY_DB_CONTAINER:-home-search-postgis}"
source_database="${PROPERTY_DB_NAME:-home_search}"
source_user="${PROPERTY_DB_USER:-home_search_property_migrator}"
source_host="${PROPERTY_DB_HOST:-}"
source_port="${PROPERTY_DB_PORT:-5432}"
source_password="${PROPERTY_DB_PASSWORD:-}"
restore_container="${PROFILE_RESTORE_DB_CONTAINER:-home-search-profile-analysis-postgis-arm64}"
restore_database="${PROFILE_RESTORE_DB_NAME:-home_search_ratio_backfill_work}"
restore_user="${PROFILE_RESTORE_DB_USER:-home_search}"
request_id="${PROFILE_RATIO_REQUEST_ID:-}"

uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
for value in "$collection_id" "$analysis_run_id" "$archive_id" "$import_id"; do
  [[ "$value" =~ $uuid_pattern ]] || fail "collection/analysis/archive/import id는 canonical lowercase UUID여야 합니다"
done
[[ "$work_directory" = /* ]] || fail "PROFILE_RATIO_WORK_DIRECTORY는 절대 경로여야 합니다"
[[ -n "$rules_version" ]] || fail "rules version이 비어 있습니다"

candidate_path="${work_directory}/profile-ratio-${import_id}.csv"

psql_source() {
  if [[ -n "$source_host" ]]; then
    [[ -n "$source_password" ]] || fail "PROPERTY_DB_HOST 사용 시 PROPERTY_DB_PASSWORD가 필요합니다"
    PGPASSWORD="$source_password" psql -X -v ON_ERROR_STOP=1 -h "$source_host" -p "$source_port" \
      -U "$source_user" -d "$source_database" "$@"
  else
    docker exec -i "$source_container" psql -X -v ON_ERROR_STOP=1 -U "$source_user" -d "$source_database" "$@"
  fi
}

psql_restore() {
  docker exec -i "$restore_container" psql -X -v ON_ERROR_STOP=1 -U "$restore_user" -d "$restore_database" "$@"
}

verify_current_lineage() {
  local result
  result="$(psql_source -At -v collection="$collection_id" -v analysis="$analysis_run_id" -v archive="$archive_id" <<'SQL'
SELECT CASE WHEN
  EXISTS (
    SELECT 1
    FROM building_register_profile_analysis_run analysis
    JOIN building_register_collection_campaign campaign USING(collection_id)
    WHERE analysis.analysis_run_id=:'analysis'::uuid
      AND campaign.collection_id=:'collection'::uuid
      AND analysis.status='COMPLETED'
      AND campaign.status='COMPLETED'
      AND campaign.purpose='PROFILE_DISCOVERY'
      AND campaign.target_scope='NATIONWIDE_STAGING')
  AND EXISTS (
    SELECT 1
    FROM building_register_profile_archive_manifest
    WHERE archive_id=:'archive'::uuid
      AND collection_id=:'collection'::uuid
      AND analysis_run_id=:'analysis'::uuid
      AND status='CLEANED')
THEN 'yes' ELSE 'no' END;
SQL
)"
  [[ "$result" = "yes" ]] || fail "COMPLETED 전국 analysis와 CLEANED archive manifest가 필요합니다"
}

verify_current_lineage

if [[ "$action" = "export" ]]; then
  mkdir -p "$work_directory"
  [[ ! -e "$candidate_path" ]] || fail "candidate 파일이 이미 존재합니다: ${candidate_path}"
  restored="$(psql_restore -At -v analysis="$analysis_run_id" <<'SQL'
SELECT CASE WHEN
  EXISTS (SELECT 1 FROM building_register_profile_analysis_run
          WHERE analysis_run_id=:'analysis'::uuid AND status='COMPLETED')
  AND EXISTS (SELECT 1 FROM building_register_profile_comparison
             WHERE analysis_run_id=:'analysis'::uuid AND field_id IN ('BC_RAT','VL_RAT'))
  AND EXISTS (SELECT 1 FROM building_register_profile_complex_match
             WHERE analysis_run_id=:'analysis'::uuid AND projectable)
THEN 'yes' ELSE 'no' END;
SQL
)"
  [[ "$restored" = "yes" ]] || fail "archive 복원 DB에 완료된 comparison/match evidence가 없습니다"
  psql_restore -q -c "CREATE EXTENSION IF NOT EXISTS pgcrypto" >/dev/null
  temporary_path="${candidate_path}.tmp"
  trap 'rm -f "$temporary_path"' EXIT
  psql_restore -q -v analysis="$analysis_run_id" <<'SQL' >"$temporary_path"
COPY (
  WITH eligible AS (
    SELECT m.complex_id,
           m.pnu,
           m.scope_key AS root_management_key,
           CASE c.field_id
             WHEN 'BC_RAT' THEN 'BUILDING_COVERAGE_RATIO'
             WHEN 'VL_RAT' THEN 'FLOOR_AREA_RATIO'
           END AS field,
           'RECAP_DIRECT' AS method,
           (c.recap_value #>> '{}')::numeric AS source_value,
           round((c.recap_value #>> '{}')::numeric,2) AS projected_value,
           c.status AS comparison_status,
           (c.recap_value #>> '{}')::numeric AS recap_value,
           (c.title_value #>> '{}')::numeric AS title_value,
           c.difference,
           c.contributor_count,
           c.expected_contributor_count,
           c.pnu_scope_hash,
           encode(digest(convert_to(concat_ws('|',m.complex_id,c.field_id,c.status,
             c.recap_value #>> '{}',c.title_value #>> '{}',c.difference,
             c.contributor_count,c.expected_contributor_count,c.pnu_scope_hash),'UTF8'),'sha256'),'hex')
             AS evidence_sha256
    FROM building_register_profile_complex_match m
    JOIN building_register_profile_comparison c
      ON c.analysis_run_id=m.analysis_run_id
     AND c.pnu_scope_hash=encode(digest(
       convert_to(m.pnu,'UTF8') || decode('00','hex') || convert_to(m.scope_key,'UTF8'),
       'sha256'),'hex')
    WHERE m.analysis_run_id=:'analysis'::uuid
      AND m.status='RESOLVED'
      AND m.projectable
      AND c.field_id IN ('BC_RAT','VL_RAT')
      AND c.status IN ('MATCH','WITHIN_TOLERANCE')
      AND c.contributor_count=c.expected_contributor_count
      AND c.expected_contributor_count>0
      AND (c.recap_value #>> '{}')::numeric>0
      AND (c.title_value #>> '{}')::numeric>0
      AND c.difference BETWEEN 0 AND 0.01
  )
  SELECT complex_id,pnu,root_management_key,field,method,source_value,projected_value,
         comparison_status,recap_value,title_value,difference,contributor_count,
         expected_contributor_count,pnu_scope_hash,evidence_sha256
  FROM eligible
  ORDER BY complex_id,field
) TO STDOUT WITH (FORMAT csv, HEADER true);
SQL
  candidate_count="$(( $(wc -l <"$temporary_path") - 1 ))"
  [[ "$candidate_count" -gt 0 ]] || fail "안전 candidate가 없습니다"
  chmod 600 "$temporary_path"
  mv "$temporary_path" "$candidate_path"
  trap - EXIT
  candidate_sha="$(shasum -a 256 "$candidate_path" | awk '{print $1}')"
  echo "CANDIDATE_COUNT=${candidate_count}"
  echo "CANDIDATE_SHA256=${candidate_sha}"
  echo "CANDIDATE_FILE=${candidate_path}"
  exit 0
fi

if [[ "$action" = "import" ]]; then
  existing="$(psql_source -At -v import="$import_id" <<'SQL'
SELECT concat_ws('|',status,source_file_sha256,candidate_count)
FROM building_ratio_profile_backfill_import WHERE import_id=:'import'::uuid;
SQL
)"
  if [[ -n "$existing" ]]; then
    [[ "$existing" = COMPLETED\|* ]] || fail "같은 import id가 terminal COMPLETED 상태가 아닙니다"
    if [[ -f "$candidate_path" ]]; then
      [[ "$(stat -f %Lp "$candidate_path")" = "600" ]] || fail "candidate 파일 권한은 600이어야 합니다"
      candidate_sha="$(shasum -a 256 "$candidate_path" | awk '{print $1}')"
      candidate_count="$(( $(wc -l <"$candidate_path") - 1 ))"
      [[ "$existing" = "COMPLETED|${candidate_sha}|${candidate_count}" ]] \
        || fail "같은 import id의 기존 evidence가 candidate 파일과 다릅니다"
      rm -f "$candidate_path"
      echo "이미 완료된 import와 SHA-256·행 수가 일치합니다"
    else
      echo "이미 완료된 import evidence가 있어 재적재하지 않습니다"
    fi
    exit 0
  fi

  [[ -f "$candidate_path" ]] || fail "candidate 파일이 없습니다: ${candidate_path}"
  [[ "$(stat -f %Lp "$candidate_path")" = "600" ]] || fail "candidate 파일 권한은 600이어야 합니다"
  candidate_sha="$(shasum -a 256 "$candidate_path" | awk '{print $1}')"
  candidate_count="$(( $(wc -l <"$candidate_path") - 1 ))"
  [[ "$candidate_count" -gt 0 ]] || fail "candidate 파일이 비어 있습니다"

  {
    cat <<'SQL'
BEGIN;
CREATE TEMP TABLE profile_ratio_import_stage (
  complex_id bigint NOT NULL,
  pnu character varying(19) NOT NULL,
  root_management_key character varying(255) NOT NULL,
  field character varying(32) NOT NULL,
  method character varying(48) NOT NULL,
  source_value numeric(18,8) NOT NULL,
  projected_value numeric(6,2) NOT NULL,
  comparison_status character varying(32) NOT NULL,
  recap_value numeric(18,8) NOT NULL,
  title_value numeric(30,12) NOT NULL,
  difference numeric(30,12) NOT NULL,
  contributor_count integer NOT NULL,
  expected_contributor_count integer NOT NULL,
  pnu_scope_hash character varying(64) NOT NULL,
  evidence_sha256 character varying(64) NOT NULL
) ON COMMIT DROP;
CREATE TEMP TABLE profile_ratio_import_context (
  collection_id uuid NOT NULL,
  import_id uuid NOT NULL,
  candidate_count integer NOT NULL
) ON COMMIT DROP;
INSERT INTO profile_ratio_import_context
VALUES (:'collection'::uuid,:'import'::uuid,:'candidate_count'::integer);
COPY profile_ratio_import_stage FROM STDIN WITH (FORMAT csv, HEADER true);
SQL
    cat "$candidate_path"
    printf '\\.\n'
    cat <<'SQL'
DO $block$
BEGIN
  IF (SELECT count(*) FROM profile_ratio_import_stage)
       <> (SELECT candidate_count FROM profile_ratio_import_context) THEN
    RAISE EXCEPTION 'candidate row count mismatch';
  END IF;
  IF EXISTS (
    SELECT 1 FROM profile_ratio_import_stage
    GROUP BY complex_id,field HAVING count(*)<>1) THEN
    RAISE EXCEPTION 'duplicate complex ratio candidate';
  END IF;
  IF EXISTS (
    SELECT 1 FROM profile_ratio_import_stage s
    LEFT JOIN complex c ON c.id=s.complex_id
    LEFT JOIN parcel p ON p.id=c.parcel_id
    WHERE c.id IS NULL OR p.pnu<>s.pnu) THEN
    RAISE EXCEPTION 'candidate complex/PNU identity mismatch';
  END IF;
END
$block$;

INSERT INTO building_ratio_profile_backfill_import
  (import_id,analysis_run_id,archive_id,rules_version,source_file_sha256,candidate_count,status)
VALUES (:'import'::uuid,:'analysis'::uuid,:'archive'::uuid,:'rules',:'candidate_sha',0,'IMPORTING');

INSERT INTO building_register_complex_match
  (collection_id,complex_id,pnu,root_management_key,scope,status,match_path,projectable,failure_reason)
SELECT DISTINCT :'collection'::uuid,complex_id,pnu,root_management_key,
       'UNIQUE_ROOT','RESOLVED',NULL,true,NULL
FROM profile_ratio_import_stage
ON CONFLICT (collection_id,complex_id) DO NOTHING;

DO $block$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM profile_ratio_import_stage s
    JOIN building_register_complex_match m
      ON m.collection_id=(SELECT collection_id FROM profile_ratio_import_context)
     AND m.complex_id=s.complex_id
    WHERE m.pnu<>s.pnu OR m.root_management_key<>s.root_management_key
       OR m.scope<>'UNIQUE_ROOT' OR m.status<>'RESOLVED' OR NOT m.projectable) THEN
    RAISE EXCEPTION 'existing match evidence conflicts with profile candidate';
  END IF;
END
$block$;

INSERT INTO building_ratio_candidate
  (match_id,field,method,value,projected_value,status,selected,reason)
SELECT m.id,s.field,s.method,s.source_value,s.projected_value,'VALID',true,
       'PROFILE_RATIO_BACKFILL:' || :'import'
FROM profile_ratio_import_stage s
JOIN building_register_complex_match m
  ON m.collection_id=:'collection'::uuid AND m.complex_id=s.complex_id
WHERE NOT EXISTS (
  SELECT 1 FROM building_ratio_candidate candidate
  WHERE candidate.match_id=m.id AND candidate.field=s.field AND candidate.selected);

DO $block$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM profile_ratio_import_stage s
    JOIN building_register_complex_match m
      ON m.collection_id=(SELECT collection_id FROM profile_ratio_import_context)
     AND m.complex_id=s.complex_id
    JOIN building_ratio_candidate c
      ON c.match_id=m.id AND c.field=s.field AND c.selected
    WHERE c.status<>'VALID' OR c.method<>s.method
       OR c.value<>s.source_value OR c.projected_value<>s.projected_value) THEN
    RAISE EXCEPTION 'selected ratio candidate conflicts with profile evidence';
  END IF;
END
$block$;

INSERT INTO building_ratio_profile_candidate_lineage
  (candidate_id,import_id,pnu_scope_hash,comparison_status,recap_value,title_value,difference,
   contributor_count,expected_contributor_count,evidence_sha256)
SELECT c.id,:'import'::uuid,s.pnu_scope_hash,s.comparison_status,s.recap_value,s.title_value,
       s.difference,s.contributor_count,s.expected_contributor_count,s.evidence_sha256
FROM profile_ratio_import_stage s
JOIN building_register_complex_match m
  ON m.collection_id=:'collection'::uuid AND m.complex_id=s.complex_id
JOIN building_ratio_candidate c
  ON c.match_id=m.id AND c.field=s.field AND c.selected
ON CONFLICT (candidate_id) DO NOTHING;

DO $block$
BEGIN
  IF (SELECT count(*) FROM building_ratio_profile_candidate_lineage
      WHERE import_id=(SELECT import_id FROM profile_ratio_import_context))
       <> (SELECT candidate_count FROM profile_ratio_import_context) THEN
    RAISE EXCEPTION 'profile lineage row count mismatch';
  END IF;
END
$block$;

UPDATE building_ratio_profile_backfill_import
SET status='COMPLETED',candidate_count=:'candidate_count'::integer,completed_at=now()
WHERE import_id=:'import'::uuid AND status='IMPORTING';
COMMIT;
SQL
  } | psql_source -v collection="$collection_id" -v analysis="$analysis_run_id" \
      -v archive="$archive_id" -v import="$import_id" -v rules="$rules_version" \
      -v candidate_sha="$candidate_sha" -v candidate_count="$candidate_count" >/dev/null
  rm -f "$candidate_path"
  echo "PROFILE_RATIO_IMPORT_ID=${import_id}"
  echo "CANDIDATE_COUNT=${candidate_count}"
  echo "candidate import 완료; 임시 PNU 파일 삭제 완료"
  exit 0
fi

[[ "$request_id" =~ $uuid_pattern ]] || fail "verify에는 PROFILE_RATIO_REQUEST_ID가 필요합니다"
psql_source -v import="$import_id" -v request="$request_id" <<'SQL'
CREATE TEMP TABLE profile_ratio_verify_context AS
SELECT :'import'::uuid AS import_id, :'request'::uuid AS request_id;

SELECT lineage_count,projected_count,applied_count,already_equal_count,existing_conflict_count
FROM (
  SELECT
    (SELECT count(*) FROM building_ratio_profile_candidate_lineage WHERE import_id=:'import'::uuid) lineage_count,
    count(*) projected_count,
    count(*) FILTER (WHERE projection.outcome='APPLIED') applied_count,
    count(*) FILTER (WHERE projection.outcome='ALREADY_EQUAL') already_equal_count,
    count(*) FILTER (WHERE projection.outcome='SKIPPED_EXISTING_CONFLICT') existing_conflict_count
  FROM building_ratio_profile_candidate_lineage lineage
  JOIN building_ratio_projection projection ON projection.candidate_id=lineage.candidate_id
  WHERE lineage.import_id=:'import'::uuid AND projection.request_id=:'request'::uuid
) summary;

DO $block$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM building_ratio_profile_candidate_lineage lineage
    JOIN building_ratio_candidate candidate ON candidate.id=lineage.candidate_id
    JOIN building_register_complex_match match ON match.id=candidate.match_id
    LEFT JOIN building_ratio_projection projection
      ON projection.candidate_id=candidate.id
     AND projection.request_id=(SELECT request_id FROM profile_ratio_verify_context)
    JOIN complex c ON c.id=match.complex_id
    WHERE lineage.import_id=(SELECT import_id FROM profile_ratio_verify_context)
      AND (projection.id IS NULL
        OR projection.outcome NOT IN ('APPLIED','ALREADY_EQUAL','SKIPPED_EXISTING_CONFLICT')
        OR (projection.outcome='APPLIED' AND (projection.previous_value IS NOT NULL OR
            CASE candidate.field WHEN 'BUILDING_COVERAGE_RATIO' THEN c.bc_rat ELSE c.vl_rat END
              IS DISTINCT FROM projection.applied_value))
        OR (projection.outcome IN ('ALREADY_EQUAL','SKIPPED_EXISTING_CONFLICT') AND
            CASE candidate.field WHEN 'BUILDING_COVERAGE_RATIO' THEN c.bc_rat ELSE c.vl_rat END
              IS DISTINCT FROM projection.previous_value))
  ) THEN
    RAISE EXCEPTION 'ratio projection verification failed';
  END IF;
END
$block$;
SQL

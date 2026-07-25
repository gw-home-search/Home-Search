#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_ROOT="$(cd "${OPS_DIR}/.." && pwd)"
REPOSITORY_ROOT="$(cd "${SERVICE_ROOT}/../.." && pwd)"
RUN_DATE="${MARKET_NEWS_E2E_RUN_DATE:-$(TZ=Asia/Seoul date +%F)}"
BOOTSTRAP_UUID="${MARKET_NEWS_E2E_BOOTSTRAP_UUID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
GENERAL_REQUEST_ID="BOOTSTRAP:${BOOTSTRAP_UUID}"
MAJOR_REQUEST_ID="${MARKET_NEWS_E2E_MAJOR_REQUEST_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
SELECTION_REQUEST_ID="${MARKET_NEWS_E2E_SELECTION_REQUEST_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
RETENTION_REQUEST_ID="${MARKET_NEWS_E2E_RETENTION_REQUEST_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
QUALITY_REVIEW_SET_ID="${MARKET_NEWS_E2E_QUALITY_REVIEW_SET_ID:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
NEWS_POLICY_VERSION="${MARKET_NEWS_E2E_POLICY_VERSION:-NEWS_V4}"
EVIDENCE_DIR="${MARKET_NEWS_E2E_EVIDENCE_DIR:-${REPOSITORY_ROOT}/tmp/market-news-e2e/${RUN_DATE}-${BOOTSTRAP_UUID}}"
BATCH_JAR="${PROPERTY_DATA_BATCH_JAR:-${SERVICE_ROOT}/batch/build/libs/property-data-batch.jar}"
PSQL_DSN="${MARKET_NEWS_E2E_PSQL_DSN:-}"
API_URL="${MARKET_NEWS_E2E_API_URL:-http://localhost:8080}"
REDIS_HOST="${MARKET_NEWS_E2E_REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${MARKET_NEWS_E2E_REDIS_PORT:-16379}"
REDIS_CONTAINER="${MARKET_NEWS_E2E_REDIS_CONTAINER:-home-search-redis}"
NAVER_CLIENT_ID="${HOME_NEWS_NAVER_CLIENT_ID:-${NAVER_NEWS_API_KEY_ID:-}}"
NAVER_CLIENT_SECRET="${HOME_NEWS_NAVER_CLIENT_SECRET:-${NAVER_NEWS_API_KEY:-}}"

usage() {
  printf '사용법: private env를 현재 shell에 주입한 뒤 %s\n' "$0" >&2
  printf '필수: DB_JDBC_URL, DB_USERNAME, DB_PASSWORD, NAVER_NEWS_API_KEY_ID(또는 HOME_NEWS_NAVER_CLIENT_ID), NAVER_NEWS_API_KEY(또는 HOME_NEWS_NAVER_CLIENT_SECRET), MARKET_NEWS_E2E_PSQL_DSN\n' >&2
  exit 2
}

require_environment() {
  local name
  local uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  local -a required=(
    DB_JDBC_URL DB_USERNAME DB_PASSWORD
    MARKET_NEWS_E2E_PSQL_DSN
  )
  for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      printf '누락된 환경 변수: %s\n' "${name}" >&2
      exit 2
    fi
  done
  if [[ -z "${NAVER_CLIENT_ID}" ]]; then
    printf '누락된 환경 변수: NAVER_NEWS_API_KEY_ID 또는 HOME_NEWS_NAVER_CLIENT_ID\n' >&2
    exit 2
  fi
  if [[ -z "${NAVER_CLIENT_SECRET}" ]]; then
    printf '누락된 환경 변수: NAVER_NEWS_API_KEY 또는 HOME_NEWS_NAVER_CLIENT_SECRET\n' >&2
    exit 2
  fi
  if [[ "${PSQL_DSN}" == *"password="* || "${PSQL_DSN}" =~ ://[^/@]+:[^/@]+@ ]]; then
    printf '거부됨: MARKET_NEWS_E2E_PSQL_DSN에 password를 포함하지 마세요. DB_PASSWORD를 사용합니다.\n' >&2
    exit 2
  fi
  [[ "${RUN_DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || usage
  [[ "${BOOTSTRAP_UUID}" =~ ${uuid_pattern} ]] || usage
  [[ "${MAJOR_REQUEST_ID}" =~ ${uuid_pattern} ]] || usage
  [[ "${SELECTION_REQUEST_ID}" =~ ${uuid_pattern} ]] || usage
  [[ "${RETENTION_REQUEST_ID}" =~ ${uuid_pattern} ]] || usage
  [[ "${QUALITY_REVIEW_SET_ID}" =~ ${uuid_pattern} ]] || usage
  command -v psql >/dev/null
  command -v curl >/dev/null
  command -v jq >/dev/null
  if ! command -v redis-cli >/dev/null; then
    command -v docker >/dev/null
    if [[ -n "${MARKET_NEWS_E2E_REDIS_PASSWORD:-}" ]]; then
      printf '거부됨: Redis password 사용 시 호스트 redis-cli가 필요합니다.\n' >&2
      exit 2
    fi
    docker inspect "${REDIS_CONTAINER}" >/dev/null 2>&1 || {
      printf '누락됨: redis-cli 또는 실행 중인 Redis 컨테이너(%s)가 필요합니다.\n' \
        "${REDIS_CONTAINER}" >&2
      exit 2
    }
  fi
}

record() {
  printf '%s=%s\n' "$1" "$2" >> "${EVIDENCE_DIR}/summary.txt"
}

query_scalar() {
  PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -At "$@"
}

run_batch() {
  local job_name="$1"
  local request_id="$2"
  local log_name="$3"
  HOME_NEWS_NAVER_ENABLED=true \
  HOME_NEWS_NAVER_PROVIDER_MODE="${HOME_NEWS_NAVER_PROVIDER_MODE:-DEVELOPERS}" \
  HOME_NEWS_NAVER_BASE_URL="${HOME_NEWS_NAVER_BASE_URL:-https://openapi.naver.com}" \
  HOME_NEWS_NAVER_PATH="${HOME_NEWS_NAVER_PATH:-/v1/search/news.json}" \
  HOME_NEWS_NAVER_CLIENT_ID="${NAVER_CLIENT_ID}" \
  HOME_NEWS_NAVER_CLIENT_SECRET="${NAVER_CLIENT_SECRET}" \
  HOME_NEWS_CACHE_ENABLED=true \
  SPRING_DATA_REDIS_HOST="${REDIS_HOST}" \
  SPRING_DATA_REDIS_PORT="${REDIS_PORT}" \
  SPRING_BATCH_JOB_NAME="${job_name}" \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
    "${OPS_DIR}/run-batch-jar.sh" \
      "runDate=${RUN_DATE}" \
      "requestId=${request_id}" \
      > "${EVIDENCE_DIR}/${log_name}" 2>&1
}

run_quality_sample() {
  HOME_NEWS_NAVER_ENABLED=false \
  HOME_NEWS_CACHE_ENABLED=false \
  SPRING_BATCH_JOB_NAME=marketNewsQualitySampleJob \
  PROPERTY_DATA_BATCH_JAR="${BATCH_JAR}" \
    "${OPS_DIR}/run-batch-jar.sh" \
      "reviewSetId=${QUALITY_REVIEW_SET_ID}" \
      "policyVersion=${NEWS_POLICY_VERSION}" \
      > "${EVIDENCE_DIR}/quality-sample.log" 2>&1
}

verify_migration() {
  local version
  version="$(query_scalar -c "
    SELECT max(version::integer)
    FROM flyway_schema_history
    WHERE success AND version ~ '^[0-9]+$';
  ")"
  if [[ -z "${version}" || "${version}" -lt 21 ]]; then
    printf '거부됨: market news V21 migration이 적용되지 않았습니다. latest=%s\n' "${version:-none}" >&2
    exit 1
  fi
  record flyway_latest "${version}"
}

verify_selection() {
  local coverage selection_week selected sido_count minimum_per_sido
  coverage="$(query_scalar -F '|' -c "
    WITH latest AS (
      SELECT max(selection_week) AS selection_week
      FROM market_news_major_complex_selection
      WHERE selection_status = 'PUBLISHED'
    ), region_counts AS (
      SELECT selection.selection_week, selection.region_code, count(*) AS selected
      FROM market_news_major_complex_selection selection
      JOIN latest USING (selection_week)
      WHERE selection.selection_status = 'PUBLISHED'
      GROUP BY selection.selection_week, selection.region_code
    )
    SELECT max(selection_week), sum(selected), count(*), min(selected)
    FROM region_counts;
  ")"
  IFS='|' read -r selection_week selected sido_count minimum_per_sido <<< "${coverage}"
  if [[ "${selected}" != "200" || "${sido_count}" != "17" || -z "${minimum_per_sido}" \
      || "${minimum_per_sido}" -lt 5 ]]; then
    printf '거부됨: 주요 단지 선정이 200개/17개 시도/시도별 최소 5개를 만족하지 않습니다.\n' >&2
    exit 1
  fi
  record selection_week "${selection_week}"
  record selected_complex_count "${selected}"
  record selected_sido_count "${sido_count}"
}

verify_execution() {
  local request_id="$1"
  local expected_type="$2"
  local evidence_prefix="$3"
  local summary state planned completed truncated failed skipped raw articles relations bootstrap_truncated
  summary="$(
    PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
      -v request_id="${request_id}" -v execution_type="${expected_type}" -f - <<'SQL'
      SELECT state, planned_work_unit_count, completed_work_unit_count,
             truncated_work_unit_count, failed_work_unit_count, skipped_budget_work_unit_count,
             raw_item_count, article_count, relation_count, bootstrap_truncated
      FROM market_news_collection_execution
      WHERE request_id = :'request_id' AND execution_type = :'execution_type';
SQL
  )"
  IFS='|' read -r state planned completed truncated failed skipped raw articles relations bootstrap_truncated <<< "${summary}"
  if [[ -z "${state}" || "${failed}" != "0" || "${skipped}" != "0" \
      || $((completed + truncated)) -ne "${planned}" ]]; then
    printf '거부됨: %s execution에 실패·budget skip·미종료 work unit이 있습니다. requestId=%s\n' \
      "${expected_type}" "${request_id}" >&2
    exit 1
  fi
  if [[ "${expected_type}" == "BOOTSTRAP" ]]; then
    if [[ "${state}" != "COMPLETED" && "${state}" != "PARTIAL" ]]; then
      printf '거부됨: BOOTSTRAP state가 publication 가능 상태가 아닙니다. state=%s\n' "${state}" >&2
      exit 1
    fi
    if [[ "${state}" == "PARTIAL" && "${bootstrap_truncated}" != "t" ]]; then
      printf '거부됨: PARTIAL BOOTSTRAP에 bootstrap_truncated 근거가 없습니다.\n' >&2
      exit 1
    fi
  elif [[ "${state}" != "COMPLETED" ]]; then
    printf '거부됨: %s execution이 COMPLETED가 아닙니다. state=%s\n' "${expected_type}" "${state}" >&2
    exit 1
  fi
  record "${evidence_prefix}_state" "${state}"
  record "${evidence_prefix}_planned" "${planned}"
  record "${evidence_prefix}_completed" "${completed}"
  record "${evidence_prefix}_truncated" "${truncated}"
  record "${evidence_prefix}_raw_count" "${raw}"
  record "${evidence_prefix}_article_count" "${articles}"
  record "${evidence_prefix}_relation_count" "${relations}"
}

verify_database_publication() {
  local counts published published_from_run raw_count article_count relation_count
  local duplicate_count invalid_count fk_gap_count
  counts="$(
    PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
      -v general_request_id="${GENERAL_REQUEST_ID}" -v major_request_id="${MAJOR_REQUEST_ID}" -f - <<'SQL'
    SELECT
      (SELECT count(*) FROM market_news_snapshot WHERE build_status = 'PUBLISHED'),
      (SELECT count(*)
       FROM market_news_snapshot snapshot
       JOIN market_news_collection_execution execution USING (execution_id)
       WHERE snapshot.build_status = 'PUBLISHED'
         AND execution.request_id IN (:'general_request_id', :'major_request_id')),
      (SELECT count(*) FROM market_news_raw_item),
      (SELECT count(*) FROM market_news_article),
      (SELECT count(*) FROM market_news_relation),
      (SELECT count(*) FROM (
         SELECT item.snapshot_id, item.article_id
         FROM market_news_snapshot_item item
         JOIN market_news_snapshot snapshot USING (snapshot_id)
         WHERE snapshot.build_status = 'PUBLISHED'
         GROUP BY snapshot_id, article_id HAVING count(*) > 1
       ) duplicate),
      (SELECT count(*)
       FROM market_news_snapshot_item item
       JOIN market_news_snapshot snapshot USING (snapshot_id)
       JOIN market_news_article article USING (article_id)
       WHERE snapshot.build_status = 'PUBLISHED'
         AND (btrim(article.title) = ''
          OR article.public_url !~ '^https?://'
          OR article.provided_at > snapshot.generated_at + interval '5 minutes'
          OR article.provided_at < snapshot.generated_at - interval '30 days')),
      (SELECT count(*)
       FROM market_news_snapshot_item item
       LEFT JOIN market_news_article article USING (article_id)
       LEFT JOIN market_news_relation relation USING (relation_id)
       WHERE article.article_id IS NULL OR relation.relation_id IS NULL);
SQL
  )"
  IFS='|' read -r published published_from_run raw_count article_count relation_count \
    duplicate_count invalid_count fk_gap_count <<< "${counts}"
  if [[ "${published}" != "18" || "${published_from_run}" != "18" \
      || "${raw_count}" -le 0 || "${article_count}" -le 0 \
      || "${relation_count}" -le 0 || "${duplicate_count}" != "0" \
      || "${invalid_count}" != "0" || "${fk_gap_count}" != "0" ]]; then
    printf '거부됨: DB publication hard gate를 통과하지 못했습니다.\n' >&2
    exit 1
  fi
  record published_scope_count "${published}"
  record published_scope_count_from_run "${published_from_run}"
  record raw_item_count "${raw_count}"
  record article_count "${article_count}"
  record relation_count "${relation_count}"
  record snapshot_duplicate_count "${duplicate_count}"
  record public_item_invalid_count "${invalid_count}"
  record snapshot_fk_gap_count "${fk_gap_count}"
}

redis_get() {
  local key="$1"
  if command -v redis-cli >/dev/null; then
    REDISCLI_AUTH="${MARKET_NEWS_E2E_REDIS_PASSWORD:-}" \
      redis-cli --raw -h "${REDIS_HOST}" -p "${REDIS_PORT}" GET "${key}"
    return
  fi
  docker exec "${REDIS_CONTAINER}" redis-cli --raw GET "${key}"
}

verify_cache_and_api() {
  local current_key last_good_key current last_good db_snapshot api_response
  local current_snapshot last_good_snapshot api_snapshot api_status api_items
  current_key="market-news:current:NATIONWIDE:_"
  last_good_key="market-news:last-good:NATIONWIDE:_"
  current="$(redis_get "${current_key}")"
  last_good="$(redis_get "${last_good_key}")"
  current_snapshot="$(jq -er '.snapshotId' <<< "${current}")"
  last_good_snapshot="$(jq -er '.snapshotId' <<< "${last_good}")"
  db_snapshot="$(query_scalar -c "
    SELECT snapshot_id
    FROM market_news_snapshot
    WHERE build_status = 'PUBLISHED' AND scope_type = 'NATIONWIDE' AND region_code IS NULL;
  ")"
  if [[ "${current_snapshot}" != "${db_snapshot}" || "${last_good_snapshot}" != "${db_snapshot}" ]]; then
    printf '거부됨: Redis current/last-good와 DB 전국 snapshot이 일치하지 않습니다.\n' >&2
    exit 1
  fi

  api_response="$(curl --fail --silent --show-error \
    "${API_URL%/}/api/v1/insights/news?scope=NATIONWIDE&category=ALL&limit=20")"
  api_snapshot="$(jq -er '.snapshotId' <<< "${api_response}")"
  api_status="$(jq -er '.dataStatus' <<< "${api_response}")"
  api_items="$(jq -er '.items | length' <<< "${api_response}")"
  if [[ "${api_snapshot}" != "${db_snapshot}" || "${api_status}" != "FRESH" ]]; then
    printf '거부됨: API와 DB snapshot/status가 일치하지 않습니다.\n' >&2
    exit 1
  fi
  record nationwide_snapshot_id "${db_snapshot}"
  record nationwide_data_status "${api_status}"
  record nationwide_first_page_items "${api_items}"
}

verify_quality_sample() {
  local summary policy status total category_min sido direct same_dong same_sigungu challenge url_count
  summary="$(
    PGPASSWORD="${DB_PASSWORD}" psql "${PSQL_DSN}" -v ON_ERROR_STOP=1 -AtF '|' \
      -v review_set_id="${QUALITY_REVIEW_SET_ID}" -f - <<'SQL'
      SELECT policy_version, status, total_sample_count, minimum_category_count,
             covered_sido_count, direct_complex_count, same_dong_count,
             same_sigungu_count, complex_challenge_count, url_sample_count
      FROM market_news_quality_review_set
      WHERE review_set_id = :'review_set_id';
SQL
  )"
  IFS='|' read -r policy status total category_min sido direct same_dong same_sigungu challenge url_count \
    <<< "${summary}"
  if [[ "${policy}" != "${NEWS_POLICY_VERSION}" || -z "${status}" || -z "${total}" || "${total}" -le 0 ]]; then
    printf '거부됨: %s 품질 표본 근거가 저장되지 않았습니다.\n' "${NEWS_POLICY_VERSION}" >&2
    exit 1
  fi
  record quality_review_set_id "${QUALITY_REVIEW_SET_ID}"
  record quality_status "${status}"
  record quality_total_sample_count "${total}"
  record quality_minimum_category_count "${category_min}"
  record quality_covered_sido_count "${sido}"
  record quality_direct_complex_count "${direct}"
  record quality_same_dong_count "${same_dong}"
  record quality_same_sigungu_count "${same_sigungu}"
  record quality_complex_challenge_count "${challenge}"
  record quality_url_sample_count "${url_count}"
}

main() {
  [[ "$#" -eq 0 ]] || usage
  require_environment
  mkdir -p "${EVIDENCE_DIR}"
  : > "${EVIDENCE_DIR}/summary.txt"
  printf '%s\n' 'provider title, description, URL은 evidence 파일에 기록하지 않습니다' \
    > "${EVIDENCE_DIR}/DATA_POLICY.txt"
  record run_date "${RUN_DATE}"
  record policy_version "${NEWS_POLICY_VERSION}"
  record bootstrap_request_id "${GENERAL_REQUEST_ID}"
  record major_request_id "${MAJOR_REQUEST_ID}"
  record started_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  cd "${SERVICE_ROOT}"
  if [[ "${MARKET_NEWS_E2E_SKIP_BUILD:-false}" == "true" ]]; then
    printf '기존 검증 JAR 사용: %s\n' "${BATCH_JAR}" > "${EVIDENCE_DIR}/build.log"
  else
    ./gradlew :batch:bootJar --no-daemon --stacktrace > "${EVIDENCE_DIR}/build.log" 2>&1
  fi
  [[ -f "${BATCH_JAR}" ]] || {
    printf 'batch jar가 없습니다: %s\n' "${BATCH_JAR}" >&2
    exit 2
  }
  record batch_jar_sha256 "$(shasum -a 256 "${BATCH_JAR}" | awk '{print $1}')"

  verify_migration
  run_batch marketNewsMajorSelectionJob "${SELECTION_REQUEST_ID}" "major-selection.log"
  verify_selection
  run_batch marketNewsGeneralJob "${GENERAL_REQUEST_ID}" "bootstrap-general.log"
  verify_execution "${GENERAL_REQUEST_ID}" "BOOTSTRAP" "bootstrap"
  run_batch marketNewsMajorComplexJob "${MAJOR_REQUEST_ID}" "major-complex.log"
  verify_execution "${MAJOR_REQUEST_ID}" "MAJOR_COMPLEX" "major"
  verify_database_publication
  verify_cache_and_api
  run_quality_sample
  verify_quality_sample
  run_batch marketNewsRetentionJob "${RETENTION_REQUEST_ID}" "retention.log"

  record completed_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '완료: bootstrapRequestId=%s evidence=%s\n' "${GENERAL_REQUEST_ID}" "${EVIDENCE_DIR}"
}

main "$@"

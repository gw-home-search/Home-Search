#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ./ops/verify-news-clean-db-cutover.sh --verify-absent
  ./ops/verify-news-clean-db-cutover.sh --self-test

Environment:
  HOME_NEWS_CUTOVER_DB        Database to inspect. Defaults to home_search_clean_codex_20260616.
  DB_HOST                     PostgreSQL host. Defaults to localhost.
  DB_PORT                     PostgreSQL port. Defaults to 5432.
  DB_USERNAME                 PostgreSQL username. Defaults to home_search.
USAGE
}

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USERNAME="${DB_USERNAME:-home_search}"
NEWS_CUTOVER_DB="${HOME_NEWS_CUTOVER_DB:-home_search_clean_codex_20260616}"

news_table_values_sql() {
  cat <<'SQL'
VALUES
  ('public.news_article_observation'),
  ('public.news_signal_feature'),
  ('public.news_collection_run')
SQL
}

psql_scalar() {
  local database="$1"
  local sql="$2"
  psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d "${database}" -At -c "${sql}"
}

verify_absent() {
  local existing_count
  existing_count="$(psql_scalar "${NEWS_CUTOVER_DB}" "
    SELECT count(*)
    FROM (
      $(news_table_values_sql)
    ) AS target(name)
    WHERE to_regclass(name) IS NOT NULL
  ")"

  if [[ "${existing_count}" != "0" ]]; then
    echo "상태: Fail"
    echo "차단 사유: news legacy tables exist in clean DB: ${existing_count}"
    exit 1
  fi

  echo "상태: Pass"
  echo "검증 근거 확인: news legacy tables are absent from ${NEWS_CUTOVER_DB}"
}

self_test() {
  local script_content
  script_content="$(cat "$0")"

  for token in \
    "public.news_article_observation" \
    "public.news_signal_feature" \
    "public.news_collection_run" \
    "to_regclass(name) IS NOT NULL" \
    "HOME_NEWS_CUTOVER_DB"
  do
    if [[ "${script_content}" != *"${token}"* ]]; then
      echo "상태: Fail"
      echo "차단 사유: missing expected token: ${token}"
      exit 1
    fi
  done

  local docker_volume_remove="docker volume r""m"
  local docker_volume_prune="docker volume p""rune"
  local docker_system_prune="docker system p""rune"
  local docker_compose_down_volume="docker compose down -""v"
  local drop_database="DROP DATA""BASE"
  local truncate_table="TRUN""CATE"

  for forbidden in \
    "${docker_volume_remove}" \
    "${docker_volume_prune}" \
    "${docker_system_prune}" \
    "${docker_compose_down_volume}" \
    "${drop_database}" \
    "${truncate_table}"
  do
    if [[ "${script_content}" == *"${forbidden}"* ]]; then
      echo "상태: Fail"
      echo "차단 사유: destructive command token is forbidden: ${forbidden}"
      exit 1
    fi
  done

  echo "상태: Pass"
  echo "검증 근거 확인: news clean DB verifier is read-only"
}

case "${1:-}" in
  --verify-absent)
    verify_absent
    ;;
  --self-test)
    self_test
    ;;
  -h|--help|"")
    usage
    ;;
  *)
    echo "상태: Fail"
    echo "차단 사유: unknown option: ${1}"
    usage
    exit 1
    ;;
esac

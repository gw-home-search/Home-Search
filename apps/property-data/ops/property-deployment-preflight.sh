#!/usr/bin/env bash
set -euo pipefail

readonly SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MIGRATION_DIRECTORY="${SERVICE_ROOT}/db/migration/api"
readonly FLYWAY_IMAGE="redgate/flyway:11.7.2"
readonly POSTGRES_IMAGE="postgres:16.3-alpine"

policy_error(){ printf '거부됨: %s\n' "$1" >&2; exit 2; }
runtime_error(){ printf '실패: %s\n' "$1" >&2; exit 1; }

require_inputs(){
  [[ $# -eq 2 && ( "$1" == before || "$1" == after ) && "$2" =~ ^[1-9][0-9]*$ ]] || policy_error 'before|after와 양의 정수 target이 필요합니다.'
  [[ -n "${PROPERTY_MIGRATOR_JDBC_URL:-}" ]] || policy_error 'PROPERTY_MIGRATOR_JDBC_URL is required'
  [[ -n "${PROPERTY_MIGRATOR_DB_USERNAME:-}" ]] || policy_error 'PROPERTY_MIGRATOR_DB_USERNAME is required'
  [[ -n "${PROPERTY_MIGRATOR_DB_PASSWORD:-}" ]] || policy_error 'PROPERTY_MIGRATOR_DB_PASSWORD is required'
  local url="${PROPERTY_MIGRATOR_JDBC_URL}" url_without_query authority database query lower_query
  url_without_query="${url%%\?*}"
  authority="${url_without_query#jdbc:postgresql://}"; authority="${authority%%/*}"
  database="${url_without_query#jdbc:postgresql://${authority}/}"
  query=$([[ "${url}" == *\?* ]] && printf '%s' "${url#*\?}" || true)
  lower_query="${query,,}"
  [[ "${url_without_query}" == jdbc:postgresql://* && -n "${authority}" && "${authority}" != *@* && "${database}" == home_search ]] || policy_error 'JDBC database는 userinfo가 없는 home_search PostgreSQL이어야 합니다.'
  [[ "&${lower_query}" != *'&password='* ]] || policy_error 'JDBC URL password parameter는 허용되지 않습니다.'
  command -v docker >/dev/null 2>&1 || runtime_error 'Docker를 찾을 수 없습니다.'
}

network_args(){ [[ -n "${MIGRATION_DOCKER_NETWORK:-}" ]] && printf '%s\n' --network "${MIGRATION_DOCKER_NETWORK}"; }

run_flyway(){
  local -a network=(); while IFS= read -r value; do network+=("${value}"); done < <(network_args)
  FLYWAY_URL="${PROPERTY_MIGRATOR_JDBC_URL}" FLYWAY_USER="${PROPERTY_MIGRATOR_DB_USERNAME}" FLYWAY_PASSWORD="${PROPERTY_MIGRATOR_DB_PASSWORD}" \
    docker run --rm --platform linux/amd64 "${network[@]}" \
      -v "${MIGRATION_DIRECTORY}:/flyway/sql:ro" -v "${SERVICE_ROOT}/db:/flyway/conf:ro" \
      -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD -e REDGATE_DISABLE_TELEMETRY=true "${FLYWAY_IMAGE}" "$@"
}

run_psql(){
  local -a network=(); while IFS= read -r value; do network+=("${value}"); done < <(network_args)
  PGPASSWORD="${PROPERTY_MIGRATOR_DB_PASSWORD}" PGOPTIONS='-c default_transaction_read_only=on' \
    docker run --rm "${network[@]}" -e PGPASSWORD -e PGOPTIONS "${POSTGRES_IMAGE}" \
      psql "${PROPERTY_MIGRATOR_JDBC_URL#jdbc:}" --username "${PROPERTY_MIGRATOR_DB_USERNAME}" -X -v ON_ERROR_STOP=1 -At -c "$1"
}

catalog(){ for file in "${MIGRATION_DIRECTORY}"/V*__*.sql; do basename "${file}" | sed -E 's/^V([0-9]+)__.*/\1/'; done | sort -n; }

info_rows(){
  local json="$1" chunk version type state
  while IFS= read -r chunk || [[ -n "${chunk}" ]]; do
    version="$(printf '%s' "${chunk}" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [[ -n "${version}" ]] || continue
    type="$(printf '%s' "${chunk}" | sed -n 's/.*"type"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    state="$(printf '%s' "${chunk}" | sed -n 's/.*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    printf '%s|%s|%s\n' "${version}" "${type}" "${state}"
  done < <(printf '%s' "${json}" | tr -d '\r\n' | tr '{' '\n')
}

verify_info(){
  local phase="$1" target="$2" json="$3" expected rows versions
  [[ "$(catalog | paste -sd, -)" == '1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29' && "$(catalog | tail -1)" == "${target}" ]] || policy_error 'resolved SQL catalog 또는 target이 예상과 다릅니다.'
  expected=$([[ "${phase}" == before ]] && printf Pending || printf Success)
  [[ "$(printf '%s' "${json}" | tr -d '[:space:]')" != *'"category":"Repeatable"'* ]] || policy_error 'repeatable migration은 허용되지 않습니다.'
  rows="$(info_rows "${json}")"; versions="$(printf '%s\n' "${rows}" | cut -d'|' -f1 | paste -sd, -)"
  [[ "${versions}" == '1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29' ]] || policy_error 'resolved version set이 예상과 다릅니다.'
  while IFS='|' read -r version type state; do [[ "${type}" == SQL && "${state}" == "${expected}" ]] || policy_error "version ${version} state/type이 허용되지 않습니다."; done <<< "${rows}"
}

verify_validate(){
  run_flyway -outputType=json validate >/dev/null || runtime_error 'Flyway validate 실패'
}

main(){
  require_inputs "$@"; local phase="$1" target="$2" current history relations info rows expected
  current="$(run_psql 'SELECT current_database();')" || runtime_error 'current_database probe 실패'
  [[ "${current}" == home_search ]] || policy_error 'current_database()가 home_search와 다릅니다.'
  history="$(run_psql "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;")" || runtime_error 'history probe 실패'
  relations="$(run_psql "SELECT /* service_owned_relations */ count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','reference','batch','ai_read') AND c.relkind IN ('r','p','v','m','S','f') AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid=c.oid AND d.deptype='e');")" || runtime_error 'relation probe 실패'
  info="$(run_flyway -outputType=json info)" || runtime_error 'Flyway info 실패'
  verify_info "${phase}" "${target}" "${info}"
  if [[ "${phase}" == before ]]; then
    [[ "${history}" =~ ^(f|false)$ && "${relations}" == 0 ]] || policy_error 'fresh DB에 history 또는 service relation이 존재합니다.'
    printf 'service=property-data phase=before target=%s state=EMPTY\n' "${target}"; return
  fi
  [[ "${history}" =~ ^(t|true)$ ]] || policy_error 'Flyway history가 없습니다.'
  rows="$(run_psql "SELECT /* preflight_history_rows */ COALESCE(version,'<null>')||'|'||type||'|'||CASE WHEN success THEN 't' ELSE 'f' END FROM public.flyway_schema_history ORDER BY installed_rank;")" || runtime_error 'history query 실패'
  expected=$'<null>|SCHEMA|t\n1|SQL|t\n2|SQL|t\n4|SQL|t\n5|SQL|t\n6|SQL|t\n7|SQL|t\n8|SQL|t\n9|SQL|t\n10|SQL|t\n11|SQL|t\n12|SQL|t\n13|SQL|t\n14|SQL|t\n15|SQL|t\n16|SQL|t\n17|SQL|t\n18|SQL|t\n19|SQL|t\n20|SQL|t\n21|SQL|t\n22|SQL|t\n23|SQL|t\n24|SQL|t\n25|SQL|t\n26|SQL|t\n27|SQL|t\n28|SQL|t\n29|SQL|t'
  rows="$(printf '%s' "${rows}" | sed '/^[[:space:]]*$/d')"
  [[ "${rows}" == "${expected}" ]] || policy_error "exact SQL/Success history가 아닙니다: ${rows}"
  verify_validate
  printf 'service=property-data phase=after target=%s state=READY\n' "${target}"
}
main "$@"

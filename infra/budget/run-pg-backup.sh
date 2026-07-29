#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

readonly STAGING_ROOT="${HOME_BUDGET_BACKUP_STAGING_ROOT:-/backup-staging}"
completed=false
report_failure() {
  status="$?"
  if [[ "${completed}" != 'true' && "${status}" != '0' ]]; then
    printf '{"metric":"backup_run_failure","value":1}\n' >&2
  fi
}
trap report_failure EXIT
timestamp="${HOME_BACKUP_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
[[ "${timestamp}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || {
  echo '상태: Fail - backup timestamp 형식이 올바르지 않습니다.' >&2
  exit 2
}
[[ "${STAGING_ROOT}" == '/backup-staging' ]] || {
  echo '상태: Fail - backup staging root는 고정 경로여야 합니다.' >&2
  exit 2
}

install -d -m 0700 "${STAGING_ROOT}"
run_directory="${STAGING_ROOT}/${timestamp}"
[[ ! -e "${run_directory}" ]] || {
  echo '상태: Fail - 동일 backup run 디렉터리를 덮어쓰지 않습니다.' >&2
  exit 1
}
mkdir -m 0700 "${run_directory}"

export HOME_BACKUP_TIMESTAMP="${timestamp}"
export HOME_BACKUP_LOGICAL_DATABASES='property,admin,user,ai'
export PGSSLMODE='require'
home-search-db-backup --backup-all "${run_directory}"

artifact_count="$(find "${run_directory}" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.manifest.tsv' \) | wc -l | tr -d ' ')"
[[ "${artifact_count}" == '8' ]] || {
  echo '상태: Fail - 4개 logical DB의 dump/manifest가 모두 생성되지 않았습니다.' >&2
  exit 1
}

resolved_root="$(cd "${STAGING_ROOT}" && pwd -P)"
resolved_run="$(cd "${run_directory}" && pwd -P)"
[[ "$(dirname "${resolved_run}")" == "${resolved_root}" && "$(basename "${resolved_run}")" == "${timestamp}" ]] || {
  echo '상태: Fail - backup cleanup 대상 경계가 올바르지 않습니다.' >&2
  exit 1
}
find "${resolved_run}" -depth -delete
completed=true
printf '{"metric":"backup_run_success","value":1,"run_id":"%s"}\n' "${timestamp}"

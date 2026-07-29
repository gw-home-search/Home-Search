#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/budget/run-pg-backup.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${tmp_dir}/bin" "${tmp_dir}/staging"

cat >"${tmp_dir}/bin/home-search-db-backup" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == '--backup-all' ]]
for logical in property admin user ai; do
  printf dump >"$2/${logical}-${HOME_BACKUP_TIMESTAMP}.dump"
  printf manifest >"$2/${logical}-${HOME_BACKUP_TIMESTAMP}.manifest.tsv"
done
FAKE
chmod +x "${tmp_dir}/bin/home-search-db-backup"

patched="${tmp_dir}/run-pg-backup.sh"
sed "s|readonly STAGING_ROOT=.*|readonly STAGING_ROOT='${tmp_dir}/staging'|; s|\[\[ \"\${STAGING_ROOT}\" == '/backup-staging' \]\]|[[ \"\${STAGING_ROOT}\" == '${tmp_dir}/staging' ]]|" \
  "${script}" >"${patched}"
chmod +x "${patched}"
PATH="${tmp_dir}/bin:${PATH}" HOME_BACKUP_TIMESTAMP=20260729T010203Z "${patched}" >"${tmp_dir}/out"
[[ ! -e "${tmp_dir}/staging/20260729T010203Z" ]]
grep -Fq '"metric":"backup_run_success"' "${tmp_dir}/out"
grep -Fq "[[ \"\${STAGING_ROOT}\" == '/backup-staging' ]]" "${script}"
grep -Fq 'find "${resolved_run}" -depth -delete' "${script}"
! grep -Eq 'find .*STAGING_ROOT.*-delete|rm -rf' "${script}"

echo '상태: Pass - budget backup은 검증된 run 디렉터리만 정리합니다.'

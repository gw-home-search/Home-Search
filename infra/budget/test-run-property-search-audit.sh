#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/budget/run-property-search-audit.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir "${tmp_dir}/bin" "${tmp_dir}/s3"

versions_before="$(seq 1 40 | grep -v '^3$' | grep -v '^40$' | jq -Rsc 'split("\n")[:-1] | map(tonumber)')"
data='{"complex":{"rows":10,"identity_checksum":"a"},"complex_name_alias":{"rows":20,"identity_checksum":"b"},"parcel":{"rows":30,"identity_checksum":"c"},"trade":{"rows":40,"identity_checksum":"d"}}'
cat >"${tmp_dir}/bin/psql" <<'SH'
#!/usr/bin/env bash
cat "${FAKE_PSQL_JSON}"
SH
cat >"${tmp_dir}/bin/aws" <<'SH'
#!/usr/bin/env bash
[[ "$1 $2" == 's3 cp' ]]
source_path="$3"; destination="$4"
if [[ "${source_path}" == s3://* ]]; then
  cp "${FAKE_S3_ROOT}/before.json" "${destination}"
else
  cp "${source_path}" "${FAKE_S3_ROOT}/${AUDIT_PHASE}.json"
fi
SH
chmod +x "${tmp_dir}/bin/psql" "${tmp_dir}/bin/aws"

jq -n --argjson versions "${versions_before}" --argjson data "${data}" \
  '{status:"pass",history:[$versions[] | {version:.,type:"SQL",success:true}],data:$data}' >"${tmp_dir}/before-input.json"
PATH="${tmp_dir}/bin:${PATH}" FAKE_PSQL_JSON="${tmp_dir}/before-input.json" FAKE_S3_ROOT="${tmp_dir}/s3" AUDIT_PHASE=before \
  HOME_BACKUP_PGHOST=database HOME_BACKUP_PGPORT=5432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=password \
  HOME_BACKUP_S3_URI=s3://home-search-budget-production-backup-123456789012/logical \
  bash "${script}" before v1.0.11 >/dev/null

versions_after="$(jq '. + [40]' <<<"${versions_before}")"
jq -n --argjson versions "${versions_after}" --argjson data "${data}" \
  '{status:"pass",history:[$versions[] | {version:.,type:"SQL",success:true}],data:$data}' >"${tmp_dir}/after-input.json"
PATH="${tmp_dir}/bin:${PATH}" FAKE_PSQL_JSON="${tmp_dir}/after-input.json" FAKE_S3_ROOT="${tmp_dir}/s3" AUDIT_PHASE=after \
  HOME_BACKUP_PGHOST=database HOME_BACKUP_PGPORT=5432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=password \
  HOME_BACKUP_S3_URI=s3://home-search-budget-production-backup-123456789012/logical \
  bash "${script}" after v1.0.11 >/dev/null

rm "${tmp_dir}/s3/before.json" "${tmp_dir}/s3/after.json"
PATH="${tmp_dir}/bin:${PATH}" FAKE_PSQL_JSON="${tmp_dir}/after-input.json" FAKE_S3_ROOT="${tmp_dir}/s3" AUDIT_PHASE=before \
  HOME_BACKUP_PGHOST=database HOME_BACKUP_PGPORT=5432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=password \
  HOME_BACKUP_S3_URI=s3://home-search-budget-production-backup-123456789012/logical \
  bash "${script}" before v1.0.12 >/dev/null
PATH="${tmp_dir}/bin:${PATH}" FAKE_PSQL_JSON="${tmp_dir}/after-input.json" FAKE_S3_ROOT="${tmp_dir}/s3" AUDIT_PHASE=after \
  HOME_BACKUP_PGHOST=database HOME_BACKUP_PGPORT=5432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=password \
  HOME_BACKUP_S3_URI=s3://home-search-budget-production-backup-123456789012/logical \
  bash "${script}" after v1.0.12 >/dev/null
jq -e '.previous_version == 40 and .target_version == 40' "${tmp_dir}/s3/after.json" >/dev/null

jq '.data.trade.rows = 41' "${tmp_dir}/after-input.json" >"${tmp_dir}/changed-input.json"
if PATH="${tmp_dir}/bin:${PATH}" FAKE_PSQL_JSON="${tmp_dir}/changed-input.json" FAKE_S3_ROOT="${tmp_dir}/s3" AUDIT_PHASE=after \
  HOME_BACKUP_PGHOST=database HOME_BACKUP_PGPORT=5432 HOME_BACKUP_PGUSER=backup HOME_BACKUP_PGPASSWORD=password \
  HOME_BACKUP_S3_URI=s3://home-search-budget-production-backup-123456789012/logical \
  bash "${script}" after v1.0.11 >/dev/null 2>&1; then
  echo '상태: Fail - V40 전후 data identity 변경을 허용했습니다.' >&2
  exit 1
fi
echo '상태: Pass - V39/V40 history와 data identity audit를 확인했습니다.'

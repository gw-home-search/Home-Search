#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
fake_bin="${test_dir}/bin"
output_one="${test_dir}/output-one"
output_two="${test_dir}/output-two"
argv_log="${test_dir}/argv.log"
aws_log="${test_dir}/aws.log"
sentinel='backup-password-sentinel'

cleanup() {
  find "${test_dir}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
mkdir -p "${fake_bin}" "${output_one}" "${output_two}"

cat > "${fake_bin}/psql" <<'EOF'
#!/usr/bin/env bash
printf 'psql' >> "${FAKE_ARGV_LOG}"
printf ' %q' "$@" >> "${FAKE_ARGV_LOG}"
printf '\n' >> "${FAKE_ARGV_LOG}"
case "$*" in
  *'SHOW server_version'*) printf '16.3\n' ;;
  *'WHERE NOT success'*) printf '0\n' ;;
  *'reference.flyway_schema_history WHERE success'*) printf '4\n' ;;
  *'flyway_schema_history WHERE success'*) printf '3\n' ;;
  *'public.raw_trade_ingest'*) printf '11\n' ;;
  *'admin.admin_account'*) printf '7\n' ;;
  *'users.user_account'*) printf '5\n' ;;
  *'public.dataset_source'*) printf '13\n' ;;
  *'reference.parcel_coordinate_snapshot'*) printf '17\n' ;;
  *'public.ai_schema_history'*) printf '16\n' ;;
  *) printf '0\n' ;;
esac
EOF

cat > "${fake_bin}/pg_dump" <<'EOF'
#!/usr/bin/env bash
printf 'pg_dump' >> "${FAKE_ARGV_LOG}"
printf ' %q' "$@" >> "${FAKE_ARGV_LOG}"
printf '\n' >> "${FAKE_ARGV_LOG}"
database='unknown'
output=''
previous=''
for argument in "$@"; do
  if [[ "${previous}" == '-d' ]]; then database="${argument}"; fi
  case "${argument}" in --file=*) output="${argument#--file=}" ;; esac
  previous="${argument}"
done
printf 'deterministic custom dump fixture for %s\n' "${database}" > "${output}"
EOF

cat > "${fake_bin}/pg_restore" <<'EOF'
#!/usr/bin/env bash
printf 'pg_restore' >> "${FAKE_ARGV_LOG}"
printf ' %q' "$@" >> "${FAKE_ARGV_LOG}"
printf '\n' >> "${FAKE_ARGV_LOG}"
EOF

cat > "${fake_bin}/aws" <<'EOF'
#!/usr/bin/env bash
printf 'aws' >> "${FAKE_AWS_LOG}"
printf ' %q' "$@" >> "${FAKE_AWS_LOG}"
printf '\n' >> "${FAKE_AWS_LOG}"
if [[ "$1 $2" == 's3api head-object' ]]; then
  key=''
  previous=''
  for argument in "$@"; do
    if [[ "${previous}" == '--key' ]]; then key="${argument}"; fi
    previous="${argument}"
  done
  file="${FAKE_AWS_UPLOAD_ROOT}/${key##*/}"
  size="$(wc -c <"${file}" | tr -d ' ')"
  checksum="$(python3 - "${file}" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
  if [[ "${FAKE_AWS_CHECKSUM_MISMATCH:-false}" == 'true' ]]; then checksum='INVALID'; fi
  printf '%s\t%s\t%s\t%s\n' "${size}" "${checksum}" 'multipart-checksum-303' 'COMPOSITE'
fi
EOF
chmod +x "${fake_bin}/psql" "${fake_bin}/pg_dump" "${fake_bin}/pg_restore" "${fake_bin}/aws"

run_backup() {
  local output_dir="$1"
  local s3_uri="$2"
  PATH="${fake_bin}:${PATH}" \
  FAKE_ARGV_LOG="${argv_log}" \
  FAKE_AWS_LOG="${aws_log}" \
  FAKE_AWS_UPLOAD_ROOT="${output_dir}" \
  HOME_BACKUP_PGHOST=fake-postgres \
  HOME_BACKUP_PGPORT=5432 \
  HOME_BACKUP_PGUSER=backup_user \
  HOME_BACKUP_PGPASSWORD="${sentinel}" \
  HOME_BACKUP_TIMESTAMP=20260716T010203Z \
  HOME_BACKUP_S3_URI="${s3_uri}" \
  HOME_BACKUP_KMS_KEY_ID=fixture-kms-key \
    "${script_dir}/home-search-db-backup.sh" --backup-all "${output_dir}"
}

run_backup "${output_one}" 's3://fixture-bucket/staging' > "${test_dir}/stdout.log"
run_backup "${output_two}" '' >> "${test_dir}/stdout.log"
if run_backup "${output_one}" '' >> "${test_dir}/stdout.log" 2>&1; then
  echo '상태: Fail - 동일 timestamp의 immutable artifact를 덮어썼습니다.' >&2
  exit 1
fi

for logical in property admin user ai; do
  manifest_one="${output_one}/${logical}-20260716T010203Z.manifest.tsv"
  manifest_two="${output_two}/${logical}-20260716T010203Z.manifest.tsv"
  dump_one="${output_one}/${logical}-20260716T010203Z.dump"
  [[ -s "${dump_one}" && -s "${manifest_one}" ]]
  cmp "${manifest_one}" "${manifest_two}"
  grep -Eq '^dump_sha256[[:space:]][0-9a-f]{64}$' "${manifest_one}"
  grep -Eq '^migration_sha256[[:space:]][0-9a-f]{64}$' "${manifest_one}"
  grep -Eq '^postgres_version[[:space:]]16[.]3$' "${manifest_one}"
done

if grep -Fq "${sentinel}" "${argv_log}" || grep -Fq "${sentinel}" "${test_dir}/stdout.log"; then
  echo '상태: Fail - backup password가 argv 또는 stdout에 노출되었습니다.' >&2
  exit 1
fi
[[ "$(wc -l < "${aws_log}" | tr -d ' ')" == '16' ]]
grep -Fq 's3://fixture-bucket/staging/property-20260716T010203Z.dump' "${aws_log}"
grep -Fq 's3://fixture-bucket/staging/user-20260716T010203Z.manifest.tsv' "${aws_log}"
grep -Fq 's3://fixture-bucket/staging/ai-20260716T010203Z.manifest.tsv' "${aws_log}"
grep -Fq -- '--checksum-algorithm SHA256' "${aws_log}"
grep -Eq -- '--metadata sha256=[0-9a-f]{64}' "${aws_log}"
grep -Fq -- 's3api head-object' "${aws_log}"
if grep -Fq '/coordinate-' "${aws_log}"; then
  echo 'ERROR: default backup set must defer coordinate source data.' >&2
  exit 1
fi

echo '상태: Pass - deterministic local backup manifest, fake S3 upload, secret 비노출을 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root_dir}/infra/bootstrap/home-search-bootstrap.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

mkdir -p "${tmp_dir}/bin" "${tmp_dir}/keys"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_ARGV_LOG}"
printf '\n' >>"${FAKE_AWS_ARGV_LOG}"

secret_id=''
for ((index = 1; index <= $#; index++)); do
  if [[ "${!index}" == '--secret-id' ]]; then
    next=$((index + 1))
    secret_id="${!next}"
  fi
done

if [[ "$*" == *'put-secret-value'* ]]; then
  touch "${FAKE_AWS_STATE}/${secret_id//\//_}"
  exit 0
fi

if [[ "$*" == *'get-secret-value'* ]]; then
  case "${secret_id}" in
    arn:primary) printf '%s\n' '{"host":"primary.internal","port":5432,"username":"primary_admin","password":"MASTER_SENTINEL_PRIMARY"}' ;;
    arn:coordinate) printf '%s\n' '{"host":"coordinate.internal","port":5432,"username":"coordinate_admin","password":"MASTER_SENTINEL_COORDINATE"}' ;;
    arn:runtime)
      if [[ "${FAKE_RUNTIME_WITHOUT_AI_READER:-false}" == 'true' ]]; then
        printf '%s\n' '{"property_runtime":"RUNTIME_SENTINEL_PROPERTY","admin_runtime":"RUNTIME_SENTINEL_ADMIN","user_runtime":"RUNTIME_SENTINEL_USER","coordinate_reader":"RUNTIME_SENTINEL_COORDINATE"}'
      else
        printf '%s\n' '{"property_runtime":"RUNTIME_SENTINEL_PROPERTY","property_ai_reader":"RUNTIME_SENTINEL_AI_READER","admin_runtime":"RUNTIME_SENTINEL_ADMIN","user_runtime":"RUNTIME_SENTINEL_USER","coordinate_reader":"RUNTIME_SENTINEL_COORDINATE"}'
      fi
      ;;
    arn:bootstrap) printf '%s\n' '{"property_migrator":"BOOTSTRAP_SENTINEL_PROPERTY","admin_migrator":"BOOTSTRAP_SENTINEL_ADMIN","user_migrator":"BOOTSTRAP_SENTINEL_USER","coordinate_migrator":"BOOTSTRAP_SENTINEL_COORDINATE","coordinate_importer":"BOOTSTRAP_SENTINEL_IMPORTER","backup":"BOOTSTRAP_SENTINEL_BACKUP"}' ;;
    *)
      [[ -f "${FAKE_AWS_STATE}/${secret_id//\//_}" ]] && printf '%s\n' '{}' && exit 0
      exit 254
      ;;
  esac
  exit 0
fi

exit 2
FAKE_AWS

cat >"${tmp_dir}/bin/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_DB_ARGV_LOG}"
printf '\n' >>"${FAKE_DB_ARGV_LOG}"
if [[ "$*" == *'SELECT 1 FROM pg_database'* ]]; then
  printf '1\n'
fi
exit 0
FAKE_PSQL

cat >"${tmp_dir}/bin/createdb" <<'FAKE_CREATEDB'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_DB_ARGV_LOG}"
printf '\n' >>"${FAKE_DB_ARGV_LOG}"
FAKE_CREATEDB
chmod +x "${tmp_dir}/bin/aws" "${tmp_dir}/bin/psql" "${tmp_dir}/bin/createdb"

export PATH="${tmp_dir}/bin:${PATH}"
export FAKE_AWS_ARGV_LOG="${tmp_dir}/aws.argv"
export FAKE_AWS_STATE="${tmp_dir}/aws-state"
export FAKE_DB_ARGV_LOG="${tmp_dir}/db.argv"
mkdir -p "${FAKE_AWS_STATE}"
: >"${FAKE_AWS_ARGV_LOG}"
: >"${FAKE_DB_ARGV_LOG}"

DATABASE_RUNTIME_SECRET_ARN=arn:new-runtime \
DATABASE_BOOTSTRAP_SECRET_ARN=arn:new-bootstrap \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
  "${script}" secret-bootstrap >"${tmp_dir}/secret.out" 2>"${tmp_dir}/secret.err"

first_put_count="$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")"
[[ "${first_put_count}" == '4' ]]
DATABASE_RUNTIME_SECRET_ARN=arn:new-runtime \
DATABASE_BOOTSTRAP_SECRET_ARN=arn:new-bootstrap \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
  "${script}" secret-bootstrap >>"${tmp_dir}/secret.out" 2>>"${tmp_dir}/secret.err"
[[ "$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")" == '4' ]]
grep -q 'file://' "${FAKE_AWS_ARGV_LOG}"
! grep -Eq 'PRIVATE KEY|private_key_pem|[[:xdigit:]]{64}' "${FAKE_AWS_ARGV_LOG}" "${tmp_dir}/secret.out" "${tmp_dir}/secret.err"

set +e
FAKE_RUNTIME_WITHOUT_AI_READER=true \
PRIMARY_RDS_SECRET_ARN=arn:primary \
COORDINATE_RDS_SECRET_ARN=arn:coordinate \
DATABASE_RUNTIME_SECRET_ARN=arn:runtime \
DATABASE_BOOTSTRAP_SECRET_ARN=arn:bootstrap \
  "${script}" db-bootstrap >"${tmp_dir}/missing-ai.out" 2>"${tmp_dir}/missing-ai.err"
missing_ai_code=$?
set -e
[[ "${missing_ai_code}" == '1' ]]
grep -Fq 'runtime secret에 property_ai_reader 설정이 필요합니다.' "${tmp_dir}/missing-ai.err"
grep -Fq "membership.member = 'home_search_ai_reader'::regrole" "${script}"
grep -Fq 'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC' "${script}"
grep -Fq 'REVOKE TEMPORARY ON DATABASE home_search FROM PUBLIC' "${script}"

PRIMARY_RDS_SECRET_ARN=arn:primary \
COORDINATE_RDS_SECRET_ARN=arn:coordinate \
DATABASE_RUNTIME_SECRET_ARN=arn:runtime \
DATABASE_BOOTSTRAP_SECRET_ARN=arn:bootstrap \
  "${script}" db-bootstrap >"${tmp_dir}/db.out" 2>"${tmp_dir}/db.err"
! grep -Eq 'SENTINEL|password' "${FAKE_AWS_ARGV_LOG}" "${FAKE_DB_ARGV_LOG}" "${tmp_dir}/db.out" "${tmp_dir}/db.err"

PRIMARY_RDS_SECRET_ARN=arn:primary DATABASE_BOOTSTRAP_SECRET_ARN=arn:bootstrap \
  "${script}" runtime-grants >"${tmp_dir}/grants.out" 2>"${tmp_dir}/grants.err"
! grep -Eq 'SENTINEL|password' "${FAKE_AWS_ARGV_LOG}" "${FAKE_DB_ARGV_LOG}" "${tmp_dir}/grants.out" "${tmp_dir}/grants.err"

PRIVATE_KEY_PEM='PRIVATE_SENTINEL' PUBLIC_KEY_PEM='PUBLIC_SENTINEL' \
KEY_OUTPUT_DIRECTORY="${tmp_dir}/keys" "${script}" materialize-keys
[[ "$(stat -c '%a' "${tmp_dir}/keys/private.pem" 2>/dev/null || stat -f '%Lp' "${tmp_dir}/keys/private.pem")" == '600' ]]
[[ "$(cat "${tmp_dir}/keys/private.pem")" == 'PRIVATE_SENTINEL' ]]
[[ "$(cat "${tmp_dir}/keys/public.pem")" == 'PUBLIC_SENTINEL' ]]

echo '상태: Pass - secret idempotency, argv/stdout 비노출, DB bootstrap 및 key materialization을 확인했습니다.'

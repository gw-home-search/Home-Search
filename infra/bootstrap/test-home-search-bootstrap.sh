#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root_dir}/infra/bootstrap/home-search-bootstrap.sh"
tmp_dir="$(mktemp -d)"
cleanup() {
  local status=$?
  if [[ "${status}" != '0' && -s "${tmp_dir}/secret.err" ]]; then
    sed -n '1,120p' "${tmp_dir}/secret.err" >&2
  fi
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
  return "${status}"
}
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
  secret_string=''
  for ((index = 1; index <= $#; index++)); do
    if [[ "${!index}" == '--secret-string' ]]; then
      next=$((index + 1))
      secret_string="${!next}"
    fi
  done
  [[ "${secret_string}" == file://* ]]
  cp "${secret_string#file://}" "${FAKE_AWS_STATE}/${secret_id//\//_}"
  exit 0
fi

if [[ "$*" == *'get-secret-value'* ]]; then
  if [[ "${FAKE_AWS_GET_DENIED_ID:-}" == "${secret_id}" ]]; then
    echo 'An error occurred (AccessDeniedException) when calling the GetSecretValue operation: denied' >&2
    exit 254
  fi
  case "${secret_id}" in
    arn:primary) printf '%s\n' '{"host":"primary.internal","port":5432,"username":"primary_admin","password":"MASTER_SENTINEL_PRIMARY"}' ;;
    arn:coordinate) printf '%s\n' '{"host":"coordinate.internal","port":5432,"username":"coordinate_admin","password":"MASTER_SENTINEL_COORDINATE"}' ;;
    arn:property-ai-reader)
      if [[ "${FAKE_RUNTIME_WITHOUT_AI_READER:-false}" == 'true' ]]; then
        printf '%s\n' '{}'
      else
        printf '%s\n' '{"password":"RUNTIME_SENTINEL_AI_READER"}'
      fi
      ;;
    arn:property-runtime) printf '%s\n' '{"password":"RUNTIME_SENTINEL_PROPERTY"}' ;;
    arn:admin-runtime) printf '%s\n' '{"password":"RUNTIME_SENTINEL_ADMIN"}' ;;
    arn:user-runtime) printf '%s\n' '{"password":"RUNTIME_SENTINEL_USER"}' ;;
    arn:coordinate-reader) printf '%s\n' '{"password":"RUNTIME_SENTINEL_COORDINATE"}' ;;
    arn:property-migrator) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_PROPERTY"}' ;;
    arn:admin-migrator) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_ADMIN"}' ;;
    arn:user-migrator) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_USER"}' ;;
    arn:coordinate-migrator) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_COORDINATE"}' ;;
    arn:coordinate-importer) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_IMPORTER"}' ;;
    arn:backup) printf '%s\n' '{"password":"BOOTSTRAP_SENTINEL_BACKUP"}' ;;
    *)
      [[ -f "${FAKE_AWS_STATE}/${secret_id//\//_}" ]] \
        && cat "${FAKE_AWS_STATE}/${secret_id//\//_}" \
        && exit 0
      echo 'An error occurred (ResourceNotFoundException) when calling the GetSecretValue operation: missing' >&2
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

PROPERTY_RUNTIME_DB_SECRET_ARN=arn:new-property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:new-property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:new-admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:new-user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:new-coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:new-property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:new-admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:new-user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:new-coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:new-coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:new-backup \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
ADMIN_JWT_PUBLIC_SECRET_ARN=arn:new-admin-jwt-public \
  "${script}" secret-bootstrap >"${tmp_dir}/secret.out" 2>"${tmp_dir}/secret.err"

first_put_count="$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")"
[[ "${first_put_count}" == '14' ]]
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:new-property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:new-property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:new-admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:new-user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:new-coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:new-property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:new-admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:new-user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:new-coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:new-coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:new-backup \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
ADMIN_JWT_PUBLIC_SECRET_ARN=arn:new-admin-jwt-public \
  "${script}" secret-bootstrap >>"${tmp_dir}/secret.out" 2>>"${tmp_dir}/secret.err"
[[ "$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")" == '14' ]]
grep -q 'file://' "${FAKE_AWS_ARGV_LOG}"
! grep -Eq 'PRIVATE KEY|private_key_pem|[[:xdigit:]]{64}' "${FAKE_AWS_ARGV_LOG}" "${tmp_dir}/secret.out" "${tmp_dir}/secret.err"

admin_private_state="${FAKE_AWS_STATE}/arn:new-admin-jwt"
admin_public_state="${FAKE_AWS_STATE}/arn:new-admin-jwt-public"
denied_put_count_before="$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")"
set +e
FAKE_AWS_GET_DENIED_ID=arn:new-property-runtime \
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:new-property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:new-property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:new-admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:new-user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:new-coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:new-property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:new-admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:new-user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:new-coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:new-coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:new-backup \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
ADMIN_JWT_PUBLIC_SECRET_ARN=arn:new-admin-jwt-public \
  "${script}" secret-bootstrap >>"${tmp_dir}/secret.out" 2>"${tmp_dir}/denied-get.err"
denied_get_code=$?
set -e
[[ "${denied_get_code}" == '1' ]]
[[ "$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")" == "${denied_put_count_before}" ]]
grep -Fq 'secret 조회에 실패했습니다' "${tmp_dir}/denied-get.err"

jq -er '.private_key_pem' "${admin_private_state}" \
  | openssl pkey -pubout -out "${tmp_dir}/expected-admin-public.pem" 2>/dev/null
rm "${admin_public_state}"
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:new-property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:new-property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:new-admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:new-user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:new-coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:new-property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:new-admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:new-user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:new-coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:new-coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:new-backup \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
ADMIN_JWT_PUBLIC_SECRET_ARN=arn:new-admin-jwt-public \
  "${script}" secret-bootstrap >>"${tmp_dir}/secret.out" 2>>"${tmp_dir}/secret.err"
[[ "$(grep -c 'put-secret-value' "${FAKE_AWS_ARGV_LOG}")" == '15' ]]
jq -er '.public_key_pem' "${admin_public_state}" \
  | openssl pkey -pubin -pubout -out "${tmp_dir}/actual-admin-public.pem" 2>/dev/null
cmp "${tmp_dir}/expected-admin-public.pem" "${tmp_dir}/actual-admin-public.pem"

cp "${admin_private_state}" "${tmp_dir}/saved-admin-private.json"
rm "${admin_private_state}"
set +e
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:new-property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:new-property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:new-admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:new-user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:new-coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:new-property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:new-admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:new-user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:new-coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:new-coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:new-backup \
USER_JWT_SECRET_ARN=arn:new-user-jwt \
ADMIN_JWT_SECRET_ARN=arn:new-admin-jwt \
ADMIN_JWT_PUBLIC_SECRET_ARN=arn:new-admin-jwt-public \
  "${script}" secret-bootstrap >>"${tmp_dir}/secret.out" 2>"${tmp_dir}/public-only.err"
public_only_code=$?
set -e
[[ "${public_only_code}" == '1' ]]
grep -Fq 'admin JWT public secret만 존재하여 안전하게 private key를 복구할 수 없습니다.' \
  "${tmp_dir}/public-only.err"
cp "${tmp_dir}/saved-admin-private.json" "${admin_private_state}"

set +e
FAKE_RUNTIME_WITHOUT_AI_READER=true \
PRIMARY_RDS_SECRET_ARN=arn:primary \
COORDINATE_RDS_SECRET_ARN=arn:coordinate \
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:backup \
  "${script}" db-bootstrap >"${tmp_dir}/missing-ai.out" 2>"${tmp_dir}/missing-ai.err"
missing_ai_code=$?
set -e
[[ "${missing_ai_code}" == '1' ]]
grep -Fq 'property AI reader secret에 password 설정이 필요합니다.' "${tmp_dir}/missing-ai.err"
grep -Fq "membership.member = 'home_search_ai_reader'::regrole" "${script}"
grep -Fq 'REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC' "${script}"
grep -Fq 'REVOKE TEMPORARY ON DATABASE home_search FROM PUBLIC' "${script}"

PRIMARY_RDS_SECRET_ARN=arn:primary \
COORDINATE_RDS_SECRET_ARN=arn:coordinate \
PROPERTY_RUNTIME_DB_SECRET_ARN=arn:property-runtime \
PROPERTY_AI_READER_DB_SECRET_ARN=arn:property-ai-reader \
ADMIN_RUNTIME_DB_SECRET_ARN=arn:admin-runtime \
USER_RUNTIME_DB_SECRET_ARN=arn:user-runtime \
COORDINATE_READER_DB_SECRET_ARN=arn:coordinate-reader \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:user-migrator \
COORDINATE_MIGRATOR_DB_SECRET_ARN=arn:coordinate-migrator \
COORDINATE_IMPORTER_DB_SECRET_ARN=arn:coordinate-importer \
BACKUP_DB_SECRET_ARN=arn:backup \
  "${script}" db-bootstrap >"${tmp_dir}/db.out" 2>"${tmp_dir}/db.err"
! grep -Eq 'SENTINEL|password' "${FAKE_AWS_ARGV_LOG}" "${FAKE_DB_ARGV_LOG}" "${tmp_dir}/db.out" "${tmp_dir}/db.err"

PRIMARY_DB_HOST=primary.internal \
PRIMARY_DB_PORT=5432 \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:user-migrator \
  "${script}" runtime-grants >"${tmp_dir}/grants.out" 2>"${tmp_dir}/grants.err"
! grep -Eq 'SENTINEL|password' "${FAKE_AWS_ARGV_LOG}" "${FAKE_DB_ARGV_LOG}" "${tmp_dir}/grants.out" "${tmp_dir}/grants.err"

: >"${FAKE_DB_ARGV_LOG}"
PROPERTY_DB_HOST=property.production.internal PROPERTY_DB_PORT=5432 \
ADMIN_DB_HOST=admin.production.internal ADMIN_DB_PORT=5432 \
USER_DB_HOST=user.production.internal USER_DB_PORT=5432 \
PROPERTY_MIGRATOR_DB_SECRET_ARN=arn:property-migrator \
ADMIN_MIGRATOR_DB_SECRET_ARN=arn:admin-migrator \
USER_MIGRATOR_DB_SECRET_ARN=arn:user-migrator \
  "${script}" runtime-grants >"${tmp_dir}/split-grants.out" 2>"${tmp_dir}/split-grants.err"
grep -Fq -- '-h property.production.internal' "${FAKE_DB_ARGV_LOG}"
grep -Fq -- '-d home_search' "${FAKE_DB_ARGV_LOG}"
grep -Fq -- '-h admin.production.internal' "${FAKE_DB_ARGV_LOG}"
grep -Fq -- '-d home_search_admin' "${FAKE_DB_ARGV_LOG}"
grep -Fq -- '-h user.production.internal' "${FAKE_DB_ARGV_LOG}"
grep -Fq -- '-d home_search_user' "${FAKE_DB_ARGV_LOG}"
! grep -Eq 'SENTINEL|password' "${FAKE_AWS_ARGV_LOG}" "${FAKE_DB_ARGV_LOG}" "${tmp_dir}/split-grants.out" "${tmp_dir}/split-grants.err"
grep -Fq 'GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public, reference, batch TO home_search_property_runtime;' "${script}"
grep -Fq 'REVOKE DELETE ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_property_runtime;' "${script}"
grep -Fq 'GRANT DELETE ON TABLE market_news_collection_execution,' "${script}"
grep -Fq 'market_news_quality_review_snapshot,' "${script}"
! grep -Fq 'ALTER DEFAULT PRIVILEGES IN SCHEMA public, reference, batch GRANT SELECT, INSERT, UPDATE, DELETE' "${script}"

PRIVATE_KEY_PEM='PRIVATE_SENTINEL' PUBLIC_KEY_PEM='PUBLIC_SENTINEL' \
KEY_OUTPUT_DIRECTORY="${tmp_dir}/keys" "${script}" materialize-keys
[[ "$(stat -c '%a' "${tmp_dir}/keys/private.pem" 2>/dev/null || stat -f '%Lp' "${tmp_dir}/keys/private.pem")" == '600' ]]
[[ "$(cat "${tmp_dir}/keys/private.pem")" == 'PRIVATE_SENTINEL' ]]
[[ "$(cat "${tmp_dir}/keys/public.pem")" == 'PUBLIC_SENTINEL' ]]

echo '상태: Pass - secret idempotency, argv/stdout 비노출, DB bootstrap 및 key materialization을 확인했습니다.'

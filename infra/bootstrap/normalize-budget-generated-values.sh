#!/usr/bin/env bash
set -Eeuo pipefail

parameter_prefix="${BUDGET_PARAMETER_PREFIX:?BUDGET_PARAMETER_PREFIX is required}"
[[ "${parameter_prefix}" == '/home-search/budget-production' ]]
db_host="${BUDGET_DB_HOST:-172.31.255.1}"
db_port="${BUDGET_DB_PORT:-15432}"

tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

random_suffixes=(
  postgres/superuser-password
  postgres/property-runtime-password
  postgres/property-migrator-password
  postgres/property-importer-password
  postgres/property-ai-reader-password
  postgres/user-runtime-password
  postgres/user-migrator-password
  postgres/admin-runtime-password
  postgres/admin-migrator-password
  postgres/ai-runtime-password
  postgres/ai-migrator-password
  postgres/ai-importer-password
  postgres/backup-password
  valkey/admin-password
  valkey/property-password
  valkey/bff-password
  edge/certificate-passphrase
)
pending_suffixes=()

read_parameter() {
  local suffix="$1" destination="$2" response="${tmp_dir}/response.json"
  aws ssm get-parameter \
    --name "${parameter_prefix}/${suffix}" \
    --with-decryption \
    --output json >"${response}"
  jq -je '.Parameter.Value | select(type == "string" and length > 0)' \
    "${response}" >"${destination}"
}

stage_normalized_random() {
  local suffix="$1" safe current clean current_file clean_file
  safe="${suffix//\//_}"
  current_file="${tmp_dir}/${safe}.current"
  clean_file="${tmp_dir}/${safe}.clean"
  read_parameter "${suffix}" "${current_file}"
  current="$(command cat "${current_file}"; printf x)"
  current="${current%x}"
  clean="${current%$'\n'}"
  if [[ "${current}" == "${clean}" ]]; then
    [[ "${clean}" =~ ^[0-9a-f]{64}$ ]]
  else
    [[ "${current}" == "${clean}"$'\n' && "${clean}" =~ ^[0-9a-f]{64}$ ]]
    pending_suffixes+=("${suffix}")
  fi
  printf '%s' "${clean}" >"${clean_file}"
}

stage_exact_value() {
  local suffix="$1" expected_file="$2" safe current_file
  safe="${suffix//\//_}"
  current_file="${tmp_dir}/${safe}.current"
  read_parameter "${suffix}" "${current_file}"
  if ! cmp -s "${current_file}" "${expected_file}"; then
    cp "${expected_file}" "${tmp_dir}/${safe}.clean"
    pending_suffixes+=("${suffix}")
  fi
}

put_staged_value() {
  local suffix="$1" safe request
  safe="${suffix//\//_}"
  request="${tmp_dir}/${safe}.request.json"
  jq -n \
    --arg name "${parameter_prefix}/${suffix}" \
    --rawfile value "${tmp_dir}/${safe}.clean" \
    '{Name:$name,Type:"SecureString",Value:$value,Overwrite:true}' >"${request}"
  aws ssm put-parameter --cli-input-json "file://${request}" >/dev/null
}

for suffix in "${random_suffixes[@]}"; do
  stage_normalized_random "${suffix}"
done

printf 'postgresql://home_search_ai_reader:%s@%s:%s/home_search?sslmode=require' \
  "$(<"${tmp_dir}/postgres_property-ai-reader-password.clean")" "${db_host}" "${db_port}" \
  >"${tmp_dir}/property-dsn.expected"
printf 'postgresql://home_search_ai_runtime:%s@%s:%s/home_search_ai?sslmode=require' \
  "$(<"${tmp_dir}/postgres_ai-runtime-password.clean")" "${db_host}" "${db_port}" \
  >"${tmp_dir}/reference-dsn.expected"
printf 'postgresql://home_search_ai_migrator:%s@%s:%s/home_search_ai?sslmode=require' \
  "$(<"${tmp_dir}/postgres_ai-migrator-password.clean")" "${db_host}" "${db_port}" \
  >"${tmp_dir}/migrator-dsn.expected"

stage_exact_value ai/property-dsn "${tmp_dir}/property-dsn.expected"
stage_exact_value ai/reference-dsn "${tmp_dir}/reference-dsn.expected"
stage_exact_value ai/migrator-dsn "${tmp_dir}/migrator-dsn.expected"

for suffix in "${pending_suffixes[@]}"; do
  put_staged_value "${suffix}"
done

printf '상태: Pass - generated budget parameter %s개를 값 노출 없이 정규화했습니다.\n' \
  "${#pending_suffixes[@]}"

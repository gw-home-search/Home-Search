#!/usr/bin/env bash
if [[ $- == *x* ]]; then
  set +x
fi
set -Eeuo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
catalog="${script_dir}/data-only-allowlist.json"
property_vars_file="${repo_root}/apps/property-data/.env"
ai_vars_file="${repo_root}/apps/ai/.env"
image_uri=''
output_dir=''
temporary_env_file=''

usage() {
  cat >&2 <<EOF
사용법: $0 --image-uri <backup ECR URI@sha256> --output <absolute new directory> [options]

Options:
  --property-vars-file <path>  기본값: apps/property-data/.env
  --ai-vars-file <path>        기본값: apps/ai/.env
EOF
  exit 2
}

reject_configuration() {
  echo "상태: Fail - $1" >&2
  exit 2
}

reject_runtime() {
  echo "상태: Fail - $1" >&2
  exit 1
}

cleanup_temporary_env() {
  if [[ -n "${temporary_env_file}" && -f "${temporary_env_file}" && ! -L "${temporary_env_file}" ]]; then
    unlink "${temporary_env_file}"
  fi
  temporary_env_file=''
}
trap cleanup_temporary_env EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

while [[ "$#" -gt 0 ]]; do
  [[ "$#" -ge 2 ]] || usage
  case "$1" in
    --image-uri)
      [[ -z "${image_uri}" ]] || reject_configuration '--image-uri는 한 번만 지정해야 합니다.'
      image_uri="$2"
      ;;
    --output)
      [[ -z "${output_dir}" ]] || reject_configuration '--output은 한 번만 지정해야 합니다.'
      output_dir="$2"
      ;;
    --property-vars-file)
      property_vars_file="$2"
      ;;
    --ai-vars-file)
      ai_vars_file="$2"
      ;;
    *) usage ;;
  esac
  shift 2
done

[[ -n "${image_uri}" && -n "${output_dir}" ]] || usage
[[ "${image_uri}" =~ ^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/backup@sha256:[0-9a-f]{64}$ ]] \
  || reject_configuration 'backup image는 서울 리전 ECR URI와 sha256 digest로 고정해야 합니다.'
[[ "${output_dir}" == /* ]] || reject_configuration '--output은 절대 경로여야 합니다.'
output_name="$(basename "${output_dir}")"
[[ "${output_name}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
  || reject_configuration '--output directory 이름이 안전하지 않습니다.'
[[ ! -e "${output_dir}" && ! -L "${output_dir}" ]] \
  || reject_configuration '--output은 아직 존재하지 않는 새 directory여야 합니다.'

require_vars_file() {
  local path="$1"
  local label="$2"
  local permissions
  [[ -f "${path}" && ! -L "${path}" ]] \
    || reject_configuration "${label} vars 파일이 없거나 일반 파일이 아닙니다."
  permissions="$(stat -c '%a' "${path}" 2>/dev/null || stat -f '%Lp' "${path}" 2>/dev/null || true)"
  [[ "${permissions}" =~ ^[0-7]{3,4}$ ]] \
    || reject_configuration "${label} vars 파일 권한을 확인할 수 없습니다."
  (( 10#${permissions} % 100 == 0 )) \
    || reject_configuration "${label} vars 파일은 group/other 권한이 없어야 합니다. chmod 600을 적용하세요."
}

read_value() {
  local path="$1"
  local key="$2"
  local required="${3:-true}"
  local count
  local value
  count="$(awk -v key="${key}" '
    /^[[:space:]]*#/ { next }
    index($0, key "=") == 1 { count += 1 }
    END { print count + 0 }
  ' "${path}")"
  if [[ "${count}" == '0' && "${required}" == 'false' ]]; then
    printf ''
    return 0
  fi
  [[ "${count}" == '1' ]] || reject_configuration "${key}는 정확히 한 번 정의해야 합니다."
  value="$(awk -v key="${key}" '
    /^[[:space:]]*#/ { next }
    index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
  ' "${path}")"
  if (( ${#value} >= 2 )); then
    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  [[ -n "${value}" ]] || reject_configuration "${key} 값이 없습니다."
  [[ "${value}" != *'replace-with'* && "${value}" != *'<'* && "${value}" != *'>'* ]] \
    || reject_configuration "${key} placeholder를 실제 설정으로 교체해야 합니다."
  printf '%s' "${value}"
}

require_vars_file "${property_vars_file}" property
require_vars_file "${ai_vars_file}" AI
command -v docker >/dev/null 2>&1 || reject_configuration 'docker 명령을 찾을 수 없습니다.'
command -v python3 >/dev/null 2>&1 || reject_configuration 'python3 명령을 찾을 수 없습니다.'
[[ "$(id -u)" != '0' ]] || reject_configuration 'root 사용자로 local export를 실행할 수 없습니다.'
[[ -f "${catalog}" && ! -L "${catalog}" ]] || reject_configuration 'data-only allowlist를 찾을 수 없습니다.'

property_password="$(read_value "${property_vars_file}" HOME_SEARCH_DB_PASSWORD false)"
if [[ -z "${property_password}" ]]; then
  property_username="$(read_value "${property_vars_file}" DB_USERNAME)"
  [[ "${property_username}" == 'home_search' ]] \
    || reject_configuration 'DB_PASSWORD fallback은 DB_USERNAME=home_search일 때만 허용합니다.'
  property_password="$(read_value "${property_vars_file}" DB_PASSWORD)"
fi
aws_access_key_id="$(read_value "${ai_vars_file}" AWS_ACCESS_KEY_ID)"
aws_secret_access_key="$(read_value "${ai_vars_file}" AWS_SECRET_ACCESS_KEY)"
aws_session_token="$(read_value "${ai_vars_file}" AWS_SESSION_TOKEN false)"
raw_bucket="$(read_value "${ai_vars_file}" HOME_AI_RAW_S3_BUCKET)"
raw_region="$(read_value "${ai_vars_file}" HOME_AI_RAW_S3_REGION)"
raw_endpoint="$(read_value "${ai_vars_file}" HOME_AI_RAW_S3_ENDPOINT)"

if ! PROPERTY_PASSWORD="${property_password}" \
  AWS_ACCESS_KEY_VALUE="${aws_access_key_id}" \
  AWS_SECRET_KEY_VALUE="${aws_secret_access_key}" \
  AWS_SESSION_TOKEN_VALUE="${aws_session_token}" \
  RAW_BUCKET="${raw_bucket}" \
  RAW_REGION="${raw_region}" \
  RAW_ENDPOINT="${raw_endpoint}" python3 - <<'PY'
import os
import re
import sys


def secret(name: str, maximum: int, *, optional: bool = False) -> bool:
    value = os.environ[name]
    if optional and value == "":
        return True
    return (
        0 < len(value) <= maximum
        and value == value.strip()
        and all(ord(character) >= 32 and ord(character) != 127 for character in value)
    )


valid = (
    secret("PROPERTY_PASSWORD", 512)
    and secret("AWS_ACCESS_KEY_VALUE", 128)
    and secret("AWS_SECRET_KEY_VALUE", 512)
    and secret("AWS_SESSION_TOKEN_VALUE", 2048, optional=True)
    and re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", os.environ["RAW_BUCKET"])
    is not None
    and os.environ["RAW_REGION"] == "ap-northeast-2"
    and os.environ["RAW_ENDPOINT"] == "http://minio:9000"
)
sys.exit(0 if valid else 1)
PY
then
  reject_configuration 'local data-only export 설정이 올바르지 않습니다.'
fi

network_lines="$(
  docker inspect \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    home-search-postgis 2>/dev/null | awk 'NF { print }'
)" || reject_runtime 'PostgreSQL local Docker network를 확인할 수 없습니다.'
network_count="$(printf '%s\n' "${network_lines}" | awk 'NF { count += 1 } END { print count + 0 }')"
[[ "${network_count}" == '1' ]] \
  || reject_runtime 'PostgreSQL container는 정확히 하나의 Docker network에 연결되어야 합니다.'
network_name="${network_lines}"

mkdir -p "$(dirname "${output_dir}")"
mkdir "${output_dir}"
chmod 700 "${output_dir}"
[[ -d "${output_dir}" && ! -L "${output_dir}" ]] \
  || reject_runtime 'evidence output directory를 안전하게 만들지 못했습니다.'

temporary_env_file="$(mktemp "${TMPDIR:-/tmp}/home-search-data-export.XXXXXX")"
chmod 600 "${temporary_env_file}"
{
  printf 'HOME_MIGRATION_PROPERTY_SOURCE_HOST=postgis\n'
  printf 'HOME_MIGRATION_PROPERTY_SOURCE_PORT=5432\n'
  printf 'HOME_MIGRATION_PROPERTY_SOURCE_DATABASE=home_search\n'
  printf 'HOME_MIGRATION_PROPERTY_SOURCE_USER=home_search\n'
  printf 'HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD=%s\n' "${property_password}"
  printf 'HOME_MIGRATION_REFERENCE_SOURCE_HOST=postgis\n'
  printf 'HOME_MIGRATION_REFERENCE_SOURCE_PORT=5432\n'
  printf 'HOME_MIGRATION_REFERENCE_SOURCE_DATABASE=home_search_ai\n'
  printf 'HOME_MIGRATION_REFERENCE_SOURCE_USER=home_search\n'
  printf 'HOME_MIGRATION_REFERENCE_SOURCE_PASSWORD=%s\n' "${property_password}"
  printf 'HOME_MIGRATION_RAW_SOURCE_BUCKET=%s\n' "${raw_bucket}"
  printf 'HOME_MIGRATION_RAW_SOURCE_REGION=%s\n' "${raw_region}"
  printf 'HOME_MIGRATION_RAW_SOURCE_ENDPOINT=%s\n' "${raw_endpoint}"
  printf 'AWS_ACCESS_KEY_ID=%s\n' "${aws_access_key_id}"
  printf 'AWS_SECRET_ACCESS_KEY=%s\n' "${aws_secret_access_key}"
  if [[ -n "${aws_session_token}" ]]; then
    printf 'AWS_SESSION_TOKEN=%s\n' "${aws_session_token}"
  fi
} >"${temporary_env_file}"
[[ "$(stat -c '%a' "${temporary_env_file}" 2>/dev/null || stat -f '%Lp' "${temporary_env_file}")" == '600' ]] \
  || reject_runtime 'temporary env file 권한이 0600이 아닙니다.'

if ! docker run --rm --pull=never \
  --platform linux/amd64 \
  --network "${network_name}" \
  --env-file "${temporary_env_file}" \
  --env HOME=/tmp \
  --env AWS_EC2_METADATA_DISABLED=true \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=256m \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" \
  --volume "${output_dir}:/evidence:rw" \
  "${image_uri}" --data-export /evidence; then
  reject_runtime 'data-only export container 실행에 실패했습니다. partial evidence는 보존합니다.'
fi
cleanup_temporary_env

manifest_path="${output_dir}/data-only-manifest.json"
validation="$({
  python3 - "${catalog}" "${manifest_path}" <<'PY'
import sys
from pathlib import Path


catalog_path = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])
sys.path.insert(0, str(catalog_path.parent))
import data_only_migration as migration


catalog = migration.load_catalog(catalog_path)
allowed = {migration.dataset_name(item) for item in catalog["datasets"]}
manifest = migration.validate_manifest_artifacts(
    manifest_path,
    migration.catalog_sha256(catalog_path),
    allowed,
)
expected_files = {"data-only-manifest.json"}
expected_files.update(chunk["file"] for chunk in manifest["chunks"])
expected_files.update(item["file"] for item in manifest.get("rawObjects", []))
root = manifest_path.parent
actual_files = set()
for path in root.iterdir():
    if path.is_symlink() or not path.is_file():
        raise migration.MigrationError(f"unexpected evidence entry: {path.name}")
    actual_files.add(path.name)
if actual_files != expected_files:
    raise migration.MigrationError("evidence file set does not equal the manifest")
property_count = sum(item["logicalDatabase"] == "property" for item in catalog["datasets"])
reference_count = sum(item["logicalDatabase"] == "reference" for item in catalog["datasets"])
if property_count != 46 or reference_count != 21:
    raise migration.MigrationError("data-only dataset count drift")
print(f"{property_count} {reference_count}")
PY
} 2>&1)" || reject_runtime "manifest와 artifact 검증에 실패했습니다: ${validation}"
read -r property_dataset_count reference_dataset_count <<<"${validation}"

find "${output_dir}" -type f -exec chmod 600 {} +
chmod 700 "${output_dir}"
if command -v sha256sum >/dev/null 2>&1; then
  manifest_sha256="$(sha256sum "${manifest_path}" | awk '{print $1}')"
else
  manifest_sha256="$(shasum -a 256 "${manifest_path}" | awk '{print $1}')"
fi

printf '%s\n' \
  '상태: Pass' \
  "manifest_path=${manifest_path}" \
  "manifest_sha256=${manifest_sha256}" \
  "property_dataset_count=${property_dataset_count}" \
  "reference_dataset_count=${reference_dataset_count}"

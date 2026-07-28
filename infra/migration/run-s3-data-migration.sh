#!/usr/bin/env bash
if [[ $- == *x* ]]; then
  set +x
fi
set -Eeuo pipefail
umask 077

required() {
  local name="$1"
  [[ -n "${!name:-}" ]] || { echo "상태: Fail - ${name} 설정이 필요합니다." >&2; exit 1; }
}

validate_s3_uri() {
  local value="$1"
  if [[ ! "${value}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] \
      || [[ "${value}" == *'//' ]] \
      || [[ "/${value#s3://*/}/" == *'/../'* ]] \
      || [[ "/${value#s3://*/}/" == *'/./'* ]]; then
    echo '상태: Fail - 허용되지 않은 S3 URI입니다.' >&2
    exit 1
  fi
}

for name in HOME_MIGRATION_ARTIFACT_S3_URI HOME_MIGRATION_EVIDENCE_S3_URI \
  HOME_MIGRATION_EVIDENCE_KMS_KEY_ID; do
  required "${name}"
done
validate_s3_uri "${HOME_MIGRATION_ARTIFACT_S3_URI}"
validate_s3_uri "${HOME_MIGRATION_EVIDENCE_S3_URI}"
command -v aws >/dev/null 2>&1 || { echo '상태: Fail - aws CLI가 필요합니다.' >&2; exit 1; }
command -v home-search-db-backup >/dev/null 2>&1 || { echo '상태: Fail - migration runner가 필요합니다.' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo '상태: Fail - jq가 필요합니다.' >&2; exit 1; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/home-search-data-import.XXXXXX")"
chmod 0700 "${work_dir}"
cleanup() { find "${work_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
artifact_dir="${work_dir}/artifact"
mkdir -m 0700 "${artifact_dir}"

aws s3 cp "${HOME_MIGRATION_ARTIFACT_S3_URI%/}/" "${artifact_dir}/" \
  --recursive --only-show-errors
mapfile -t manifests < <(find "${artifact_dir}" -type f -name '*data-only-manifest.json' -print)
if [[ "${#manifests[@]}" != '1' ]]; then
  echo '상태: Fail - data-only manifest는 정확히 하나여야 합니다.' >&2
  exit 1
fi
manifest="${manifests[0]}"
chmod 0600 "${manifest}"

home-search-db-backup --data-import "${manifest}"
report="${work_dir}/data-migration-reconciliation.json"
export HOME_MIGRATION_RECONCILIATION_REPORT="${report}"
home-search-db-backup --data-reconcile "${manifest}"
[[ -f "${report}" && ! -L "${report}" ]] || {
  echo '상태: Fail - reconciliation report가 생성되지 않았습니다.' >&2
  exit 1
}
chmod 0600 "${report}"
jq -e '.status == "pass"' "${report}" >/dev/null || {
  echo '상태: Fail - reconciliation 결과가 pass가 아닙니다.' >&2
  exit 1
}

aws s3 cp "${report}" \
  "${HOME_MIGRATION_EVIDENCE_S3_URI%/}/data-migration-reconciliation.json" \
  --only-show-errors --sse aws:kms --sse-kms-key-id "${HOME_MIGRATION_EVIDENCE_KMS_KEY_ID}"
echo '상태: Pass - Property+Reference data-only import와 reconciliation을 완료했습니다.'

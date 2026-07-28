#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/migration/run-s3-data-migration.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

mkdir -p "${tmp_dir}/bin" "${tmp_dir}/source"
printf '%s\n' '{"formatVersion":1}' >"${tmp_dir}/source/data-only-manifest.json"
printf '%s\n' 'chunk' >"${tmp_dir}/source/property-chunk.csv.zst"

cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_LOG}"
printf '\n' >>"${FAKE_AWS_LOG}"
if [[ "$*" == *'--recursive'* ]]; then
  destination="${4}"
  cp -R "${FAKE_S3_SOURCE}/." "${destination}/"
  exit 0
fi
[[ "$*" == *'--sse aws:kms'* && "$*" == *'--sse-kms-key-id'* ]]
cp "$3" "${FAKE_EVIDENCE_REPORT}"
FAKE_AWS

cat >"${tmp_dir}/bin/home-search-db-backup" <<'FAKE_BACKUP'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_BACKUP_LOG}"
printf '\n' >>"${FAKE_BACKUP_LOG}"
case "$1" in
  --data-import) [[ -f "$2" ]] ;;
  --data-reconcile)
    [[ -f "$2" ]]
    printf '%s\n' '{"status":"pass","markerParity":"pass"}' >"${HOME_MIGRATION_RECONCILIATION_REPORT}"
    ;;
  *) exit 2 ;;
esac
FAKE_BACKUP
chmod +x "${tmp_dir}/bin/aws" "${tmp_dir}/bin/home-search-db-backup"

export PATH="${tmp_dir}/bin:${PATH}"
export FAKE_AWS_LOG="${tmp_dir}/aws.log"
export FAKE_BACKUP_LOG="${tmp_dir}/backup.log"
export FAKE_S3_SOURCE="${tmp_dir}/source"
export FAKE_EVIDENCE_REPORT="${tmp_dir}/uploaded-reconciliation.json"
: >"${FAKE_AWS_LOG}"
: >"${FAKE_BACKUP_LOG}"
manifest_sha256="$(shasum -a 256 "${tmp_dir}/source/data-only-manifest.json" | awk '{print $1}')"

HOME_MIGRATION_ARTIFACT_S3_URI=s3://approved-artifacts/releases/migration-1 \
HOME_MIGRATION_MANIFEST_SHA256="${manifest_sha256}" \
HOME_MIGRATION_EVIDENCE_S3_URI=s3://production-audit/deployments/release-1 \
HOME_MIGRATION_EVIDENCE_KMS_KEY_ID=arn:aws:kms:ap-northeast-2:123456789012:key/evidence \
HOME_MIGRATION_PROPERTY_TARGET_PASSWORD=PROPERTY_PASSWORD_SENTINEL \
HOME_MIGRATION_REFERENCE_TARGET_PASSWORD=REFERENCE_PASSWORD_SENTINEL \
  "${script}" >"${tmp_dir}/out" 2>"${tmp_dir}/err"

[[ "$(wc -l <"${FAKE_BACKUP_LOG}" | tr -d ' ')" == '2' ]]
sed -n '1p' "${FAKE_BACKUP_LOG}" | grep -Fq -- '--data-import'
sed -n '2p' "${FAKE_BACKUP_LOG}" | grep -Fq -- '--data-reconcile'
jq -e '.status == "pass" and .markerParity == "pass"' "${FAKE_EVIDENCE_REPORT}" >/dev/null
grep -Fq 's3://production-audit/deployments/release-1/data-migration-reconciliation.json' "${FAKE_AWS_LOG}"
grep -Fq -- '--sse-kms-key-id arn:aws:kms:ap-northeast-2:123456789012:key/evidence' "${FAKE_AWS_LOG}"
! grep -Fq 'PASSWORD_SENTINEL' "${FAKE_AWS_LOG}" "${FAKE_BACKUP_LOG}" "${tmp_dir}/out" "${tmp_dir}/err"

set +e
HOME_MIGRATION_ARTIFACT_S3_URI=s3://approved-artifacts/releases/migration-1 \
HOME_MIGRATION_MANIFEST_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
HOME_MIGRATION_EVIDENCE_S3_URI=s3://production-audit/deployments/release-1 \
HOME_MIGRATION_EVIDENCE_KMS_KEY_ID=key \
  "${script}" >"${tmp_dir}/digest.out" 2>"${tmp_dir}/digest.err"
digest_code=$?
set -e
[[ "${digest_code}" == '1' ]]
grep -Fq 'reviewed input과 다릅니다' "${tmp_dir}/digest.err"

set +e
HOME_MIGRATION_ARTIFACT_S3_URI=s3://approved-artifacts/releases/../escape \
HOME_MIGRATION_MANIFEST_SHA256="${manifest_sha256}" \
HOME_MIGRATION_EVIDENCE_S3_URI=s3://production-audit/deployments/release-1 \
HOME_MIGRATION_EVIDENCE_KMS_KEY_ID=key \
  "${script}" >"${tmp_dir}/unsafe.out" 2>"${tmp_dir}/unsafe.err"
unsafe_code=$?
set -e
[[ "${unsafe_code}" == '1' ]]
grep -Fq '허용되지 않은 S3 URI' "${tmp_dir}/unsafe.err"

cp "${tmp_dir}/source/data-only-manifest.json" "${tmp_dir}/source/duplicate-data-only-manifest.json"
set +e
HOME_MIGRATION_ARTIFACT_S3_URI=s3://approved-artifacts/releases/migration-1 \
HOME_MIGRATION_MANIFEST_SHA256="${manifest_sha256}" \
HOME_MIGRATION_EVIDENCE_S3_URI=s3://production-audit/deployments/release-1 \
HOME_MIGRATION_EVIDENCE_KMS_KEY_ID=key \
  "${script}" >"${tmp_dir}/duplicate.out" 2>"${tmp_dir}/duplicate.err"
duplicate_code=$?
set -e
[[ "${duplicate_code}" == '1' ]]
grep -Fq 'manifest는 정확히 하나여야 합니다' "${tmp_dir}/duplicate.err"

echo '상태: Pass - S3 data-only import/reconciliation wrapper의 경계와 비노출을 확인했습니다.'

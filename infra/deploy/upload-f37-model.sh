#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

artifact_dir="${1:?local F37 artifact directory is required}"
bucket="${2:?existing private backup bucket is required}"
manifest="${3:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/f37-model-manifest.json}"
version=deployment__F37_monthly_anchor_prev3_rolling_huber_010
prefix="models/f37/${version}"

[[ -d "${artifact_dir}" && ! -L "${artifact_dir}" ]]
[[ -f "${manifest}" && ! -L "${manifest}" ]]
[[ "${bucket}" =~ ^home-search-budget-production-backup-[0-9]{12}$ ]]
expected_names='["_SUCCESS","eval_metrics.csv","feature_schema.json","keras_model.keras","metadata.json","numeric_medians.json","sample_input.json"]'
jq -e --arg version "${version}" --argjson names "${expected_names}" '
  .model_version == $version
  and (.files | keys | sort) == ($names | sort)
  and all(.files[]; test("^[0-9a-f]{64}$"))
' "${manifest}" >/dev/null

actual_names="$(find "${artifact_dir}" -mindepth 1 -maxdepth 1 -print | sed 's#^.*/##' | LC_ALL=C sort | jq -Rsc 'split("\n")[:-1]')"
jq -e --argjson actual "${actual_names}" --argjson expected "${expected_names}" '$actual == ($expected | sort)' <<<null >/dev/null
for name in $(jq -r '.[]' <<<"${actual_names}"); do
  path="${artifact_dir}/${name}"
  [[ -f "${path}" && ! -L "${path}" ]]
  expected="$(jq -er --arg name "${name}" '.files[$name]' "${manifest}")"
  actual="$(if command -v sha256sum >/dev/null 2>&1; then sha256sum "${path}" | awk '{print $1}'; else shasum -a 256 "${path}" | awk '{print $1}'; fi)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "상태: Fail - F37 checksum 불일치: ${name}" >&2
    exit 1
  }
done

upload_immutable() {
  local source="$1" key="$2" checksum="$3" head metadata encryption
  if head="$(aws s3api head-object --bucket "${bucket}" --key "${key}" --output json 2>/dev/null)"; then
    metadata="$(jq -er '.Metadata.sha256 // empty' <<<"${head}")"
    encryption="$(jq -er '.ServerSideEncryption // empty' <<<"${head}")"
    [[ "${metadata}" == "${checksum}" && "${encryption}" == aws:kms ]] || {
      echo "상태: Fail - 기존 F37 object를 overwrite할 수 없습니다: ${key}" >&2
      exit 1
    }
    return
  fi
  aws s3api put-object --bucket "${bucket}" --key "${key}" --body "${source}" \
    --if-none-match '*' \
    --server-side-encryption aws:kms --checksum-algorithm SHA256 \
    --metadata "sha256=${checksum}" >/dev/null
  head="$(aws s3api head-object --bucket "${bucket}" --key "${key}" --output json)"
  jq -e --arg checksum "${checksum}" '.ServerSideEncryption == "aws:kms" and .Metadata.sha256 == $checksum' <<<"${head}" >/dev/null
}

manifest_checksum="$(if command -v sha256sum >/dev/null 2>&1; then sha256sum "${manifest}" | awk '{print $1}'; else shasum -a 256 "${manifest}" | awk '{print $1}'; fi)"
upload_immutable "${manifest}" "${prefix}/manifest.json" "${manifest_checksum}"
for name in $(jq -r '.[]' <<<"${actual_names}"); do
  upload_immutable "${artifact_dir}/${name}" "${prefix}/${name}" "$(jq -er --arg name "${name}" '.files[$name]' "${manifest}")"
done

jq -n --arg version "${version}" --arg prefix "${prefix}/" --arg manifest_sha256 "${manifest_checksum}" \
  '{status:"pass",model_version:$version,s3_prefix:$prefix,manifest_sha256:$manifest_sha256,
    checks:{allowlist_exact:true,checksums_match:true,immutable_upload:true},redactions_applied:true}'

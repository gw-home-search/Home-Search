#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

manifest="${1:?manifest path is required}"
source_dir="${2:?source directory is required}"
target_dir="${3:?target directory is required}"
owner="${4:-10001:10001}"
expected_version='deployment__F37_monthly_anchor_prev3_rolling_huber_010'
expected_names='["_SUCCESS","eval_metrics.csv","feature_schema.json","keras_model.keras","metadata.json","numeric_medians.json","sample_input.json"]'

[[ -f "${manifest}" && ! -L "${manifest}" && -d "${source_dir}" && ! -L "${source_dir}" ]]
[[ "${target_dir}" == /* && "${target_dir}" != / && "${target_dir}" != /srv && "${target_dir}" != /srv/home-search ]]
jq -e --arg version "${expected_version}" --argjson names "${expected_names}" '
  .model_version == $version and (.files | type == "object")
  and ((.files | keys | sort) == $names)
  and all(.files[]; type == "string" and test("^[0-9a-f]{64}$"))
' "${manifest}" >/dev/null

actual_names="$(find "${source_dir}" -mindepth 1 -maxdepth 1 -print | while IFS= read -r path; do basename "${path}"; done | LC_ALL=C sort | jq -Rsc 'split("\n") | map(select(length > 0))')"
[[ "${actual_names}" == "${expected_names}" ]] || { echo '상태: Fail - model artifact allowlist가 일치하지 않습니다.' >&2; exit 1; }
while IFS= read -r name; do
  path="${source_dir}/${name}"
  [[ -f "${path}" && ! -L "${path}" ]] || { echo "상태: Fail - regular model file이 아닙니다: ${name}" >&2; exit 1; }
  expected="$(jq -er --arg name "${name}" '.files[$name]' "${manifest}")"
  actual="$(shasum -a 256 "${path}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || { echo "상태: Fail - model checksum 불일치: ${name}" >&2; exit 1; }
done < <(jq -r '.files | keys[]' "${manifest}")

parent="$(dirname "${target_dir}")"
mkdir -p "${parent}"
staging="$(mktemp -d "${parent}/.ml-model.staging.XXXXXX")"
previous=''
activated=false
cleanup() {
  if [[ "${activated}" != true && -n "${previous}" && -d "${previous}" && ! -e "${target_dir}" ]]; then
    mv "${previous}" "${target_dir}"
  fi
  [[ ! -d "${staging}" ]] || find "${staging}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT
while IFS= read -r name; do
  cp "${source_dir}/${name}" "${staging}/${name}"
done < <(jq -r '.files | keys[]' "${manifest}")
chown -R "${owner}" "${staging}"
chmod 0750 "${staging}"
find "${staging}" -mindepth 1 -maxdepth 1 -type f -exec chmod 0440 {} +
owner_uid="${owner%%:*}"
if [[ "${owner_uid}" == "$(id -u)" ]]; then
  [[ -r "${staging}/keras_model.keras" ]]
else
  command -v runuser >/dev/null
  runuser -u "#${owner_uid}" -- test -r "${staging}/keras_model.keras"
fi
if [[ -e "${target_dir}" ]]; then
  [[ -d "${target_dir}" && ! -L "${target_dir}" ]]
  previous="${target_dir}.previous.$(date -u +%Y%m%dT%H%M%SZ)"
  [[ ! -e "${previous}" ]]
  mv "${target_dir}" "${previous}"
fi
mv "${staging}" "${target_dir}"
activated=true
trap - EXIT
jq -n --arg model_version "${expected_version}" --arg target "${target_dir}" --slurpfile manifest "${manifest}" \
  '{status:"pass",model_version:$model_version,target:$target,files:$manifest[0].files,redactions_applied:true}'

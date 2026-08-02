#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
installer="${root}/infra/deploy/install-ml-model.sh"
manifest="${root}/infra/deploy/f37-model-manifest.json"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

[[ -x "${installer}" ]] || { echo '상태: Fail - F37 model installer가 없습니다.' >&2; exit 1; }
jq -e '.model_version == "deployment__F37_monthly_anchor_prev3_rolling_huber_010"
  and (.files | keys | sort) == ["_SUCCESS","eval_metrics.csv","feature_schema.json","keras_model.keras","metadata.json","numeric_medians.json","sample_input.json"]
  and all(.files[]; test("^[0-9a-f]{64}$"))' "${manifest}" >/dev/null

source_dir="${tmp_dir}/source"
install_root="${tmp_dir}/runtime"
mkdir "${source_dir}" "${install_root}"
for name in _SUCCESS eval_metrics.csv feature_schema.json keras_model.keras metadata.json numeric_medians.json sample_input.json; do
  printf '%s' "fixture-${name}" >"${source_dir}/${name}"
done
jq -n --arg version 'deployment__F37_monthly_anchor_prev3_rolling_huber_010' \
  --arg dir "${source_dir}" '{model_version:$version,files:(["_SUCCESS","eval_metrics.csv","feature_schema.json","keras_model.keras","metadata.json","numeric_medians.json","sample_input.json"] | map({key:.,value:(input_filename)}) | from_entries)}' \
  >/dev/null 2>&1 || true

fixture_manifest="${tmp_dir}/fixture-manifest.json"
files='{}'
for path in "${source_dir}"/* "${source_dir}/_SUCCESS"; do
  [[ -f "${path}" ]] || continue
  name="${path##*/}"
  hash="$(shasum -a 256 "${path}" | awk '{print $1}')"
  files="$(jq --arg name "${name}" --arg hash "${hash}" '. + {($name):$hash}' <<<"${files}")"
done
jq -n --arg version 'deployment__F37_monthly_anchor_prev3_rolling_huber_010' --argjson files "${files}" \
  '{model_version:$version,files:$files}' >"${fixture_manifest}"

bash "${installer}" "${fixture_manifest}" "${source_dir}" "${install_root}/ml-model" "$(id -u):$(id -g)"
[[ -f "${install_root}/ml-model/keras_model.keras" && ! -L "${install_root}/ml-model" ]]

printf 'unexpected' >"${source_dir}/extra.bin"
if bash "${installer}" "${fixture_manifest}" "${source_dir}" "${install_root}/extra-target" "$(id -u):$(id -g)" >/dev/null 2>&1; then
  echo '상태: Fail - allowlist 밖 추가 파일을 허용했습니다.' >&2; exit 1
fi
unlink "${source_dir}/extra.bin"
ln -s metadata.json "${source_dir}/link.json"
if bash "${installer}" "${fixture_manifest}" "${source_dir}" "${install_root}/link-target" "$(id -u):$(id -g)" >/dev/null 2>&1; then
  echo '상태: Fail - symlink artifact를 허용했습니다.' >&2; exit 1
fi
unlink "${source_dir}/link.json"
printf 'tampered' >>"${source_dir}/metadata.json"
if bash "${installer}" "${fixture_manifest}" "${source_dir}" "${install_root}/checksum-target" "$(id -u):$(id -g)" >/dev/null 2>&1; then
  echo '상태: Fail - checksum 불일치를 허용했습니다.' >&2; exit 1
fi
echo '상태: Pass - F37 allowlist/checksum/symlink/atomic install 계약을 확인했습니다.'

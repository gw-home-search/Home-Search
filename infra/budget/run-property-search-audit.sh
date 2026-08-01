#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

phase="${1:?before|after is required}"
release_tag="${2:?release tag is required}"
[[ "${phase}" == before || "${phase}" == after ]]
[[ "${release_tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ ]]
for name in HOME_BACKUP_PGHOST HOME_BACKUP_PGPORT HOME_BACKUP_PGUSER HOME_BACKUP_PGPASSWORD HOME_BACKUP_S3_URI; do
  [[ -n "${!name:-}" ]] || { echo "상태: Fail - ${name} 설정이 필요합니다." >&2; exit 1; }
done
[[ "${HOME_BACKUP_S3_URI}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/logical$ ]]

tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
snapshot="${tmp_dir}/${phase}.json"
versions_through_39='[1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39]'
versions_through_40="$(jq '. + [40]' <<<"${versions_through_39}")"

PGSSLMODE=require PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=180s' \
  PGPASSWORD="${HOME_BACKUP_PGPASSWORD}" psql -X -Atq -v ON_ERROR_STOP=1 \
    -h "${HOME_BACKUP_PGHOST}" -p "${HOME_BACKUP_PGPORT}" \
    -U "${HOME_BACKUP_PGUSER}" -d home_search >"${snapshot}" <<'SQL'
SELECT json_build_object(
  'status', 'pass',
  'history', (SELECT json_agg(json_build_object('version', version::integer, 'type', type, 'success', success) ORDER BY installed_rank)
    FROM public.flyway_schema_history WHERE version IS NOT NULL),
  'data', json_build_object(
    'complex', json_build_object('rows', (SELECT count(*) FROM public.complex), 'identity_checksum', (SELECT md5(coalesce(string_agg(concat_ws('|', id, complex_pk, apt_seq), E'\n' ORDER BY id), '')) FROM public.complex)),
    'complex_name_alias', json_build_object('rows', (SELECT count(*) FROM public.complex_name_alias), 'identity_checksum', (SELECT md5(coalesce(string_agg(concat_ws('|', id, complex_id, source, source_key), E'\n' ORDER BY id), '')) FROM public.complex_name_alias)),
    'parcel', json_build_object('rows', (SELECT count(*) FROM public.parcel), 'identity_checksum', (SELECT md5(coalesce(string_agg(concat_ws('|', id, pnu), E'\n' ORDER BY id), '')) FROM public.parcel)),
    'trade', json_build_object('rows', (SELECT count(*) FROM public.trade), 'identity_checksum', (SELECT coalesce(bit_xor(hashtextextended(concat_ws('|', id, source, source_key, complex_pk, apt_seq), 0))::text, '0') FROM public.trade))
  )
);
SQL

jq -e --arg phase "${phase}" --argjson v39 "${versions_through_39}" --argjson v40 "${versions_through_40}" '
  .status == "pass"
  and (if $phase == "before" then ([.history[].version] == $v39 or [.history[].version] == $v40)
       else [.history[].version] == $v40 end)
  and all(.history[]; .type == "SQL" and .success == true)
  and (.data | keys | sort) == ["complex","complex_name_alias","parcel","trade"]
  and all(.data[]; (.rows | type == "number") and (.identity_checksum | type == "string" and length > 0))
' "${snapshot}" >/dev/null || {
  echo "상태: Fail - property search audit ${phase} history/data snapshot이 exact 조건과 다릅니다." >&2
  exit 1
}

prefix="${HOME_BACKUP_S3_URI}/rollout-audit/${release_tag}"
if [[ "${phase}" == after ]]; then
  aws s3 cp "${prefix}/before.json" "${tmp_dir}/before.json" --only-show-errors
  jq -e --slurp '.[0].data == .[1].data' "${tmp_dir}/before.json" "${snapshot}" >/dev/null || {
    echo '상태: Fail - V40 전후 row count 또는 식별자 checksum이 변경됐습니다.' >&2
    exit 1
  }
  previous_version="$(jq -er '.previous_version | select(. == 39 or . == 40)' "${tmp_dir}/before.json")"
else
  previous_version="$(jq -er '.history[-1].version | select(. == 39 or . == 40)' "${snapshot}")"
fi
enriched="${tmp_dir}/${phase}-enriched.json"
jq --arg phase "${phase}" --argjson previous_version "${previous_version}" '
  . + {phase:$phase,previous_version:$previous_version,target_version:40,failed:0,missing:0,out_of_order:0}
' "${snapshot}" >"${enriched}"
mv "${enriched}" "${snapshot}"
aws s3 cp "${snapshot}" "${prefix}/${phase}.json" --sse aws:kms --sse-kms-key-id alias/aws/s3 --only-show-errors
echo "상태: Pass - property V${previous_version}→V40 ${phase} row count, 식별자 checksum, Flyway history를 확인했습니다."

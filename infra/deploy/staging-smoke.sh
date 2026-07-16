#!/usr/bin/env bash
set -Eeuo pipefail

public_origin="${1:?public origin is required}"
admin_origin="${2:?admin origin is required}"
output="${3:?evidence output is required}"
[[ "${public_origin}" =~ ^https://[^/]+$ && "${admin_origin}" =~ ^https://[^/]+$ ]]

status() { curl --fail-with-body --silent --show-error --output "$2" --write-out '%{http_code}' "$1"; }
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

[[ "$(status "${public_origin}/" "${tmp_dir}/public.html")" == '200' ]]
[[ "$(status "${admin_origin}/" "${tmp_dir}/admin.html")" == '200' ]]

public_actuator="$(curl --silent --output /dev/null --write-out '%{http_code}' "${public_origin}/actuator/health")"
admin_actuator="$(curl --silent --output /dev/null --write-out '%{http_code}' "${admin_origin}/actuator/health")"
public_admin="$(curl --silent --output /dev/null --write-out '%{http_code}' "${public_origin}/api/v1/admin/coordinates/pending/summary")"
[[ "${public_actuator}" == '404' && "${admin_actuator}" == '404' && "${public_admin}" == '404' ]]

search_status="$(curl --silent --show-error --output "${tmp_dir}/search.json" --write-out '%{http_code}' \
  "${public_origin}/api/v1/search/complexes?q=staging-smoke-no-match")"
[[ "${search_status}" == '200' ]]
jq -e 'type == "array"' "${tmp_dir}/search.json" >/dev/null

validation_status="$(curl --silent --show-error --output "${tmp_dir}/validation.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --request POST --data '{}' "${public_origin}/api/v1/map/complexes")"
[[ "${validation_status}" == '400' ]]
jq -e '.title == "C401" and .status == 400 and .detail == "Invalid parameter format." and has("timestamp")' \
  "${tmp_dir}/validation.json" >/dev/null

admin_api_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${admin_origin}/api/v1/admin/coordinates/pending/summary")"
[[ "${admin_api_status}" =~ ^(200|302|401|403)$ ]]

jq -n --arg verified_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg public_root '200' --arg admin_root '200' --arg actuator '404' --arg c401 '400' --arg admin_api "${admin_api_status}" \
  '{status:"pass",verified_at:$verified_at,public_root:$public_root,admin_root:$admin_root,actuator_block:$actuator,generic_c401:$c401,admin_api_boundary:$admin_api}' >"${output}"
echo '상태: Pass - public/admin route, C401 contract, actuator 차단, admin 인증 경계를 확인했습니다.'

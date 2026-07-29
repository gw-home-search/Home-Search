#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/verify-budget-production-plan.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_ecr_repository.platform[\"budget-postgres\"]",type:"aws_ecr_repository",change:{actions:["create"],after:{name:"home-search/budget-postgres"}}},
  {mode:"managed",address:"aws_ecr_lifecycle_policy.platform[\"budget-postgres\"]",type:"aws_ecr_lifecycle_policy",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/registry.json"
"${script}" "${tmp_dir}/registry.json" registry registry >/dev/null

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_instance.host[0]",type:"aws_instance",change:{actions:["create"],after:{tags_all:{Environment:"budget-production"}}}}
]}' >"${tmp_dir}/foundation.json"
"${script}" "${tmp_dir}/foundation.json" foundation registry >/dev/null

if "${script}" "${tmp_dir}/foundation.json" foundation private >/dev/null 2>&1; then
  echo '상태: Fail - phase backslide를 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].change.actions = ["delete","create"]' \
  "${tmp_dir}/foundation.json" >"${tmp_dir}/replacement.json"
if "${script}" "${tmp_dir}/replacement.json" foundation registry >/dev/null 2>&1; then
  echo '상태: Fail - replacement/delete plan을 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_nat_gateway.this",type:"aws_nat_gateway",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/forbidden.json"
if "${script}" "${tmp_dir}/forbidden.json" foundation registry >/dev/null 2>&1; then
  echo '상태: Fail - 금지된 managed service를 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_ecr_repository.platform[\"budget-postgres\"]",type:"aws_ecr_repository",change:{actions:["create"],after:{name:"home-search/budget-postgres"}}},
  {mode:"managed",address:"aws_vpc.this[0]",type:"aws_vpc",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/registry-bypass.json"
if "${script}" "${tmp_dir}/registry-bypass.json" registry registry >/dev/null 2>&1; then
  echo '상태: Fail - registry phase의 foundation 변경을 허용했습니다.' >&2
  exit 1
fi

echo '상태: Pass - budget-production phase backslide, zero-destroy, 금지 resource 검증을 확인했습니다.'

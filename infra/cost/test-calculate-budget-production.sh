#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
calculator="${root}/infra/cost/calculate-budget-production.py"
fixture="${root}/infra/cost/budget-production-price-fixture.json"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

cat >"${tmp_dir}/pass.json" <<'JSON'
{
  "resource_changes": [
    {"address":"aws_instance.host[0]","mode":"managed","type":"aws_instance","change":{"actions":["create"],"after":{"instance_type":"t3a.large"}}},
    {"address":"aws_ebs_volume.data[0]","mode":"managed","type":"aws_ebs_volume","change":{"actions":["create"],"after":{"type":"gp3","size":80}}},
    {"address":"aws_eip.public[0]","mode":"managed","type":"aws_eip","change":{"actions":["create"],"after":{}}}
  ]
}
JSON
"${calculator}" --plan "${tmp_dir}/pass.json" --fixture "${fixture}" --account-forecast-usd 98.46 >"${tmp_dir}/pass.out"
grep -Fq '"incremental_monthly_usd": "94.96"' "${tmp_dir}/pass.out"
grep -Fq '"account_total_usd": "98.46"' "${tmp_dir}/pass.out"

jq '.resource_changes += [{"address":"aws_nat_gateway.forbidden","mode":"managed","type":"aws_nat_gateway","change":{"actions":["create"],"after":{}}}]' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/forbidden.json"
if "${calculator}" --plan "${tmp_dir}/forbidden.json" --fixture "${fixture}" >"${tmp_dir}/forbidden.out"; then
  echo '상태: Fail - 금지 resource를 허용했습니다.' >&2
  exit 1
fi
grep -Fq 'forbidden:aws_nat_gateway' "${tmp_dir}/forbidden.out"

if "${calculator}" --plan "${tmp_dir}/pass.json" --fixture "${fixture}" --account-forecast-usd 99.01 >"${tmp_dir}/forecast.out"; then
  echo '상태: Fail - account forecast gate 초과를 허용했습니다.' >&2
  exit 1
fi
grep -Fq '계정 total forecast $99.01' "${tmp_dir}/forecast.out"

echo '상태: Pass - budget-production 증분 $95/account $99 및 금지 resource 비용 gate를 확인했습니다.'

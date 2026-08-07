#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/release-manifest-to-budget-tfvars.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

applications=(admin-api admin-gateway admin-migration admin-ops ai backup chat-bff ml ops-bootstrap property-api property-batch property-flyway public-gateway seo-renderer source-data-migration user-api user-flyway user-insight-worker)
platform=(budget-postgres budget-valkey)
jq -n --argjson apps "$(printf '%s\n' "${applications[@]}" | jq -Rsc 'split("\n")[:-1]')" \
  --argjson platform "$(printf '%s\n' "${platform[@]}" | jq -Rsc 'split("\n")[:-1]')" '
  def image($name): {repository:("home-search/"+$name),digest:("sha256:"+("a"*64)),uri:("123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/"+$name+"@sha256:"+("a"*64))};
  {format_version:2,tag:"v2.0.0",commit_sha:("b"*40),build_architecture:"linux/amd64",
   build_flags:{market_news_enabled:true},
   vulnerability_critical_gate_passed:true,vulnerability_policy_gate_passed:true,
   images:(reduce $apps[] as $name ({}; .[$name]=image($name))),
   platform_images:(reduce $platform[] as $name ({}; .[$name]=image($name)))}
' >"${tmp_dir}/manifest.json"

"${script}" "${tmp_dir}/manifest.json" s3://migration-bucket/data/v2 \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "${tmp_dir}/release.auto.tfvars.json"
jq -e '
  (.image_uris | length) == 18 and (.platform_image_uris | length) == 2
  and .deployment_release_tag == "v2.0.0"
  and .migration_artifact_s3_uri == "s3://migration-bucket/data/v2"
' "${tmp_dir}/release.auto.tfvars.json" >/dev/null

jq '.build_flags.market_news_enabled=false' "${tmp_dir}/manifest.json" >"${tmp_dir}/news-disabled.json"
if "${script}" "${tmp_dir}/news-disabled.json" s3://migration-bucket/data/v2 \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "${tmp_dir}/news-disabled.tfvars.json" >/dev/null 2>&1; then
  echo '상태: Fail - market news가 비활성화된 release를 허용했습니다.' >&2
  exit 1
fi

jq '.tag="v1.0.4"' "${tmp_dir}/manifest.json" >"${tmp_dir}/old.json"
if "${script}" "${tmp_dir}/old.json" s3://migration-bucket/data/v2 \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "${tmp_dir}/old.tfvars.json" >/dev/null 2>&1; then
  echo '상태: Fail - v1.0.4 budget 배포를 허용했습니다.' >&2
  exit 1
fi

echo '상태: Pass - budget release tfvars의 18+2 digest와 v1.0.4 차단을 확인했습니다.'

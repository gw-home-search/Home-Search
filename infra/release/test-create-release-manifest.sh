#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${tmp_dir}/bin"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"
PATH="${tmp_dir}/bin:${PATH}" "${root}/infra/release/create-release-manifest.sh" \
  v2.3.4 0123456789abcdef0123456789abcdef01234567 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com \
  "${tmp_dir}/manifest.json"
jq -e '
  .tag == "v2.3.4" and .commit_sha == "0123456789abcdef0123456789abcdef01234567" and
  (.images | length == 17) and (.images["property-api"].repository == "home-search/property-api") and
  (.platform_images | length == 2) and
  (.platform_images["budget-postgres"].repository == "home-search/budget-postgres") and
  (.platform_images["budget-valkey"].repository == "home-search/budget-valkey") and
  (.images["user-insight-worker"].repository == "home-search/user-insight-worker") and
  (.images["chat-bff"].repository == "home-search/chat-bff") and
  (.images.ai.repository == "home-search/ai") and
  (.images.ml.uri | endswith("@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
' "${tmp_dir}/manifest.json" >/dev/null
echo '상태: Pass - 17개 application image와 2개 platform image digest release manifest 생성을 확인했습니다.'

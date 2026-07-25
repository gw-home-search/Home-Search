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
  (.images | length == 15) and (.images["property-api"].repository == "home-search/property-api") and
  (.images["user-insight-worker"].repository == "home-search/user-insight-worker") and
  (.images.ml.uri | endswith("@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
' "${tmp_dir}/manifest.json" >/dev/null
echo '상태: Pass - 15개 image digest release manifest 생성을 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-release-eligibility.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

git init --bare "${tmp_dir}/origin.git" >/dev/null
git init -b main "${tmp_dir}/repo" >/dev/null
(
  cd "${tmp_dir}/repo"
  git config user.email test@example.invalid
  git config user.name release-test
  git remote add origin "${tmp_dir}/origin.git"
  touch evidence
  git add evidence
  git commit -m initial >/dev/null
  git tag v2.3.4
  git push origin main --tags >/dev/null 2>&1
  git fetch origin main >/dev/null 2>&1
  sha="$(git rev-parse HEAD)"

  checks=(
    changes test-display-name-policy backend-test source-data-test frontend-test-build
    infra-contract-test ml-image-test property-image-test platform-image-test edge-image-test
    image-manifest-test terraform-test admin-service-test admin-web-test-build user-service-test diff-check
  )
  printf '%s\n' "${checks[@]}" | jq -Rn \
    '{check_runs: [inputs | {name:.,status:"completed",conclusion:"success",completed_at:"2026-07-16T00:00:00Z"}]}' \
    >"${tmp_dir}/checks.json"
  "${script}" v2.3.4 "${sha}" "${tmp_dir}/checks.json" >/dev/null

  jq '(.check_runs[] | select(.name == "terraform-test")).conclusion = "failure"' \
    "${tmp_dir}/checks.json" >"${tmp_dir}/failed.json"
  if "${script}" v2.3.4 "${sha}" "${tmp_dir}/failed.json" >/dev/null 2>&1; then
    echo '상태: Fail - 실패한 quality gate를 허용했습니다.' >&2
    exit 1
  fi
  if "${script}" v2.3 "${sha}" "${tmp_dir}/checks.json" >/dev/null 2>&1; then
    echo '상태: Fail - 잘못된 release tag를 허용했습니다.' >&2
    exit 1
  fi
)

echo '상태: Pass - release tag/main/check-run eligibility self-test를 확인했습니다.'

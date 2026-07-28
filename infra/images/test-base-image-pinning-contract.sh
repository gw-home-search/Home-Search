#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

printf '%s\n' 'FROM ubuntu' >"${tmp_dir}/Dockerfile"
if BASE_IMAGE_SCAN_ROOTS="${tmp_dir}" "${root}/infra/images/test-base-image-pinning.sh" >/dev/null 2>&1; then
  echo '상태: Fail - tag가 생략된 mutable base image가 통과했습니다.' >&2
  exit 1
fi

printf '%s\n' \
  'FROM ubuntu@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa AS runtime' \
  'FROM runtime' >"${tmp_dir}/Dockerfile"
BASE_IMAGE_SCAN_ROOTS="${tmp_dir}" "${root}/infra/images/test-base-image-pinning.sh" >/dev/null

echo '상태: Pass - unqualified mutable image 차단과 internal stage 허용 계약을 확인했습니다.'

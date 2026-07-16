#!/usr/bin/env bash
set -Eeuo pipefail

tag="${1:?tag is required}"
expected_sha="${2:?commit SHA is required}"
check_runs_file="${3:?check-runs JSON is required}"

[[ "${tag}" =~ ^v(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ ]] \
  || { echo '상태: Fail - release tag는 vMAJOR.MINOR.PATCH 형식이어야 합니다.' >&2; exit 1; }
[[ "${expected_sha}" =~ ^[0-9a-f]{40}$ ]] \
  || { echo '상태: Fail - release commit SHA가 유효하지 않습니다.' >&2; exit 1; }
[[ -f "${check_runs_file}" ]] || { echo '상태: Fail - check-runs evidence가 없습니다.' >&2; exit 1; }

tag_sha="$(git rev-parse "${tag}^{commit}")"
[[ "${tag_sha}" == "${expected_sha}" ]] \
  || { echo '상태: Fail - tag와 workflow commit이 일치하지 않습니다.' >&2; exit 1; }
git merge-base --is-ancestor "${expected_sha}" origin/main \
  || { echo '상태: Fail - release commit이 origin/main에 포함되지 않았습니다.' >&2; exit 1; }

required_checks=(
  changes test-display-name-policy backend-test source-data-test frontend-test-build
  infra-contract-test ml-image-test property-image-test platform-image-test edge-image-test
  image-manifest-test terraform-test admin-service-test admin-web-test-build user-service-test diff-check
)

missing=()
for check_name in "${required_checks[@]}"; do
  conclusion="$(jq -r --arg name "${check_name}" '
    [.check_runs[] | select(.name == $name and .status == "completed")]
    | sort_by(.completed_at) | last | .conclusion // "missing"
  ' "${check_runs_file}")"
  if [[ "${conclusion}" != "success" ]]; then
    missing+=("${check_name}=${conclusion}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  printf '상태: Fail - 필수 quality gate가 성공하지 않았습니다: %s\n' "${missing[*]}" >&2
  exit 1
fi
echo '상태: Pass - main commit, SemVer tag, 필수 quality gate 성공을 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfiles=()
while IFS= read -r dockerfile; do
  dockerfiles+=("${dockerfile}")
done < <(find "${root}/apps" "${root}/infra" -type f -name 'Dockerfile*' -not -path '*/build/*' -print | sort)

failures=()
for dockerfile in "${dockerfiles[@]}"; do
  while IFS= read -r from_line; do
    image="$(awk '{ for (i = 2; i <= NF; i++) if ($i !~ /^--/) { print $i; exit } }' <<<"${from_line}")"
    if [[ "${image}" != "scratch" && "${image}" =~ [/:] && ! "${image}" =~ @sha256:[0-9a-f]{64}$ ]]; then
      failures+=("${dockerfile#"${root}/"}: ${from_line}")
    fi
  done < <(grep -E '^[[:space:]]*FROM[[:space:]]+' "${dockerfile}" || true)
done

if ((${#failures[@]} > 0)); then
  printf '상태: Fail - digest로 고정되지 않은 base image입니다.\n' >&2
  printf '%s\n' "${failures[@]}" >&2
  exit 1
fi

echo '상태: Pass - 모든 Docker base image가 sha256 digest로 고정됐습니다.'

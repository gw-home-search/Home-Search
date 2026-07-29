#!/usr/bin/env bash
set -Eeuo pipefail

terraform_dir="${1:?budget-production Terraform directory is required}"
error_file="$(mktemp)"
trap 'rm -f "${error_file}"' EXIT

set +e
state_json="$(terraform -chdir="${terraform_dir}" state pull 2>"${error_file}")"
state_status=$?
set -e

if (( state_status != 0 )); then
  if grep -Fq 'No state file was found!' "${error_file}"; then
    printf '%s\n' registry
    exit 0
  fi
  sed -n '1,120p' "${error_file}" >&2
  echo '상태: Fail - budget-production state를 읽지 못해 phase 판독을 중단합니다.' >&2
  exit "${state_status}"
fi

phase="$(
  printf '%s' "${state_json}" |
    jq -er '.outputs.deployment_phase.value | select(type == "string")' 2>/dev/null
)" || {
  echo '상태: Fail - 기존 budget-production state에 deployment_phase가 없습니다.' >&2
  exit 1
}

case "${phase}" in
  registry | foundation | data | private | public)
    printf '%s\n' "${phase}"
    ;;
  *)
    echo "상태: Fail - 알 수 없는 budget-production phase입니다: ${phase}" >&2
    exit 1
    ;;
esac

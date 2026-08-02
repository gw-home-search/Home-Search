#!/usr/bin/env bash
set -Eeuo pipefail
set +x

disable_schedule() {
  local group="$1" name="$2" current request error_file
  error_file="$(mktemp)"
  if ! current="$(aws scheduler get-schedule --group-name "${group}" --name "${name}" --output json 2>"${error_file}")"; then
    if grep -Fq ResourceNotFoundException "${error_file}"; then
      unlink "${error_file}"
      return
    fi
    cat "${error_file}" >&2
    unlink "${error_file}"
    return 1
  fi
  unlink "${error_file}"
  request="$(jq '{Name,GroupName,Description,ScheduleExpression,ScheduleExpressionTimezone,
    StartDate,EndDate,KmsKeyArn,FlexibleTimeWindow,Target,ActionAfterCompletion} | with_entries(select(.value != null))
    | .State="DISABLED"' <<<"${current}")"
  aws scheduler update-schedule --cli-input-json "${request}" >/dev/null
}

for name in general morning major-selection retention; do
  disable_schedule home-search-budget-production-market-news "home-search-budget-production-market-news-${name}"
done
disable_schedule home-search-budget-production-data-refresh home-search-budget-production-rtms-daily-refresh
echo '상태: Pass - market-news와 RTMS schedule을 application rollback 전에 disabled로 전환했습니다.'

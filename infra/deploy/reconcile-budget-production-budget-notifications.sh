#!/usr/bin/env bash
set -Eeuo pipefail

account_id="${1:?AWS account ID is required}"
budget_name="${2:?AWS Budget name is required}"
alarm_email="${3:?AWS Budget alarm email is required}"
budgets_region='us-east-1'

fail() {
  printf '상태: Fail - %s\n' "$1" >&2
  exit 1
}

[[ "${account_id}" =~ ^[0-9]{12}$ ]] || fail 'AWS account ID 형식이 올바르지 않습니다.'
[[ "${budget_name}" == 'home-search-budget-production-monthly' ]] || fail '허용되지 않은 AWS Budget 이름입니다.'
[[ "${alarm_email}" =~ ^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$ ]] \
  || fail 'AWS Budget alarm email 형식이 올바르지 않습니다.'
command -v aws >/dev/null || fail 'aws CLI가 필요합니다.'
command -v jq >/dev/null || fail 'jq가 필요합니다.'

desired_notifications='[
  {"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"ABSOLUTE_VALUE"},
  {"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":80,"ThresholdType":"ABSOLUTE_VALUE"},
  {"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":100,"ThresholdType":"ABSOLUTE_VALUE"}
]'
desired_subscribers="$(jq -cn --arg email "${alarm_email}" \
  '[{SubscriptionType:"EMAIL",Address:$email}]')"

normalize_notifications() {
  jq -ce '
    .Notifications
    | map({
        NotificationType,
        ComparisonOperator,
        Threshold:(.Threshold | tonumber),
        ThresholdType
      })
    | sort_by(.NotificationType, .Threshold)
  '
}

describe_notifications() {
  aws budgets describe-notifications-for-budget \
    --region "${budgets_region}" \
    --account-id "${account_id}" \
    --budget-name "${budget_name}" \
    --output json
}

verify_subscriber() {
  local notification="$1" subscribers
  subscribers="$(aws budgets describe-subscribers-for-notification \
    --region "${budgets_region}" \
    --account-id "${account_id}" \
    --budget-name "${budget_name}" \
    --notification "${notification}" \
    --output json)" \
    || fail 'AWS Budget subscriber 조회에 실패했습니다.'
  jq -e --arg email "${alarm_email}" '
    .Subscribers
    | length == 1
      and .[0].SubscriptionType == "EMAIL"
      and .[0].Address == $email
  ' <<<"${subscribers}" >/dev/null \
    || fail 'AWS Budget subscriber가 승인된 email exact 1개와 일치하지 않습니다.'
}

desired_normalized="$(jq -c 'sort_by(.NotificationType, .Threshold)' <<<"${desired_notifications}")"
live_response="$(describe_notifications)" \
  || fail 'AWS Budget 알림 조회에 실패했습니다.'
live_normalized="$(normalize_notifications <<<"${live_response}")" \
  || fail 'AWS Budget 알림 응답 형식이 올바르지 않습니다.'

if [[ "$(jq 'length' <<<"${live_normalized}")" != "$(jq 'unique | length' <<<"${live_normalized}")" ]]; then
  fail '중복 AWS Budget 알림이 있어 자동 보정하지 않습니다.'
fi

unexpected="$(jq -cn --argjson live "${live_normalized}" --argjson desired "${desired_normalized}" \
  '$live - $desired')"
[[ "$(jq 'length' <<<"${unexpected}")" == 0 ]] \
  || fail '예상하지 않은 AWS Budget 알림이 있어 자동 보정하지 않습니다.'

while IFS= read -r notification; do
  verify_subscriber "${notification}"
done < <(jq -c '.[]' <<<"${live_normalized}")

missing="$(jq -cn --argjson live "${live_normalized}" --argjson desired "${desired_normalized}" \
  '$desired - $live')"
while IFS= read -r notification; do
  [[ -n "${notification}" ]] || continue
  aws budgets create-notification \
    --region "${budgets_region}" \
    --account-id "${account_id}" \
    --budget-name "${budget_name}" \
    --notification "${notification}" \
    --subscribers "${desired_subscribers}" \
    >/dev/null \
    || fail '누락 AWS Budget 알림 생성에 실패했습니다.'
done < <(jq -c '.[]' <<<"${missing}")

live_response="$(describe_notifications)" \
  || fail '보정 후 AWS Budget 알림 조회에 실패했습니다.'
live_normalized="$(normalize_notifications <<<"${live_response}")" \
  || fail '보정 후 AWS Budget 알림 응답 형식이 올바르지 않습니다.'
jq -en --argjson live "${live_normalized}" --argjson desired "${desired_normalized}" \
  '$live == $desired' >/dev/null \
  || fail '보정 후 AWS Budget 알림이 exact 3개와 일치하지 않습니다.'

while IFS= read -r notification; do
  verify_subscriber "${notification}"
done < <(jq -c '.[]' <<<"${desired_normalized}")

printf '상태: Pass - AWS Budget 알림 exact 3개와 email subscriber를 확인했습니다.\n'

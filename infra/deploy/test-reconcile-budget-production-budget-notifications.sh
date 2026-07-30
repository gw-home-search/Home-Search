#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/reconcile-budget-production-budget-notifications.sh"
temp_dir="$(mktemp -d)"
cleanup() { find "${temp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

mkdir -p "${temp_dir}/bin"

cat >"${temp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail

service="${1:?}"
operation="${2:?}"
shift 2
[[ "${service}" == budgets ]]

notification=''
subscribers=''
while (($#)); do
  case "$1" in
    --notification) notification="$2"; shift 2 ;;
    --subscribers) subscribers="$2"; shift 2 ;;
    *) shift ;;
  esac
done

case "${operation}" in
  describe-notifications-for-budget)
    cat "${BUDGET_NOTIFICATIONS_STATE}"
    ;;
  create-notification)
    jq -e '
      .NotificationType as $type
      | .ComparisonOperator == "GREATER_THAN"
      and .ThresholdType == "ABSOLUTE_VALUE"
      and (($type == "ACTUAL" and .Threshold == 50)
        or ($type == "FORECASTED" and (.Threshold == 80 or .Threshold == 100)))
    ' <<<"${notification}" >/dev/null
    jq -e --arg email "${EXPECTED_ALARM_EMAIL}" '
      length == 1 and .[0] == {SubscriptionType:"EMAIL",Address:$email}
    ' <<<"${subscribers}" >/dev/null
    jq --argjson notification "${notification}" \
      '.Notifications += [$notification]' "${BUDGET_NOTIFICATIONS_STATE}" \
      >"${BUDGET_NOTIFICATIONS_STATE}.next"
    mv "${BUDGET_NOTIFICATIONS_STATE}.next" "${BUDGET_NOTIFICATIONS_STATE}"
    jq -c '{operation:"create-notification",notification:.}' <<<"${notification}" \
      >>"${BUDGET_NOTIFICATIONS_CALLS}"
    ;;
  describe-subscribers-for-notification)
    if [[ "${TEST_SCENARIO}" == wrong-subscriber ]]; then
      printf '%s\n' '{"Subscribers":[{"SubscriptionType":"EMAIL","Address":"other@example.com"}]}'
    else
      jq -cn --arg email "${EXPECTED_ALARM_EMAIL}" \
        '{Subscribers:[{SubscriptionType:"EMAIL",Address:$email}]}'
    fi
    ;;
  *)
    echo "unexpected aws call: ${service} ${operation}" >&2
    exit 64
    ;;
esac
FAKE_AWS
chmod +x "${temp_dir}/bin/aws"

desired='{
  "Notifications": [
    {"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"ABSOLUTE_VALUE"},
    {"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":80,"ThresholdType":"ABSOLUTE_VALUE"},
    {"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":100,"ThresholdType":"ABSOLUTE_VALUE"}
  ]
}'

run_reconcile() {
  local scenario="$1"
  TEST_SCENARIO="${scenario}" \
  EXPECTED_ALARM_EMAIL='alerts@example.com' \
  BUDGET_NOTIFICATIONS_STATE="${temp_dir}/notifications.json" \
  BUDGET_NOTIFICATIONS_CALLS="${temp_dir}/calls.jsonl" \
  PATH="${temp_dir}/bin:${PATH}" \
    "${script}" 123456789012 home-search-budget-production-monthly alerts@example.com
}

printf '%s\n' '{"Notifications":[]}' >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
run_reconcile empty >"${temp_dir}/empty.out"
grep -Fq '상태: Pass' "${temp_dir}/empty.out"
[[ "$(wc -l <"${temp_dir}/calls.jsonl" | tr -d ' ')" -eq 3 ]]
jq -e '.Notifications | length == 3' "${temp_dir}/notifications.json" >/dev/null

: >"${temp_dir}/calls.jsonl"
run_reconcile exact >"${temp_dir}/exact.out"
[[ ! -s "${temp_dir}/calls.jsonl" ]]
grep -Fq '상태: Pass' "${temp_dir}/exact.out"

printf '%s\n' '{"Notifications":[{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50.0,"ThresholdType":"ABSOLUTE_VALUE"},{"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":80.0,"ThresholdType":"ABSOLUTE_VALUE"},{"NotificationType":"FORECASTED","ComparisonOperator":"GREATER_THAN","Threshold":100.0,"ThresholdType":"ABSOLUTE_VALUE"}]}' \
  >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
run_reconcile decimal-thresholds >"${temp_dir}/decimal.out"
[[ ! -s "${temp_dir}/calls.jsonl" ]]
grep -Fq '상태: Pass' "${temp_dir}/decimal.out"

jq '.Notifications = [.Notifications[0]]' <<<"${desired}" >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
run_reconcile partial >"${temp_dir}/partial.out"
[[ "$(wc -l <"${temp_dir}/calls.jsonl" | tr -d ' ')" -eq 2 ]]
grep -Fq '상태: Pass' "${temp_dir}/partial.out"

jq '.Notifications += [{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":60,"ThresholdType":"ABSOLUTE_VALUE"}]' \
  <<<"${desired}" >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
if run_reconcile unexpected >"${temp_dir}/unexpected.out" 2>"${temp_dir}/unexpected.err"; then
  echo '상태: Fail - 예상하지 않은 AWS Budget 알림을 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls.jsonl" ]]
grep -Fq '예상하지 않은' "${temp_dir}/unexpected.err"

printf '%s\n' '{"Notifications":[{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"ABSOLUTE_VALUE"}]}' \
  >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
if run_reconcile wrong-subscriber >"${temp_dir}/partial-subscriber.out" 2>"${temp_dir}/partial-subscriber.err"; then
  echo '상태: Fail - 일부 누락 상태에서 다른 AWS Budget subscriber를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls.jsonl" ]]
grep -Fq 'subscriber' "${temp_dir}/partial-subscriber.err"

printf '%s\n' "${desired}" >"${temp_dir}/notifications.json"
: >"${temp_dir}/calls.jsonl"
if run_reconcile wrong-subscriber >"${temp_dir}/subscriber.out" 2>"${temp_dir}/subscriber.err"; then
  echo '상태: Fail - 다른 AWS Budget subscriber를 허용했습니다.' >&2
  exit 1
fi
[[ ! -s "${temp_dir}/calls.jsonl" ]]
grep -Fq 'subscriber' "${temp_dir}/subscriber.err"

if PATH="${temp_dir}/bin:${PATH}" "${script}" not-an-account home-search-budget-production-monthly \
  alerts@example.com >"${temp_dir}/account.out" 2>"${temp_dir}/account.err"; then
  echo '상태: Fail - 잘못된 AWS account ID를 허용했습니다.' >&2
  exit 1
fi
grep -Fq 'account ID' "${temp_dir}/account.err"

echo '상태: Pass - AWS Budget 알림 3개 보정, idempotency, unexpected/subscriber fail-closed를 확인했습니다.'

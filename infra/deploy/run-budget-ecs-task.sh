#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?ECS cluster name is required}"
task_definition="${2:?task definition ARN is required}"
started_by="${3:?started-by token is required}"
command_override="${4:-}"
[[ "${cluster}" == 'home-search-budget-production' ]]
[[ "${task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-[a-z0-9-]+:[0-9]+$ ]]
[[ "${started_by}" =~ ^budget-[A-Za-z0-9_-]{1,28}$ ]]
if [[ -n "${command_override}" ]]; then
  jq -e '
    type == "array" and length > 0
    and all(.[]; type == "string" and length > 0 and length <= 256)
  ' <<<"${command_override}" >/dev/null || {
    echo '상태: Fail - command override는 비어 있지 않은 제한된 JSON string array여야 합니다.' >&2
    exit 2
  }
fi

task_arn=''
completed=false
task_timeout_seconds=7200
cleanup() {
  if [[ -n "${task_arn}" && "${completed}" != true ]]; then
    aws ecs stop-task --region ap-northeast-2 --cluster "${cluster}" \
      --task "${task_arn}" --reason 'budget one-shot waiter exited before completion' >/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

run_args=(
  ecs run-task --region ap-northeast-2 --cluster "${cluster}"
  --task-definition "${task_definition}" --launch-type EC2 --count 1
  --started-by "${started_by}" --output json
)
if [[ -n "${command_override}" ]]; then
  family="${task_definition#*/}"
  family="${family%:*}"
  container_name="${family#home-search-budget-production-}"
  case "${container_name}" in
    property-flyway)
      jq -e '. == ["-target=41", "validate"]' <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - property-flyway override는 exact V41 validate만 허용합니다.' >&2
        exit 2
      }
      ;;
    scheduled-backup)
      jq -e '
        length == 3 and .[0] == "property-search-audit"
        and (.[1] == "before" or .[1] == "after")
        and (.[2] | test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
      ' <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - scheduled-backup override는 release별 property search audit만 허용합니다.' >&2
        exit 2
      }
      ;;
    rtms-daily-refresh)
      task_timeout_seconds="${BUDGET_RTMS_TASK_TIMEOUT_SECONDS:-10800}"
      [[ "${task_timeout_seconds}" == 5400 || "${task_timeout_seconds}" == 10800 ]] || {
        echo '상태: Fail - RTMS timeout은 first/repeat 90분 또는 단일 운영 3시간만 허용합니다.' >&2
        exit 2
      }
      jq -e 'length == 1 and (.[0] | test("^requestId=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))' \
        <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - RTMS 수동 실행은 canonical requestId 하나만 허용합니다.' >&2
        exit 2
      }
      ;;
    market-news-general)
      jq -e 'length == 1 and (.[0] | test("^requestId=BOOTSTRAP:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))' \
        <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - 뉴스 bootstrap은 BOOTSTRAP canonical requestId만 허용합니다.' >&2
        exit 2
      }
      ;;
    market-news-quality-sample)
      jq -e 'length == 2
        and (.[0] | test("^reviewSetId=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        and .[1] == "policyVersion=NEWS_V5"' <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - 뉴스 품질 표본은 canonical reviewSetId와 NEWS_V5만 허용합니다.' >&2
        exit 2
      }
      ;;
    runtime-feature-audit)
      jq -e 'length == 6
        and (.[0] | test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
        and (.[1] | test("^[0-9a-f]{40}$"))
        and (.[2:] | all(test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))' \
        <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - runtime-feature-audit override는 release, commit, execution id 조합만 허용합니다.' >&2
        exit 2
      }
      ;;
    market-news-major-selection | market-news-major-complex | market-news-retention)
      jq -e 'length == 1 and (.[0] | test("^schedulerExecutionId=manual-[0-9a-f]{32}$"))' \
        <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - 뉴스 수동 운영 job은 제한된 execution id만 허용합니다.' >&2
        exit 2
      }
      ;;
    *)
      echo '상태: Fail - 이 budget task에는 command override를 허용하지 않습니다.' >&2
      exit 2
      ;;
  esac
  overrides="$(jq -cn --arg name "${container_name}" --argjson command "${command_override}" \
    '{containerOverrides:[{name:$name,command:$command}]}')"
  run_args+=(--overrides "${overrides}")
fi
result="$(aws "${run_args[@]}")"
jq -e '.failures | length == 0' <<<"${result}" >/dev/null || {
  jq -c '.failures' <<<"${result}" >&2
  exit 1
}
task_arn="$(jq -er '.tasks | select(length == 1) | .[0].taskArn' <<<"${result}")"
[[ "${task_arn}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task/home-search-budget-production/ ]]

deadline=$((SECONDS + task_timeout_seconds))
last_status=''
description=''
while ((SECONDS < deadline)); do
  description="$(aws ecs describe-tasks --region ap-northeast-2 --cluster "${cluster}" \
    --tasks "${task_arn}" --output json)"
  jq -e '(.failures | length) == 0 and (.tasks | length) == 1' <<<"${description}" >/dev/null
  last_status="$(jq -er '.tasks[0].lastStatus' <<<"${description}")"
  [[ "${last_status}" != STOPPED ]] || break
  sleep 15
done
if [[ "${last_status}" != STOPPED ]]; then
  echo "상태: Fail - budget one-shot task가 ${task_timeout_seconds}초 안에 종료되지 않았습니다." >&2
  exit 1
fi
jq -e '
  (.failures | length) == 0
  and (.tasks | length) == 1
  and (.tasks[0].containers | length) > 0
  and all(.tasks[0].containers[]; .exitCode == 0)
' <<<"${description}" >/dev/null || {
  jq -c '.tasks[0] | {stopCode,stoppedReason,containers:[.containers[] | {name,exitCode,reason}]}' <<<"${description}" >&2
  exit 1
}
completed=true
trap - EXIT HUP INT TERM
printf '%s\n' "${task_arn}"

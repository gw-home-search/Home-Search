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
      jq -e '. == ["-target=40", "validate"]' <<<"${command_override}" >/dev/null || {
        echo '상태: Fail - property-flyway override는 exact V40 validate만 허용합니다.' >&2
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

timeout 7200 aws ecs wait tasks-stopped --region ap-northeast-2 \
  --cluster "${cluster}" --tasks "${task_arn}"
description="$(aws ecs describe-tasks --region ap-northeast-2 --cluster "${cluster}" \
  --tasks "${task_arn}" --output json)"
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

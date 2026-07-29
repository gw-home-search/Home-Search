#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?ECS cluster name is required}"
task_definition="${2:?task definition ARN is required}"
started_by="${3:?started-by token is required}"
[[ "${cluster}" == 'home-search-budget-production' ]]
[[ "${task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-[a-z0-9-]+:[0-9]+$ ]]
[[ "${started_by}" =~ ^budget-[A-Za-z0-9_-]{1,28}$ ]]

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

result="$(aws ecs run-task --region ap-northeast-2 --cluster "${cluster}" \
  --task-definition "${task_definition}" --launch-type EC2 --count 1 \
  --started-by "${started_by}" --output json)"
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

#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?cluster ARN is required}"
task_definition="${2:?task definition ARN is required}"
subnets_json="${3:?subnet JSON array is required}"
security_groups_json="${4:?security group JSON array is required}"

network="$(jq -cn --argjson subnets "${subnets_json}" --argjson securityGroups "${security_groups_json}" \
  '{awsvpcConfiguration:{subnets:$subnets,securityGroups:$securityGroups,assignPublicIp:"DISABLED"}}')"
result="$(aws ecs run-task --cluster "${cluster}" --task-definition "${task_definition}" \
  --launch-type FARGATE --platform-version LATEST --network-configuration "${network}" --output json)"
[[ "$(jq '.failures | length' <<<"${result}")" == '0' ]] \
  || { jq -c '.failures' <<<"${result}" >&2; exit 1; }
task_arn="$(jq -r '.tasks[0].taskArn // empty' <<<"${result}")"
[[ -n "${task_arn}" ]] || { echo '상태: Fail - ECS task ARN을 받지 못했습니다.' >&2; exit 1; }

aws ecs wait tasks-stopped --cluster "${cluster}" --tasks "${task_arn}"
description="$(aws ecs describe-tasks --cluster "${cluster}" --tasks "${task_arn}" --output json)"
if ! jq -e '.tasks[0].containers | length > 0 and all(.exitCode == 0)' <<<"${description}" >/dev/null; then
  jq -c '.tasks[0] | {stopCode,stoppedReason,containers:[.containers[] | {name,exitCode,reason}]}' <<<"${description}" >&2
  exit 1
fi
printf '%s\n' "${task_arn}"

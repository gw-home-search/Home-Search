#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?terraform plan JSON is required}"
mode="${2:-release}"
[[ -f "${plan_json}" ]] || exit 2
[[ "${mode}" == 'release' || "${mode}" == 'one-shot' || "${mode}" == 'activation' ]] || exit 2
if [[ "${mode}" == 'activation' ]]; then
  violations="$(jq -c '
    [.resource_changes[]
     | select(.change.actions != ["no-op"] and .change.actions != ["read"])
     | select(
         ((.type == "aws_ecs_service")
          and (.address | startswith("aws_ecs_service.service["))
          and .change.actions == ["update"]
          and ((.change.before | del(.desired_count)) == (.change.after | del(.desired_count))))
         or
         ((.type == "aws_cloudwatch_metric_alarm")
          and (.address | startswith("aws_cloudwatch_metric_alarm.ecs_running_task["))
          and .change.actions == ["update"]
          and ((.change.before | del(.threshold)) == (.change.after | del(.threshold))))
         or
         ((.type == "aws_cloudwatch_metric_alarm")
          and .address == "aws_cloudwatch_metric_alarm.public_unhealthy_targets"
          and .change.actions == ["update"]
          and ((.change.before | del(.treat_missing_data)) == (.change.after | del(.treat_missing_data))))
         | not)
     | {address,type,actions:.change.actions}]
  ' "${plan_json}")"
  if [[ "$(jq 'length' <<<"${violations}")" != '0' ]]; then
    echo '상태: Fail - service activation plan이 desired count와 대응 alarm 허용 범위를 벗어났습니다.' >&2
    jq . <<<"${violations}" >&2
    exit 1
  fi
  echo '상태: Pass - service activation plan은 desired count와 대응 alarm만 변경합니다.'
  exit 0
fi
violations="$(jq -c '
  ($mode == "one-shot") as $one_shot |
  [.resource_changes[]
   | select(.change.actions != ["no-op"] and .change.actions != ["read"])
   | . as $change
   | select(
       ((.type == "aws_ecs_task_definition") and
         ((($one_shot | not) and ((.address | startswith("aws_ecs_task_definition.service[")) or
                                  (.address | startswith("aws_ecs_task_definition.one_shot[")) or
                                  .address == "aws_ecs_task_definition.user_insight_worker")) or
          ($one_shot and (.address | startswith("aws_ecs_task_definition.one_shot[")))) and
         (.change.actions == ["create"] or .change.actions == ["update"] or .change.actions == ["delete","create"])) or
       (($one_shot | not) and (.type == "aws_ecs_service") and
         ((.address | startswith("aws_ecs_service.service[")) or
          .address == "aws_ecs_service.user_insight_worker") and
         (.change.actions == ["create"] or .change.actions == ["update"])) or
       (($one_shot | not) and (.type == "aws_cloudwatch_metric_alarm") and
         ((.address | startswith("aws_cloudwatch_metric_alarm.ecs_running_task[")) or
          .address == "aws_cloudwatch_metric_alarm.user_insight_worker_running[0]") and
         (.change.actions == ["create"] or .change.actions == ["update"])) or
       (($one_shot | not) and (.type == "aws_scheduler_schedule") and
         ((.address | startswith("aws_scheduler_schedule.database_backup[")) or
          (.address | startswith("aws_scheduler_schedule.market_news[")) or
          .address == "aws_scheduler_schedule.property_event_relay" or
          .address == "aws_scheduler_schedule.property_event_retention") and
         .change.actions == ["update"])
     | not)
   | {address,type,actions:.change.actions}]
' --arg mode "${mode}" "${plan_json}")"
if [[ "$(jq 'length' <<<"${violations}")" != '0' ]]; then
  echo '상태: Fail - staging release plan이 workload 허용 범위를 벗어났습니다.' >&2
  jq . <<<"${violations}" >&2
  exit 1
fi
echo '상태: Pass - Terraform plan은 task/service/schedule release 변경만 포함합니다.'

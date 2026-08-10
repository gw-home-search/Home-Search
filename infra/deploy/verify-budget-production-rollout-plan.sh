#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?Terraform plan JSON is required}"
live_application_settings_json="${2:?Live application settings JSON is required}"
plan_phase="${3:-prep}"
[[ -f "${plan_json}" && ! -L "${plan_json}" ]] || exit 2
[[ -f "${live_application_settings_json}" && ! -L "${live_application_settings_json}" ]] || exit 2
[[ "${plan_phase}" == prep || "${plan_phase}" == final ]]

ai_supervisor_graph_mode="$(jq -er '
  .ai_supervisor_graph_mode
  | select(type == "string" and (. == "off" or . == "shadow" or . == "canary" or . == "active"))
' "${live_application_settings_json}")"
ai_supervisor_graph_canary_percent="$(jq -er '
  .ai_supervisor_graph_canary_percent
  | select(type == "number" and . >= 0 and . <= 100 and floor == .) | tostring
' "${live_application_settings_json}")"

violations="$(jq -c \
  --arg ai_mode "${ai_supervisor_graph_mode}" \
  --arg ai_percent "${ai_supervisor_graph_canary_percent}" \
  --arg phase "${plan_phase}" '
  def immutable_image:
    type == "string"
    and test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$");
  def containers: ((.change.after.container_definitions | fromjson?) // []);
  def task_images_safe:
    (containers | length) > 0 and all(containers[]; .image | immutable_image)
    and all(containers[]; ((.command // []) | map(ascii_downcase) | index("clean")) == null);
  def ai_live_settings_preserved:
    if .address == "aws_ecs_task_definition.application[\"ai\"]" then
      ([containers[] | select(.name == "ai") | .environment[]?
        | select(.name == "HOME_AI_SUPERVISOR_GRAPH_MODE") | .value] == [$ai_mode])
      and ([containers[] | select(.name == "ai") | .environment[]?
        | select(.name == "HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT") | .value] == [$ai_percent])
    else true end;
  def task_change_safe:
    (.change.actions == ["create"] or .change.actions == ["delete","create"])
    and .change.after.skip_destroy == true and task_images_safe and ai_live_settings_preserved;
  def runtime_task_address:
    .type == "aws_ecs_task_definition"
    and (.address | test("^aws_ecs_task_definition[.](application|one_shot)\\["));
  def runtime_support_address:
    (.type == "aws_cloudwatch_log_group" and (.address | test("^aws_cloudwatch_log_group[.]runtime\\[")))
    or (.type == "aws_iam_role" and (.address | test("^aws_iam_role[.](task_execution|task_runtime|market_news_scheduler|rtms_scheduler|rtms_orchestration)")))
    or (.type == "aws_iam_role_policy_attachment" and (.address | test("^aws_iam_role_policy_attachment[.]task_execution\\[")))
    or (.type == "aws_iam_role_policy" and (.address | test("^aws_iam_role_policy[.](task_execution|secret_readiness|market_news_scheduler|rtms_scheduler|rtms_orchestration|runtime_feature_audit|runtime_log_audit|host_operations)")))
    or (.type == "aws_ssm_parameter" and (.address | test("^aws_ssm_parameter[.]runtime\\[")))
    or .address == "aws_ssm_document.install_ml_model[0]"
    or .address == "aws_scheduler_schedule_group.market_news[0]"
    or .address == "aws_scheduler_schedule_group.data_refresh[0]"
    or .address == "aws_sfn_state_machine.rtms_refresh[0]"
    or (.type == "aws_scheduler_schedule" and (.address | test("^aws_scheduler_schedule[.](market_news|rtms_daily_refresh)\\[")))
    or (.type == "aws_cloudwatch_metric_alarm" and (.address | test("^aws_cloudwatch_metric_alarm[.](market_news_scheduler_failure|rtms_refresh_failure|ml_recovery_critical)")));
  def exact_rtms_scheduler_policy_replacement:
    .address == "aws_iam_role_policy.rtms_scheduler[0]"
    and .type == "aws_iam_role_policy"
    and .change.actions == ["delete","create"]
    and .change.after.name == "start-reviewed-rtms-orchestration"
    and .change.after.role == "home-search-budget-production-rtms-scheduler"
    and ((.change.after.policy | fromjson) as $policy
      | $policy.Version == "2012-10-17"
      and ($policy.Statement | length) == 1
      and $policy.Statement[0].Effect == "Allow"
      and $policy.Statement[0].Action == ["states:StartExecution"]
      and ($policy.Statement[0].Resource | length) == 1
      and ($policy.Statement[0].Resource[0]
        == "arn:aws:states:ap-northeast-2:399291871263:stateMachine:home-search-budget-production-rtms-refresh"));
  def forbidden_scope:
    (.type | test("^aws_(route53|ebs|instance|eip|vpc|subnet|security_group|s3_bucket|dlm)"))
    or ((.address | test("platform|data[_-]?import|recovery|rehearsal|logical_backup|backup_scheduler"; "i"))
      and .address != "aws_cloudwatch_metric_alarm.ml_recovery_critical[0]");
  def final_schedule_update:
    .type == "aws_scheduler_schedule"
    and .address == "aws_scheduler_schedule.rtms_daily_refresh[0]"
    and .change.actions == ["update"]
    and .change.before.state == "DISABLED" and .change.after.state == "ENABLED"
    and ((.change.before | del(.state)) == (.change.after | del(.state)));
  def final_rtms_revision_update:
    .address == "aws_sfn_state_machine.rtms_refresh[0]"
    and .change.actions == ["update"]
    and ((.change.before | del(.definition)) == (.change.after | del(.definition)))
    and (((.change.before.definition | fromjson) | .States.RUN_RTMS.Parameters.TaskDefinition = "PINNED")
      == ((.change.after.definition | fromjson) | .States.RUN_RTMS.Parameters.TaskDefinition = "PINNED"))
    and ((.change.after.definition | fromjson).States.RUN_RTMS.Parameters.TaskDefinition
      | test("^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-rtms-daily-refresh:[1-9][0-9]*$"));
  [.resource_changes[]?
    | select(.mode == "managed")
    | select(.change.actions != ["no-op"] and .change.actions != ["read"])
    | select(
        if $phase == "final" then
          ((final_schedule_update or final_rtms_revision_update) | not)
        else
          forbidden_scope
          or .type == "aws_ecs_service"
          or (runtime_task_address and (task_change_safe | not))
          or ((runtime_task_address or runtime_support_address) | not)
          or ((.change.actions | index("delete")) != null
            and (((runtime_task_address and .change.actions == ["delete","create"] and .change.after.skip_destroy == true)
              or exact_rtms_scheduler_policy_replacement) | not))
          or ((.change.after // {} | tostring) | test("home-search-(staging|production)([^-]|$)"))
        end
      )
    | {address,type,actions:.change.actions}
  ]
' "${plan_json}")"

if [[ "$(jq 'length' <<<"${violations}")" != 0 ]]; then
  echo "상태: Fail - ${plan_phase} plan이 application task/news·RTMS/model 최소 allowlist 또는 zero-destroy 경계를 벗어났습니다." >&2
  jq . <<<"${violations}" >&2
  exit 1
fi

echo "상태: Pass - ${plan_phase} plan은 service/platform/DNS/storage 변경 없이 승인된 runtime 리소스만 포함합니다."

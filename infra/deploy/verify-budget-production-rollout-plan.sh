#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?Terraform plan JSON is required}"
[[ -f "${plan_json}" && ! -L "${plan_json}" ]] || exit 2

violations="$(jq -c '
  def immutable_image:
    type == "string"
    and test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$");
  def normalized_containers($address):
    fromjson
    | map(
        del(.image)
        | if .dependsOn == [] then del(.dependsOn) else . end
        | if $address == "aws_ecs_task_definition.one_shot[\"property-flyway\"]" then
            .command = ["__EXACT_PROPERTY_TARGET__", "__FLYWAY_OPERATION__"]
          else . end
        | .environment |= map(
            if .name == "HOME_MIGRATION_EVIDENCE_S3_URI" then .value = "__RELEASE_EVIDENCE_URI__" else . end
          )
      );
  def normalized_task_definition($address):
    .container_definitions |= normalized_containers($address)
    | .ipc_mode = (if .ipc_mode == "" then null else .ipc_mode end)
    | .pid_mode = (if .pid_mode == "" then null else .pid_mode end)
    | del(.arn,.arn_without_revision,.id,.revision,.tags.Release,.tags_all.Release);
  def safe_task_revision:
    . as $change
    | (($change.change.before.container_definitions | fromjson?) // []) as $before_containers
    | (($change.change.after.container_definitions | fromjson?) // []) as $after_containers
    | $change.change.actions == ["delete","create"]
    and $change.change.before.skip_destroy == true
    and $change.change.after.skip_destroy == true
    and ($before_containers | length) > 0
    and ($before_containers | length) == ($after_containers | length)
    and all(range(0; $before_containers | length);
      ($before_containers[.] | .image | immutable_image)
      and ($after_containers[.] | .image | immutable_image)
      and $before_containers[.].name == $after_containers[.].name
      and (($before_containers[.].image | split("@sha256:")[0]) == ($after_containers[.].image | split("@sha256:")[0])))
    and ($change.address != "aws_ecs_task_definition.one_shot[\"property-flyway\"]"
      or ($before_containers[0].command == ["migrate"]
        and $after_containers[0].command == ["-target=40","migrate"]))
    and (($change.change.before | normalized_task_definition($change.address))
      == ($change.change.after | normalized_task_definition($change.address)));
  def safe_service_revision:
    .change.actions == ["update"]
    and ((.change.before | del(.task_definition)) == (.change.after | del(.task_definition)));
  def new_rtms_task:
    .address == "aws_ecs_task_definition.one_shot[\"rtms-daily-refresh\"]"
    and .change.actions == ["create"]
    and .change.after.skip_destroy == true;
  def allowed_address:
    (.type == "aws_ecs_task_definition" and
      (.address | test("^aws_ecs_task_definition[.](application|one_shot)\\[")))
    or (.type == "aws_ecs_service" and
      (.address | test("^aws_ecs_service[.]application\\[")))
    or (.address == "aws_iam_role.rtms_scheduler[0]")
    or (.address == "aws_iam_role_policy.rtms_scheduler[0]")
    or (.address == "aws_scheduler_schedule_group.data_refresh[0]")
    or (.address == "aws_scheduler_schedule.rtms_daily_refresh[0]")
    or (.type == "aws_cloudwatch_log_group" and
      (.address | test("^aws_cloudwatch_log_group[.]runtime\\[\\\"rtms-daily-refresh\\\"\\]$")))
    or (.type == "aws_iam_role" and
      (.address | test("^aws_iam_role[.](task_execution|task_runtime)\\[\\\"rtms-daily-refresh\\\"\\]$")))
    or (.type == "aws_iam_role_policy" and
      (.address | test("^aws_iam_role_policy[.]task_execution\\[\\\"rtms-daily-refresh\\\"\\]$")))
    or (.type == "aws_iam_role_policy_attachment" and
      (.address | test("^aws_iam_role_policy_attachment[.]task_execution\\[\\\"rtms-daily-refresh\\\"\\]$")))
    or (.address == "aws_iam_role_policy.backup_scheduler[0]" and .change.actions == ["update"])
    or (.address == "aws_iam_role_policy.secret_readiness[0]" and .change.actions == ["update"])
    or (.address == "aws_iam_role_policy.task_execution[\"map-marker-projection\"]" and .change.actions == ["update"])
    or (.address == "aws_scheduler_schedule.logical_backup[0]" and .change.actions == ["update"]);
  [.resource_changes[]?
    | select(.mode == "managed")
    | select(.change.actions != ["no-op"] and .change.actions != ["read"])
    | select(
        (allowed_address | not)
        or (.address | test("aws_ecs_(service|task_definition)[.]platform"))
        or (.type == "aws_ecs_task_definition" and ((safe_task_revision or new_rtms_task) | not))
        or (.type == "aws_ecs_service" and (safe_service_revision | not))
        or ((.change.actions | index("delete")) != null
          and ((.type == "aws_ecs_task_definition"
            and .change.actions == ["delete","create"]
            and .change.before.skip_destroy == true
            and .change.after.skip_destroy == true) | not))
        or ((.change.after // {} | tostring) | test("home-search-(staging|production)([^-]|$)"))
      )
    | {address,type,actions:.change.actions}
  ]
' "${plan_json}")"

if [[ "$(jq 'length' <<<"${violations}")" != 0 ]]; then
  echo '상태: Fail - 증분 rollout plan이 application/one-shot/RTMS allowlist 또는 zero-destroy 경계를 벗어났습니다.' >&2
  jq . <<<"${violations}" >&2
  exit 1
fi

echo '상태: Pass - 증분 rollout plan은 application/one-shot revision과 제한된 RTMS scheduler/IAM 변경만 포함합니다.'

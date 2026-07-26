#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

jq -n '{resource_changes:[
  {address:"aws_ecs_task_definition.service[\"property-api\"]",type:"aws_ecs_task_definition",change:{actions:["delete","create"]}},
  {address:"aws_ecs_task_definition.user_insight_worker",type:"aws_ecs_task_definition",change:{actions:["delete","create"]}},
  {address:"aws_ecs_service.service[\"property-api\"]",type:"aws_ecs_service",change:{actions:["update"]}},
  {address:"aws_ecs_service.user_insight_worker",type:"aws_ecs_service",change:{actions:["update"]}},
  {address:"aws_cloudwatch_metric_alarm.ecs_running_task[\"property-api\"]",type:"aws_cloudwatch_metric_alarm",change:{actions:["create"]}},
  {address:"aws_cloudwatch_metric_alarm.user_insight_worker_running[0]",type:"aws_cloudwatch_metric_alarm",change:{actions:["create"]}},
  {address:"aws_scheduler_schedule.database_backup[\"daily-backup\"]",type:"aws_scheduler_schedule",change:{actions:["update"]}},
  {address:"aws_scheduler_schedule.property_event_retention",type:"aws_scheduler_schedule",change:{actions:["update"]}},
  {address:"aws_db_instance.primary",type:"aws_db_instance",change:{actions:["no-op"]}}
]}' >"${tmp_dir}/allowed.json"
"${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/allowed.json" >/dev/null
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/allowed.json" one-shot >/dev/null 2>&1; then
  echo '상태: Fail - one-shot 사전 plan에서 service 변경을 허용했습니다.' >&2
  exit 1
fi
jq -n '{resource_changes:[
  {address:"aws_ecs_task_definition.one_shot[\"property-flyway\"]",type:"aws_ecs_task_definition",change:{actions:["delete","create"]}}
]}' >"${tmp_dir}/one-shot.json"
"${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/one-shot.json" one-shot >/dev/null
jq '.resource_changes += [{address:"aws_db_instance.primary",type:"aws_db_instance",change:{actions:["update"]}}]' \
  "${tmp_dir}/allowed.json" >"${tmp_dir}/blocked.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/blocked.json" >/dev/null 2>&1; then
  echo '상태: Fail - data resource Terraform 변경을 허용했습니다.' >&2
  exit 1
fi
jq -n '{resource_changes:[
  {address:"aws_ecs_task_definition.exfil",type:"aws_ecs_task_definition",change:{actions:["create"]}}
]}' >"${tmp_dir}/unknown-task.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/unknown-task.json" >/dev/null 2>&1; then
  echo '상태: Fail - 승인되지 않은 task definition address를 허용했습니다.' >&2
  exit 1
fi
jq -n '{resource_changes:[
  {address:"aws_ecs_task_definition.one_shot_exfil[\"copy\"]",type:"aws_ecs_task_definition",change:{actions:["create"]}},
  {address:"aws_scheduler_schedule.exfil",type:"aws_scheduler_schedule",change:{actions:["update"]}}
]}' >"${tmp_dir}/prefix-bypass.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/prefix-bypass.json" >/dev/null 2>&1; then
  echo '상태: Fail - task prefix 또는 unknown Scheduler address 우회를 허용했습니다.' >&2
  exit 1
fi
jq -n '{resource_changes:[
  {address:"aws_cloudwatch_metric_alarm.user_insight_worker_running[0]",type:"aws_cloudwatch_metric_alarm",change:{actions:["delete"]}}
]}' >"${tmp_dir}/alarm-delete.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/alarm-delete.json" >/dev/null 2>&1; then
  echo '상태: Fail - release plan이 running-task alarm 삭제를 허용했습니다.' >&2
  exit 1
fi

"${root}/infra/deploy/migration-checksums.sh" >"${tmp_dir}/checksums.json"
jq -e 'keys == ["admin","property","source_data","user"] and ([.[]] | all(test("^[0-9a-f]{64}$")))' \
  "${tmp_dir}/checksums.json" >/dev/null

mkdir -p "${tmp_dir}/bin"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_LOG}"
printf '\n' >>"${FAKE_AWS_LOG}"
case "$*" in
  *'ecs run-task'*) printf '%s\n' '{"tasks":[{"taskArn":"arn:aws:ecs:ap-northeast-2:123456789012:task/staging/task-1"}],"failures":[]}' ;;
  *'ecs wait tasks-stopped'*) ;;
  *'ecs describe-tasks'*) printf '%s\n' '{"tasks":[{"containers":[{"name":"migration","exitCode":0}]}]}' ;;
  *'ecs list-services'*) printf '%s\n' '["arn:aws:ecs:ap-northeast-2:123456789012:service/home-search-staging/user-insight-worker"]' ;;
  *'ecs describe-services'*) printf '%s\n' '{"serviceName":"user-insight-worker","taskDefinition":"arn:aws:ecs:ap-northeast-2:123456789012:task-definition/user-insight-worker:1","desiredCount":0}' ;;
  *'ecs describe-task-definition'*) printf '%s\n' '{"containerDefinitions":[{"name":"user-insight-worker","image":"example.invalid/worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}' ;;
  *'ecs update-service'*) printf '%s\n' '{}' ;;
  *'ecs wait services-stable'*) ;;
  *) exit 2 ;;
esac
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"
: >"${tmp_dir}/aws.log"
task_arn="$(PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" \
  "${root}/infra/deploy/run-ecs-task.sh" arn:cluster arn:task '["subnet-1"]' '["sg-1"]')"
[[ "${task_arn}" == 'arn:aws:ecs:ap-northeast-2:123456789012:task/staging/task-1' ]]
! grep -Eq 'password|secret' "${tmp_dir}/aws.log"

PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" \
  "${root}/infra/deploy/capture-service-state.sh" arn:cluster "${tmp_dir}/service-state.json"
jq -e '.services["user-insight-worker"].desired_count == 0' "${tmp_dir}/service-state.json" >/dev/null
PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" \
  "${root}/infra/deploy/rollback-services.sh" "${tmp_dir}/service-state.json" >/dev/null
grep -F -- '--desired-count 0' "${tmp_dir}/aws.log" >/dev/null

echo '상태: Pass - Terraform release/one-shot allowlist, migration checksum, ECS one-shot exit, rollback desired count 복구를 확인했습니다.'

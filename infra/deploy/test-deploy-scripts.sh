#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

images=(
  property-api property-batch property-flyway admin-api admin-migration admin-ops
  user-api user-insight-worker user-flyway source-data-migration public-gateway admin-gateway
  backup ops-bootstrap ml ai chat-bff
)
manifest_images='{}'
for image in "${images[@]}"; do
  manifest_images="$(jq --arg name "${image}" \
    --arg uri "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/${image}@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
    '. + {($name):{repository:("home-search/" + $name),uri:$uri,digest:"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}' \
    <<<"${manifest_images}")"
done
jq -n --argjson images "${manifest_images}" \
  '{
    format_version:2,
    tag:"v1.2.3",
    commit_sha:"0123456789abcdef0123456789abcdef01234567",
    images:$images,
    build_architecture:"linux/amd64",
    event_schema_sha256:("c" * 64),
    topic_manifest_sha256:("d" * 64),
    flyway_migration_set_sha256:("e" * 64),
    sbom_set_sha256:("f" * 64),
    vulnerability_set_sha256:("1" * 64),
    build_flags:{market_news_enabled:false},
    vulnerability_critical_gate_passed:true,
    vulnerability_policy_gate_passed:true
  }' \
  >"${tmp_dir}/release-manifest.json"
"${root}/infra/deploy/release-manifest-to-tfvars.sh" \
  "${tmp_dir}/release-manifest.json" \
  'public.ecr.aws/aws-observability/aws-otel-collector@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  "${tmp_dir}/release.auto.tfvars.json"
jq -e '
  (.image_uris | length == 17)
  and .deployment_release_tag == "v1.2.3"
  and .image_uris["property-api"] == "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  and .adot_collector_image_uri == "public.ecr.aws/aws-observability/aws-otel-collector@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
' "${tmp_dir}/release.auto.tfvars.json" >/dev/null
jq 'del(.images["chat-bff"])' "${tmp_dir}/release-manifest.json" >"${tmp_dir}/missing-image.json"
if "${root}/infra/deploy/release-manifest-to-tfvars.sh" \
  "${tmp_dir}/missing-image.json" \
  'public.ecr.aws/aws-observability/aws-otel-collector@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  "${tmp_dir}/missing.auto.tfvars.json" >/dev/null 2>&1; then
  echo '상태: Fail - image가 누락된 release manifest를 Terraform input으로 변환했습니다.' >&2
  exit 1
fi
if "${root}/infra/deploy/release-manifest-to-tfvars.sh" \
  "${tmp_dir}/release-manifest.json" \
  'public.ecr.aws/aws-observability/aws-otel-collector:latest' \
  "${tmp_dir}/mutable.auto.tfvars.json" >/dev/null 2>&1; then
  echo '상태: Fail - mutable ADOT image를 Terraform input으로 허용했습니다.' >&2
  exit 1
fi

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

jq -n '{resource_changes:[
  {address:"aws_ecs_service.service[\"user-insight-worker\"]",type:"aws_ecs_service",change:{actions:["update"],before:{desired_count:0,name:"user-insight-worker"},after:{desired_count:2,name:"user-insight-worker"}}},
  {address:"aws_cloudwatch_metric_alarm.ecs_running_task[\"user-insight-worker\"]",type:"aws_cloudwatch_metric_alarm",change:{actions:["update"],before:{threshold:0,treat_missing_data:"notBreaching"},after:{threshold:2,treat_missing_data:"notBreaching"}}}
]}' >"${tmp_dir}/activation.json"
"${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/activation.json" activation >/dev/null
jq '.resource_changes += [{address:"aws_ecs_task_definition.service[\"user-insight-worker\"]",type:"aws_ecs_task_definition",change:{actions:["update"],before:{cpu:"512"},after:{cpu:"1024"}}}]' \
  "${tmp_dir}/activation.json" >"${tmp_dir}/activation-task-change.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/activation-task-change.json" activation >/dev/null 2>&1; then
  echo '상태: Fail - activation plan이 task definition 변경을 허용했습니다.' >&2
  exit 1
fi
jq '.resource_changes[0].change.after.name = "unexpected"' \
  "${tmp_dir}/activation.json" >"${tmp_dir}/activation-field-change.json"
if "${root}/infra/deploy/verify-terraform-plan.sh" "${tmp_dir}/activation-field-change.json" activation >/dev/null 2>&1; then
  echo '상태: Fail - activation plan이 desired_count 이외 service 변경을 허용했습니다.' >&2
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
  *'ecs describe-clusters'*)
    if [[ "${FAKE_CLUSTER_FAILURE:-}" == 'missing' ]]; then
      printf '%s\n' '{"clusters":[],"failures":[{"arn":"arn:cluster","reason":"MISSING"}]}'
    elif [[ "${FAKE_CLUSTER_FAILURE:-}" == 'access-denied' ]]; then
      printf '%s\n' '{"clusters":[],"failures":[{"arn":"arn:cluster","reason":"ACCESS_DENIED"}]}'
    else
      printf '%s\n' '{"clusters":[{"clusterArn":"arn:cluster","status":"ACTIVE"}],"failures":[]}'
    fi
    ;;
  *'ecs list-services'*) printf '%s\n' '["arn:aws:ecs:ap-northeast-2:123456789012:service/home-search-staging/user-insight-worker"]' ;;
  *'ecs describe-services'*)
    if [[ "${FAKE_PHASE_VERIFY:-}" == '1' ]]; then
      services='[]'
      for name in property-api admin-api user-api public-gateway admin-gateway ml ai chat-bff user-insight-worker; do
        services="$(jq --arg name "${name}" '. + [{serviceName:$name,taskDefinition:("arn:aws:ecs:ap-northeast-2:123456789012:task-definition/" + $name + ":1"),desiredCount:2,runningCount:2,pendingCount:0,deployments:[{status:"PRIMARY",rolloutState:"COMPLETED"}]}]' <<<"${services}")"
      done
      jq -n --argjson services "${services}" '{services:$services,failures:[]}'
    else
      printf '%s\n' '{"serviceName":"user-insight-worker","taskDefinition":"arn:aws:ecs:ap-northeast-2:123456789012:task-definition/user-insight-worker:1","desiredCount":0}'
    fi
    ;;
  *'ecs describe-task-definition'*)
    if [[ "${FAKE_PHASE_VERIFY:-}" == '1' ]]; then
      task_definition=''
      previous=''
      for argument in "$@"; do
        if [[ "${previous}" == '--task-definition' ]]; then task_definition="${argument}"; break; fi
        previous="${argument}"
      done
      name="${task_definition##*/}"
      name="${name%%:*}"
      jq -n --arg name "${name}" --arg image "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/${name}@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
        '{containerDefinitions:[{name:$name,image:$image}]}'
    else
      printf '%s\n' '{"containerDefinitions":[{"name":"user-insight-worker","image":"example.invalid/worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}'
    fi
    ;;
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
jq -e '.cluster_exists == true and .services["user-insight-worker"].desired_count == 0' "${tmp_dir}/service-state.json" >/dev/null
PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" \
  "${root}/infra/deploy/rollback-services.sh" "${tmp_dir}/service-state.json" >/dev/null
grep -F -- '--desired-count 0' "${tmp_dir}/aws.log" >/dev/null

PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" FAKE_CLUSTER_FAILURE=missing \
  "${root}/infra/deploy/capture-service-state.sh" arn:cluster "${tmp_dir}/first-deploy-state.json"
jq -e '.cluster_exists == false and (.services | length) == 0' "${tmp_dir}/first-deploy-state.json" >/dev/null
if PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" FAKE_CLUSTER_FAILURE=access-denied \
  "${root}/infra/deploy/capture-service-state.sh" arn:cluster "${tmp_dir}/denied-state.json" >/dev/null 2>&1; then
  echo '상태: Fail - ECS cluster access failure를 최초 배포 상태로 오인했습니다.' >&2
  exit 1
fi

PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" FAKE_PHASE_VERIFY=1 \
  "${root}/infra/deploy/verify-service-activation.sh" \
  arn:cluster all 2 "${tmp_dir}/release-manifest.json" "${tmp_dir}/activation-evidence.json"
jq -e '.phase == "all" and ([.services[].running_count] | all(. == 2))' \
  "${tmp_dir}/activation-evidence.json" >/dev/null
jq '.images["public-gateway"].uri = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/public-gateway@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' \
  "${tmp_dir}/release-manifest.json" >"${tmp_dir}/mismatched-release-manifest.json"
if PATH="${tmp_dir}/bin:${PATH}" FAKE_AWS_LOG="${tmp_dir}/aws.log" FAKE_PHASE_VERIFY=1 \
  "${root}/infra/deploy/verify-service-activation.sh" \
  arn:cluster all 2 "${tmp_dir}/mismatched-release-manifest.json" "${tmp_dir}/mismatched-activation-evidence.json" \
  >/dev/null 2>&1; then
  echo '상태: Fail - ECS task image와 release manifest digest 불일치를 허용했습니다.' >&2
  exit 1
fi

echo '상태: Pass - Terraform release/one-shot allowlist, migration checksum, ECS one-shot exit, rollback desired count 복구를 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
normalizer="${root_dir}/infra/bootstrap/normalize-budget-generated-values.sh"
run_task_script="${BUDGET_RUN_TASK_SCRIPT:-${root_dir}/infra/deploy/run-budget-ecs-task.sh}"
cluster="${1:?ECS cluster name is required}"
base_task_definition="${2:?secret bootstrap task definition ARN is required}"
started_by="${3:?started-by token is required}"
region='ap-northeast-2'

[[ "${cluster}" == 'home-search-budget-production' ]]
[[ "${base_task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-secret-bootstrap:[0-9]+$ ]]
[[ "${started_by}" =~ ^budget-[A-Za-z0-9_-]{1,28}$ ]]
[[ -x "${normalizer}" && -x "${run_task_script}" ]]
account_id="$(aws sts get-caller-identity --query Account --output text)"
[[ "${account_id}" =~ ^[0-9]{12}$ ]]
[[ "${base_task_definition}" == "arn:aws:ecs:${region}:${account_id}:task-definition/home-search-budget-production-secret-bootstrap:"* ]]

tmp_dir="$(mktemp -d)"
registered_task_definition=''
cleanup() {
  local status=$?
  if [[ -n "${registered_task_definition}" ]]; then
    aws ecs deregister-task-definition --region "${region}" \
      --task-definition "${registered_task_definition}" >/dev/null || true
  fi
  find "${tmp_dir}" -depth -delete 2>/dev/null || true
  return "${status}"
}
trap cleanup EXIT

service_state="$(aws ecs describe-services --region "${region}" --cluster "${cluster}" \
  --services budget-postgres budget-valkey --output json)"
jq -e '
  (.failures | length) == 0
  and (.services | length) == 2
  and ([.services[].serviceName] | sort) == ["budget-postgres", "budget-valkey"]
  and all(.services[]; .desiredCount == 0 and .runningCount == 0 and .pendingCount == 0)
' <<<"${service_state}" >/dev/null

aws ecs describe-task-definition --region "${region}" \
  --task-definition "${base_task_definition}" \
  --query taskDefinition --output json >"${tmp_dir}/base-task-definition.json"
jq -e --arg account_id "${account_id}" '
  .taskRoleArn == "arn:aws:iam::\($account_id):role/home-search-budget-production-secret-bootstrap-runtime"
  and .executionRoleArn == "arn:aws:iam::\($account_id):role/home-search-budget-production-secret-bootstrap-execution"
  and .networkMode == "bridge"
  and (.containerDefinitions | length) == 1
  and (.containerDefinitions[0].image | test("^[0-9]{12}\\.dkr\\.ecr\\.ap-northeast-2\\.amazonaws\\.com/home-search/ops-bootstrap@sha256:[0-9a-f]{64}$"))
' "${tmp_dir}/base-task-definition.json" >/dev/null

jq --rawfile script "${normalizer}" '
  {
    family: "home-search-budget-production-generated-value-normalization",
    taskRoleArn: .taskRoleArn,
    executionRoleArn: .executionRoleArn,
    networkMode: .networkMode,
    containerDefinitions: [
      .containerDefinitions[0]
      | .name = "generated-value-normalization"
      | .entryPoint = ["/bin/bash"]
      | .command = ["-Eeuo", "pipefail", "-c", $script]
      | .environment = [{name:"BUDGET_PARAMETER_PREFIX",value:"/home-search/budget-production"}]
      | .secrets = []
      | del(.healthCheck)
    ],
    volumes: (.volumes // []),
    placementConstraints: (.placementConstraints // []),
    requiresCompatibilities: .requiresCompatibilities,
    cpu: .cpu,
    memory: .memory,
    runtimePlatform: .runtimePlatform,
    enableFaultInjection: (.enableFaultInjection // false),
    tags: [
      {key:"Environment",value:"budget-production"},
      {key:"Purpose",value:"generated-value-normalization"}
    ]
  }
' "${tmp_dir}/base-task-definition.json" >"${tmp_dir}/normalization-task-definition.json"

registered_task_definition="$(aws ecs register-task-definition --region "${region}" \
  --cli-input-json "file://${tmp_dir}/normalization-task-definition.json" \
  --query 'taskDefinition.taskDefinitionArn' --output text)"
[[ "${registered_task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-generated-value-normalization:[0-9]+$ ]]

"${run_task_script}" "${cluster}" "${registered_task_definition}" "${started_by}"
echo '상태: Pass - data-dark에서 generated value 정규화 task를 완료했습니다.'

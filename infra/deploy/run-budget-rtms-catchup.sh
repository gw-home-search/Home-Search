#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?ECS cluster name is required}"
task_definition="${2:?RTMS task definition ARN is required}"
started_by="${3:?started-by token is required}"
first_request_id="${4:?first request id is required}"
repeat_request_id="${5:?repeat request id is required}"
[[ "${cluster}" == home-search-budget-production ]]
[[ "${task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-rtms-daily-refresh:[1-9][0-9]*$ ]]
request_id_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
[[ "${first_request_id}" =~ ${request_id_pattern} && "${repeat_request_id}" =~ ${request_id_pattern} ]]

backup_family=home-search-budget-production-scheduled-backup
for attempt in $(seq 0 12); do
  running="$(aws ecs list-tasks --region ap-northeast-2 --cluster "${cluster}" --family "${backup_family}" --desired-status RUNNING --output json)"
  if jq -e '.taskArns | length == 0' <<<"${running}" >/dev/null; then
    backup_clear=true
    break
  fi
  backup_clear=false
  ((attempt < 12)) || break
  sleep 300
done
[[ "${backup_clear}" == true ]] || {
  echo '상태: Fail - logical backup이 60분 안에 종료되지 않아 RTMS를 시작하지 않습니다.' >&2
  exit 1
}

ml_state="$(aws ecs describe-services --region ap-northeast-2 --cluster "${cluster}" --services ml --output json)"
jq -e '(.failures | length) == 0 and (.services | length) == 1' <<<"${ml_state}" >/dev/null
ml_task_definition="$(jq -er '.services[0].taskDefinition' <<<"${ml_state}")"
ml_desired="$(jq -er '.services[0].desiredCount' <<<"${ml_state}")"
ml_stopped=false

capacity_json() {
  local instances instance
  instances="$(aws ecs list-container-instances --region ap-northeast-2 --cluster "${cluster}" --status ACTIVE --output json)"
  jq -e '.containerInstanceArns | length == 1' <<<"${instances}" >/dev/null
  instance="$(jq -er '.containerInstanceArns[0]' <<<"${instances}")"
  aws ecs describe-container-instances --region ap-northeast-2 --cluster "${cluster}" \
    --container-instances "${instance}" --output json
}

capacity_sufficient() {
  jq -e '
    (.containerInstances | length) == 1
    and ([.containerInstances[0].remainingResources[] | select(.name == "CPU") | .integerValue][0] >= 512)
    and ([.containerInstances[0].remainingResources[] | select(.name == "MEMORY") | .integerValue][0] >= 1024)
  ' <<<"$1" >/dev/null
}

restore_ml() {
  [[ "${ml_stopped}" == true ]] || return 0
  aws ecs update-service --region ap-northeast-2 --cluster "${cluster}" --service ml \
    --task-definition "${ml_task_definition}" --desired-count "${ml_desired}" >/dev/null
  aws ecs wait services-stable --region ap-northeast-2 --cluster "${cluster}" --services ml
  local restored
  restored="$(aws ecs describe-services --region ap-northeast-2 --cluster "${cluster}" --services ml --output json)"
  jq -e --arg task_definition "${ml_task_definition}" --argjson desired "${ml_desired}" '
    .services[0].taskDefinition == $task_definition
    and .services[0].desiredCount == $desired and .services[0].runningCount == $desired
    and .services[0].pendingCount == 0
    and all(.services[0].deployments[]; .status != "PRIMARY" or .rolloutState == "COMPLETED")
  ' <<<"${restored}" >/dev/null
  if ((ml_desired > 0)); then
    local search complex_id detail
    search="$(curl --fail-with-body --silent --show-error --get --data-urlencode 'q=마포래미안푸르지오' \
      https://homesearch.world/api/v1/search/complexes)"
    complex_id="$(jq -er '.[0].complexId' <<<"${search}")"
    for _ in $(seq 1 12); do
      detail="$(curl --fail-with-body --silent --show-error "https://homesearch.world/api/v1/complex/${complex_id}")"
      jq -e '.prediction.status == "READY"' <<<"${detail}" >/dev/null && return 0
      sleep 5
    done
    return 1
  fi
}

capacity="$(capacity_json)"
if ! capacity_sufficient "${capacity}"; then
  ((ml_desired > 0)) || {
    echo '상태: Fail - ML 중단 없이도 RTMS capacity 기준을 충족하지 못합니다.' >&2
    exit 1
  }
  aws ecs update-service --region ap-northeast-2 --cluster "${cluster}" --service ml --desired-count 0 >/dev/null
  aws ecs wait services-stable --region ap-northeast-2 --cluster "${cluster}" --services ml
  ml_stopped=true
  stopped="$(aws ecs describe-services --region ap-northeast-2 --cluster "${cluster}" --services ml --output json)"
  jq -e '.services[0].runningCount == 0 and .services[0].pendingCount == 0' <<<"${stopped}" >/dev/null
  search="$(curl --fail-with-body --silent --show-error --get --data-urlencode 'q=마포래미안푸르지오' \
    https://homesearch.world/api/v1/search/complexes)"
  complex_id="$(jq -er '.[0].complexId' <<<"${search}")"
  detail="$(curl --fail-with-body --silent --show-error "https://homesearch.world/api/v1/complex/${complex_id}")"
  jq -e '.prediction.status == "UNAVAILABLE"' <<<"${detail}" >/dev/null
  capacity="$(capacity_json)"
  if ! capacity_sufficient "${capacity}"; then
    restore_ml || {
      echo '상태: Fail - ML_RECOVERY_CRITICAL: capacity 실패 뒤 ML 복구에 실패했습니다.' >&2
      exit 70
    }
    echo '상태: Fail - ML 중단 후에도 RTMS capacity 기준을 충족하지 못합니다.' >&2
    exit 1
  fi
fi

run_status=0
BUDGET_RTMS_TASK_TIMEOUT_SECONDS=5400 infra/deploy/run-budget-ecs-task.sh \
  "${cluster}" "${task_definition}" "${started_by}" \
  "$(jq -cn --arg id "${first_request_id}" '["requestId="+$id]')" >/dev/null || run_status=$?
if ((run_status == 0)); then
  BUDGET_RTMS_TASK_TIMEOUT_SECONDS=5400 infra/deploy/run-budget-ecs-task.sh \
    "${cluster}" "${task_definition}" "${started_by}" \
    "$(jq -cn --arg id "${repeat_request_id}" '["requestId="+$id]')" >/dev/null || run_status=$?
fi
restore_ml || {
  echo '상태: Fail - ML_RECOVERY_CRITICAL: RTMS 종료 뒤 ML 복구에 실패했습니다.' >&2
  exit 70
}
exit "${run_status}"

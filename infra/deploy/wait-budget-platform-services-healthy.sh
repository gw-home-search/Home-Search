#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?ECS cluster name is required}"
region='ap-northeast-2'
attempts="${BUDGET_PLATFORM_HEALTH_ATTEMPTS:-60}"
interval_seconds="${BUDGET_PLATFORM_HEALTH_INTERVAL_SECONDS:-10}"
[[ "${cluster}" == 'home-search-budget-production' ]]
[[ "${attempts}" =~ ^[1-9][0-9]*$ && "${interval_seconds}" =~ ^[0-9]+$ ]]

for ((attempt = 1; attempt <= attempts; attempt++)); do
  service_state="$(aws ecs describe-services --region "${region}" --cluster "${cluster}" \
    --services budget-postgres budget-valkey --output json)"
  if jq -e '
    (.failures | length) == 0
    and (.services | length) == 2
    and ([.services[].serviceName] | sort) == ["budget-postgres", "budget-valkey"]
    and all(.services[]; .desiredCount == 1 and .runningCount == 1 and .pendingCount == 0)
  ' <<<"${service_state}" >/dev/null; then
    all_healthy=true
    for service in budget-postgres budget-valkey; do
      tasks="$(aws ecs list-tasks --region "${region}" --cluster "${cluster}" \
        --service-name "${service}" --desired-status RUNNING --output json)"
      task_arn="$(jq -er '.taskArns | select(length == 1) | .[0]' <<<"${tasks}")" || {
        all_healthy=false
        break
      }
      description="$(aws ecs describe-tasks --region "${region}" --cluster "${cluster}" \
        --tasks "${task_arn}" --output json)"
      jq -e '
        (.failures | length) == 0
        and (.tasks | length) == 1
        and .tasks[0].lastStatus == "RUNNING"
        and .tasks[0].healthStatus == "HEALTHY"
        and (.tasks[0].containers | length) > 0
        and all(.tasks[0].containers[]; .lastStatus == "RUNNING" and .healthStatus == "HEALTHY")
      ' <<<"${description}" >/dev/null || {
        all_healthy=false
        break
      }
    done
    if [[ "${all_healthy}" == 'true' ]]; then
      echo '상태: Pass - budget PostgreSQL/Valkey service와 container가 모두 HEALTHY입니다.'
      exit 0
    fi
  fi
  if (( attempt < attempts )); then
    sleep "${interval_seconds}"
  fi
done

echo '상태: Fail - budget PostgreSQL/Valkey가 제한 시간 안에 HEALTHY가 되지 않았습니다.' >&2
exit 1

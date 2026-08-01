#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

cluster="${1:?cluster ARN is required}"
output="${2:?output path is required}"
platform_output="${3:?platform output path is required}"
application_allowlist=(property-api admin-api user-api ai chat-bff public-gateway admin-gateway ml)
platform_allowlist=(budget-postgres budget-valkey)
application_map='{}'
platform_map='{}'
cluster_description="$(aws ecs describe-clusters --clusters "${cluster}" --output json)"
if [[ "$(jq '.failures | length' <<<"${cluster_description}")" != '0' ]]; then
  if jq -e '(.clusters | length) == 0 and (.failures | length) > 0 and all(.failures[]; .reason == "MISSING")' \
    <<<"${cluster_description}" >/dev/null; then
    jq -n --arg cluster "${cluster}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{format_version:1,cluster:$cluster,cluster_exists:false,captured_at:$captured_at,services:{}}' >"${output}"
    jq -n --arg cluster "${cluster}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{format_version:1,cluster:$cluster,cluster_exists:false,captured_at:$captured_at,services:{}}' >"${platform_output}"
    exit 0
  fi
  jq -c '{failures:[.failures[] | {arn,reason,detail}]}' <<<"${cluster_description}" >&2
  echo '상태: Fail - ECS cluster 상태를 확인할 수 없습니다.' >&2
  exit 1
fi
jq -e '(.clusters | length) == 1 and .clusters[0].status == "ACTIVE"' \
  <<<"${cluster_description}" >/dev/null || {
  echo '상태: Fail - rollback state를 캡처할 ECS cluster가 ACTIVE 상태가 아닙니다.' >&2
  exit 1
}
capture_service() {
  local name="$1" target="$2" service task_definition desired_count running_count pending_count deployment_state task updated
  service="$(aws ecs describe-services --cluster "${cluster}" --services "${name}" --query 'services[0]' --output json)"
  name="$(jq -r '.serviceName' <<<"${service}")"
  task_definition="$(jq -r '.taskDefinition' <<<"${service}")"
  desired_count="$(jq -r '.desiredCount' <<<"${service}")"
  running_count="$(jq -r '.runningCount' <<<"${service}")"
  pending_count="$(jq -r '.pendingCount' <<<"${service}")"
  deployment_state="$(jq -r '[.deployments[]? | select(.status == "PRIMARY") | .rolloutState][0] // "UNKNOWN"' <<<"${service}")"
  task="$(aws ecs describe-task-definition --task-definition "${task_definition}" --query 'taskDefinition' --output json)"
  updated="$(jq --arg name "${name}" --arg task_definition "${task_definition}" \
    --argjson desired_count "${desired_count}" --argjson running_count "${running_count}" \
    --argjson pending_count "${pending_count}" --arg deployment_state "${deployment_state}" \
    --argjson images "$(jq '[.containerDefinitions[] | {name,image}]' <<<"${task}")" \
    '. + {($name):{task_definition:$task_definition,desired_count:$desired_count,running_count:$running_count,pending_count:$pending_count,deployment_state:$deployment_state,images:$images}}' <<<"${target}")"
  printf '%s' "${updated}"
}
for name in "${application_allowlist[@]}"; do
  application_map="$(capture_service "${name}" "${application_map}")"
done
for name in "${platform_allowlist[@]}"; do
  platform_map="$(capture_service "${name}" "${platform_map}")"
done
captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n --arg cluster "${cluster}" --arg captured_at "${captured_at}" --argjson services "${application_map}" \
  '{format_version:1,cluster:$cluster,cluster_exists:true,captured_at:$captured_at,services:$services}' >"${output}"
jq -n --arg cluster "${cluster}" --arg captured_at "${captured_at}" --argjson services "${platform_map}" \
  '{format_version:1,cluster:$cluster,cluster_exists:true,captured_at:$captured_at,services:$services}' >"${platform_output}"

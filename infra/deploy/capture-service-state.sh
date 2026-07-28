#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?cluster ARN is required}"
output="${2:?output path is required}"
map='{}'
cluster_description="$(aws ecs describe-clusters --clusters "${cluster}" --output json)"
if [[ "$(jq '.failures | length' <<<"${cluster_description}")" != '0' ]]; then
  if jq -e '(.clusters | length) == 0 and (.failures | length) > 0 and all(.failures[]; .reason == "MISSING")' \
    <<<"${cluster_description}" >/dev/null; then
    jq -n --arg cluster "${cluster}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{format_version:1,cluster:$cluster,cluster_exists:false,captured_at:$captured_at,services:{}}' >"${output}"
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
service_arns="$(aws ecs list-services --cluster "${cluster}" --query 'serviceArns' --output json)"
while IFS= read -r service_arn; do
  [[ -n "${service_arn}" ]] || continue
  service="$(aws ecs describe-services --cluster "${cluster}" --services "${service_arn}" --query 'services[0]' --output json)"
  name="$(jq -r '.serviceName' <<<"${service}")"
  task_definition="$(jq -r '.taskDefinition' <<<"${service}")"
  desired_count="$(jq -r '.desiredCount' <<<"${service}")"
  task="$(aws ecs describe-task-definition --task-definition "${task_definition}" --query 'taskDefinition' --output json)"
  map="$(jq --arg name "${name}" --arg task_definition "${task_definition}" \
    --argjson desired_count "${desired_count}" \
    --argjson images "$(jq '[.containerDefinitions[] | {name,image}]' <<<"${task}")" \
    '. + {($name):{task_definition:$task_definition,desired_count:$desired_count,images:$images}}' <<<"${map}")"
done < <(jq -r '.[]' <<<"${service_arns}")
jq -n --arg cluster "${cluster}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson services "${map}" \
  '{format_version:1,cluster:$cluster,cluster_exists:true,captured_at:$captured_at,services:$services}' >"${output}"

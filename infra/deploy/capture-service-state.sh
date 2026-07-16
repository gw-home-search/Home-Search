#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?cluster ARN is required}"
output="${2:?output path is required}"
map='{}'
service_arns="$(aws ecs list-services --cluster "${cluster}" --query 'serviceArns' --output json)"
while IFS= read -r service_arn; do
  [[ -n "${service_arn}" ]] || continue
  service="$(aws ecs describe-services --cluster "${cluster}" --services "${service_arn}" --query 'services[0]' --output json)"
  name="$(jq -r '.serviceName' <<<"${service}")"
  task_definition="$(jq -r '.taskDefinition' <<<"${service}")"
  task="$(aws ecs describe-task-definition --task-definition "${task_definition}" --query 'taskDefinition' --output json)"
  map="$(jq --arg name "${name}" --arg task_definition "${task_definition}" --argjson images "$(jq '[.containerDefinitions[] | {name,image}]' <<<"${task}")" \
    '. + {($name):{task_definition:$task_definition,images:$images}}' <<<"${map}")"
done < <(jq -r '.[]' <<<"${service_arns}")
jq -n --arg cluster "${cluster}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson services "${map}" \
  '{format_version:1,cluster:$cluster,captured_at:$captured_at,services:$services}' >"${output}"

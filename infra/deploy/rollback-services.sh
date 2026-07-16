#!/usr/bin/env bash
set -Eeuo pipefail

state_file="${1:?previous service state is required}"
[[ -f "${state_file}" ]] || { echo '상태: Fail - rollback state가 없습니다.' >&2; exit 1; }
cluster="$(jq -r '.cluster' "${state_file}")"
count="$(jq '.services | length' "${state_file}")"
(( count > 0 )) || { echo '상태: Fail - 최초 배포에는 rollback할 이전 service revision이 없습니다.' >&2; exit 1; }

services=()
while IFS=$'\t' read -r service task_definition; do
  aws ecs update-service --cluster "${cluster}" --service "${service}" \
    --task-definition "${task_definition}" --force-new-deployment >/dev/null
  services+=("${service}")
done < <(jq -r '.services | to_entries[] | [.key,.value.task_definition] | @tsv' "${state_file}")
aws ecs wait services-stable --cluster "${cluster}" --services "${services[@]}"
echo '상태: Pass - 이전 task definition ARN으로 ECS service rollback을 완료했습니다.'

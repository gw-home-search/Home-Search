#!/usr/bin/env bash
set -Eeuo pipefail

state_file="${1:?previous service state is required}"
[[ -f "${state_file}" ]] || { echo '상태: Fail - rollback state가 없습니다.' >&2; exit 1; }
cluster="$(jq -r '.cluster' "${state_file}")"
count="$(jq '.services | length' "${state_file}")"
(( count > 0 )) || { echo '상태: Fail - 최초 배포에는 rollback할 이전 service revision이 없습니다.' >&2; exit 1; }
jq -e '
  .format_version == 1
  and (.services | type == "object")
  and ([.services[] |
    (.task_definition | type == "string")
    and (.desired_count | type == "number")
    and (.desired_count >= 0)
    and (.desired_count == (.desired_count | floor))
  ] | all)
' "${state_file}" >/dev/null || {
  echo '상태: Fail - rollback state 형식이 올바르지 않습니다.' >&2
  exit 1
}

services=()
while IFS=$'\t' read -r service task_definition desired_count; do
  aws ecs update-service --cluster "${cluster}" --service "${service}" \
    --task-definition "${task_definition}" --desired-count "${desired_count}" \
    --force-new-deployment >/dev/null
  services+=("${service}")
done < <(jq -r '.services | to_entries[] | [.key,.value.task_definition,.value.desired_count] | @tsv' "${state_file}")
aws ecs wait services-stable --cluster "${cluster}" --services "${services[@]}"
echo '상태: Pass - 이전 task definition ARN으로 ECS service rollback을 완료했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

state_file="${1:?previous service state is required}"
progress_file="${2:-$(dirname "${state_file}")/rollback-progress.json}"
[[ -f "${state_file}" ]] || { echo '상태: Fail - rollback state가 없습니다.' >&2; exit 1; }
cluster="$(jq -r '.cluster' "${state_file}")"
count="$(jq '.services | length' "${state_file}")"
(( count > 0 )) || { echo '상태: Fail - 최초 배포에는 rollback할 이전 service revision이 없습니다.' >&2; exit 1; }
jq -e '
  .format_version == 1
  and (.services | type == "object")
  and ((.services | keys) - ["admin-api","admin-gateway","ai","chat-bff","ml","property-api","public-gateway","user-api"] | length == 0)
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

progress='[]'
rollback_order=(public-gateway chat-bff ai user-api property-api ml admin-api admin-gateway)
smoke_public_search() {
  local body status
  body="$(mktemp)"
  status="$(curl --silent --show-error --get --output "${body}" --write-out '%{http_code}' \
    --data-urlencode 'q=마포' https://homesearch.world/api/v1/search/complexes)"
  [[ "${status}" == 200 ]] && jq -e 'type == "array"' "${body}" >/dev/null
  unlink "${body}"
}
for service in "${rollback_order[@]}"; do
  jq -e --arg service "${service}" '.services | has($service)' "${state_file}" >/dev/null || continue
  task_definition="$(jq -er --arg service "${service}" '.services[$service].task_definition' "${state_file}")"
  desired_count="$(jq -er --arg service "${service}" '.services[$service].desired_count' "${state_file}")"
  aws ecs update-service --cluster "${cluster}" --service "${service}" \
    --task-definition "${task_definition}" --desired-count "${desired_count}" \
    --force-new-deployment >/dev/null
  if ! aws ecs wait services-stable --cluster "${cluster}" --services "${service}"; then
    progress="$(jq --arg service "${service}" --arg reason stable_waiter_failed '. + [{service:$service,status:"fail",reason:$reason}]' <<<"${progress}")"
    jq -n --arg status fail --arg failed_service "${service}" --argjson services "${progress}" \
      '{status:$status,failed_service:$failed_service,services:$services}' >"${progress_file}"
    exit 1
  fi
  if ! smoke_public_search; then
    progress="$(jq --arg service "${service}" --arg reason public_smoke_failed '. + [{service:$service,status:"fail",reason:$reason}]' <<<"${progress}")"
    jq -n --arg status fail --arg failed_service "${service}" --argjson services "${progress}" \
      '{status:$status,failed_service:$failed_service,services:$services}' >"${progress_file}"
    exit 1
  fi
  progress="$(jq --arg service "${service}" --arg task_definition "${task_definition}" --argjson desired_count "${desired_count}" \
    '. + [{service:$service,status:"pass",task_definition:$task_definition,desired_count:$desired_count}]' <<<"${progress}")"
  jq -n --arg status running --argjson services "${progress}" '{status:$status,services:$services}' >"${progress_file}"
done
jq -n --arg status pass --argjson services "${progress}" '{status:$status,services:$services}' >"${progress_file}"
echo '상태: Pass - 이전 task definition ARN으로 ECS service rollback을 완료했습니다.'

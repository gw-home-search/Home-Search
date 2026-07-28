#!/usr/bin/env bash
set -Eeuo pipefail

cluster="${1:?ECS cluster is required}"
phase="${2:?service activation phase is required}"
core_count="${3:?core desired count is required}"
manifest="${4:?release manifest is required}"
output="${5:?evidence output is required}"

[[ "${phase}" == 'off' || "${phase}" == 'consumers' || "${phase}" == 'private' || "${phase}" == 'all' ]] || {
  echo '상태: Fail - 알 수 없는 service activation phase입니다.' >&2
  exit 2
}
[[ "${core_count}" =~ ^[0-9]+$ ]] && ((core_count >= 2)) || {
  echo '상태: Fail - core desired count는 2 이상의 정수여야 합니다.' >&2
  exit 2
}
[[ -f "${manifest}" && ! -L "${manifest}" ]] || {
  echo '상태: Fail - release manifest는 symlink가 아닌 regular file이어야 합니다.' >&2
  exit 2
}
[[ ! -L "${output}" ]] || {
  echo '상태: Fail - activation evidence symlink는 허용하지 않습니다.' >&2
  exit 2
}
output_directory="$(dirname "${output}")"
[[ -d "${output_directory}" && ! -L "${output_directory}" ]] || {
  echo '상태: Fail - activation evidence directory가 안전하지 않습니다.' >&2
  exit 2
}

services=(property-api admin-api user-api public-gateway admin-gateway ml ai chat-bff user-insight-worker)
expected_names="$(printf '%s\n' "${services[@]}" | jq -Rsc 'split("\n")[:-1] | sort')"
jq -e --argjson expected "${expected_names}" '
  .format_version == 2
  and ((.images | keys) as $names | all($expected[]; $names | index(.) != null))
  and ([$expected[] as $name | .images[$name] as $image |
    $image.repository == ("home-search/" + $name)
    and ($image.digest | test("^sha256:[0-9a-f]{64}$"))
    and ($image.uri | test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$"))
    and $image.uri == (($image.uri | split("/")[0]) + "/" + $image.repository + "@" + $image.digest)
  ] | all)
' "${manifest}" >/dev/null || {
  echo '상태: Fail - activation 검증용 release manifest가 유효하지 않습니다.' >&2
  exit 1
}

description="$(aws ecs describe-services --cluster "${cluster}" --services "${services[@]}" --output json)"
jq -e --argjson expected "${expected_names}" '
  (.failures | length) == 0
  and (([.services[].serviceName] | sort) == $expected)
' <<<"${description}" >/dev/null || {
  jq -c '{failures:[.failures[]? | {arn,reason,detail}],services:[.services[]?.serviceName]}' <<<"${description}" >&2
  echo '상태: Fail - production ECS service set을 정확히 조회하지 못했습니다.' >&2
  exit 1
}

evidence='{}'
for name in "${services[@]}"; do
  expected=0
  case "${phase}" in
    consumers)
      if [[ "${name}" == 'user-insight-worker' ]]; then expected="${core_count}"; fi
      ;;
    private)
      if [[ "${name}" != 'public-gateway' ]]; then expected="${core_count}"; fi
      ;;
    all) expected="${core_count}" ;;
  esac
  service="$(jq -c --arg name "${name}" '.services[] | select(.serviceName == $name)' <<<"${description}")"
  jq -e --argjson expected "${expected}" '
    .desiredCount == $expected
    and .runningCount == $expected
    and .pendingCount == 0
    and ([.deployments[] | select(.status == "PRIMARY" and .rolloutState == "COMPLETED")] | length) == 1
    and all(.deployments[]; .rolloutState == "COMPLETED")
  ' <<<"${service}" >/dev/null || {
    jq -c '{serviceName,desiredCount,runningCount,pendingCount,deployments:[.deployments[] | {status,rolloutState,rolloutStateReason}]}' <<<"${service}" >&2
    echo "상태: Fail - ${name} service가 ${phase} 단계의 stable 상태가 아닙니다." >&2
    exit 1
  }

  task_definition="$(jq -r '.taskDefinition' <<<"${service}")"
  task="$(aws ecs describe-task-definition --task-definition "${task_definition}" --query taskDefinition --output json)"
  approved_image="$(jq -r --arg name "${name}" '.images[$name].uri' "${manifest}")"
  jq -e --arg name "${name}" --arg image "${approved_image}" '
    [.containerDefinitions[] | select(.name == $name and .image == $image)] | length == 1
  ' <<<"${task}" >/dev/null || {
    echo "상태: Fail - ${name} task definition image가 approved release digest와 다릅니다." >&2
    exit 1
  }
  evidence="$(jq --arg name "${name}" --arg task_definition "${task_definition}" --arg image "${approved_image}" \
    --argjson desired "${expected}" \
    '. + {($name):{desired_count:$desired,running_count:$desired,pending_count:0,task_definition:$task_definition,image:$image,rollout_state:"COMPLETED"}}' \
    <<<"${evidence}")"
done

temporary="$(mktemp "${output_directory}/.service-activation.XXXXXX")"
cleanup() { unlink "${temporary}" 2>/dev/null || true; }
trap cleanup EXIT
jq -n --arg cluster "${cluster}" --arg phase "${phase}" --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson services "${evidence}" \
  '{format_version:1,cluster:$cluster,phase:$phase,captured_at:$captured_at,services:$services}' >"${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${output}"
trap - EXIT
echo "상태: Pass - ${phase} 단계 ECS stable 상태와 approved image digest를 확인했습니다."

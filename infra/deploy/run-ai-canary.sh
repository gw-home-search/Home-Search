#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

cluster="${1:?cluster is required}"
canary_task_definition="${2:?AI canary task definition ARN is required}"
output="${3:?sanitized canary evidence output is required}"
release_tag="${4:?release tag is required}"
commit_sha="${5:?commit SHA is required}"
[[ "${cluster}" == home-search-budget-production ]]
[[ "${canary_task_definition}" =~ ^arn:aws:ecs:ap-northeast-2:[0-9]{12}:task-definition/home-search-budget-production-ai-canary:[0-9]+$ ]]
[[ "${release_tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ && "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]
[[ ! -e "${output}" && ! -L "${output}" ]]

source="$(aws ecs describe-task-definition --task-definition "${canary_task_definition}" --output json)"
task_arn=''
cleanup() {
  if [[ -n "${task_arn}" ]]; then
    aws ecs stop-task --cluster "${cluster}" --task "${task_arn}" --reason 'AI canary cleanup' >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM
run="$(aws ecs run-task --cluster "${cluster}" --task-definition "${canary_task_definition}" --launch-type EC2 --count 1 \
  --started-by "budget-ai-canary-${GITHUB_RUN_ID:-manual}" --output json)"
jq -e '(.failures | length) == 0 and (.tasks | length) == 1' <<<"${run}" >/dev/null
task_arn="$(jq -er '.tasks[0].taskArn' <<<"${run}")"

healthy_samples=0
description='{}'
for _ in $(seq 1 60); do
  description="$(aws ecs describe-tasks --cluster "${cluster}" --tasks "${task_arn}" --output json)"
  if jq -e '.tasks[0].lastStatus == "RUNNING" and .tasks[0].healthStatus == "HEALTHY"
      and ([.tasks[0].containers[] | select(.name == "ai" and .lastStatus == "RUNNING" and .healthStatus == "HEALTHY")] | length) == 1
      and all(.tasks[0].containers[] | select(.name != "ai");
        (.lastStatus == "RUNNING") or (.lastStatus == "STOPPED" and .exitCode == 0))' <<<"${description}" >/dev/null; then
    healthy_samples=$((healthy_samples + 1))
    [[ "${healthy_samples}" -lt 2 ]] || break
  elif jq -e '.tasks[0].lastStatus == "STOPPED"' <<<"${description}" >/dev/null; then
    break
  fi
  sleep 5
done

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ "${healthy_samples}" -lt 2 ]]; then
  task_id="${task_arn##*/}"
  log_group="$(jq -r '.taskDefinition.containerDefinitions[] | select(.name == "ai") | .logConfiguration.options["awslogs-group"] // empty' <<<"${source}")"
  log_prefix="$(jq -r '.taskDefinition.containerDefinitions[] | select(.name == "ai") | .logConfiguration.options["awslogs-stream-prefix"] // empty' <<<"${source}")"
  sanitized_logs='[]'
  if [[ -n "${log_group}" && -n "${log_prefix}" ]]; then
    raw_logs="$(aws logs get-log-events --log-group-name "${log_group}" --log-stream-name "${log_prefix}/ai/${task_id}" \
      --limit 50 --query 'events[].message' --output json 2>/dev/null || printf '[]')"
    sanitized_logs="$(jq 'map(
      if test("(?i)(authorization|cookie|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|api[_ -]?key|oauth.{0,32}(code|state)|[?&](code|state)=|BEGIN.{0,16}PRIVATE KEY)")
      then "[REDACTED SENSITIVE LOG LINE]"
      else gsub("AKIA[0-9A-Z]{16}"; "[REDACTED]")
        | gsub("[A-Za-z0-9_-]{10,}[.][A-Za-z0-9_-]{10,}[.][A-Za-z0-9_-]{10,}"; "[REDACTED JWT]")
        | gsub("://[^[:space:]/]+:[^[:space:]@]+@"; "://[REDACTED]@")
      end
    )' <<<"${raw_logs}")"
  fi
  jq -n --arg tag "${release_tag}" --arg sha "${commit_sha}" --arg created_at "${created_at}" \
    --argjson logs "${sanitized_logs}" \
    --argjson task "$(jq -c '.tasks[0] | {lastStatus,healthStatus,stopCode,stoppedReason,
      containers:[.containers[] | {name,lastStatus,healthStatus,exitCode,reason}]}' <<<"${description}")" \
    '{status:"fail",release_tag:$tag,commit_sha:$sha,created_at:$created_at,
      checks:{running:false,healthy:false,cleaned_up:false,failure_evidence_capable:true},
      failure:$task,logs:{status:"sanitized",messages:$logs},redactions_applied:true}' >"${output}"
  exit 1
fi

aws ecs stop-task --cluster "${cluster}" --task "${task_arn}" --reason 'AI canary passed' >/dev/null
aws ecs wait tasks-stopped --cluster "${cluster}" --tasks "${task_arn}"
task_arn=''
trap - EXIT HUP INT TERM
jq -n --arg tag "${release_tag}" --arg sha "${commit_sha}" --arg created_at "${created_at}" \
  '{status:"pass",release_tag:$tag,commit_sha:$sha,created_at:$created_at,
    checks:{running:true,healthy:true,cleaned_up:true,failure_evidence_capable:true},redactions_applied:true}' >"${output}"

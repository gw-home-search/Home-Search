#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
cleanup() { find "${work}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "${work}/bin"
cat >"${work}/bin/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"${MOCK_AWS_LOG}"
case "$1 $2" in
  'ecs describe-task-definition')
    jq -n '{taskDefinition:{containerDefinitions:[{name:"ai",logConfiguration:{options:{"awslogs-group":"/home-search/budget-production/ai","awslogs-stream-prefix":"ecs"}}}]}}'
    ;;
  'ecs run-task')
    jq -n '{failures:[],tasks:[{taskArn:"arn:aws:ecs:ap-northeast-2:123456789012:task/home-search-budget-production/task-1"}]}'
    ;;
  'ecs describe-tasks')
    if [[ "${FAKE_CANARY_SUCCESS:-}" == 1 ]]; then
      jq -n '{tasks:[{lastStatus:"RUNNING",healthStatus:"HEALTHY",containers:[{name:"ai",lastStatus:"RUNNING",healthStatus:"HEALTHY"}]}]}'
    else
      jq -n '{tasks:[{lastStatus:"STOPPED",healthStatus:"UNHEALTHY",stopCode:"EssentialContainerExited",stoppedReason:"fixture",containers:[{name:"ai",lastStatus:"STOPPED",healthStatus:"UNHEALTHY",exitCode:1,reason:"fixture"}]}]}'
    fi
    ;;
  'logs get-log-events')
    jq -n '["authorization: Bearer token-value-must-not-survive","jdbc:postgresql://user:password@db.internal/home","safe stack trace"]'
    ;;
  'ecs stop-task') ;;
  'ecs wait') ;;
  *) exit 2 ;;
esac
SCRIPT
chmod 0555 "${work}/bin/aws"
cat >"${work}/bin/sleep" <<'SCRIPT'
#!/usr/bin/env bash
exit 0
SCRIPT
chmod 0555 "${work}/bin/sleep"

output="${work}/ai-canary.json"
export MOCK_AWS_LOG="${work}/aws.log"
: >"${MOCK_AWS_LOG}"
if PATH="${work}/bin:${PATH}" "${root}/infra/deploy/run-ai-canary.sh" \
  home-search-budget-production \
  arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-ai-canary:1 \
  "${output}" v1.0.24 "$(printf 'a%.0s' {1..40})"; then
  echo '상태: Fail - 실패 canary fixture를 성공으로 판정했습니다.' >&2
  exit 1
fi
[[ -f "${output}" ]] || { cat "${MOCK_AWS_LOG}" >&2; exit 1; }
if ! jq -e '.status == "fail" and .redactions_applied == true and .logs.status == "sanitized"
  and (.logs.messages | index("safe stack trace") != null)
  and ([.logs.messages[] | select(. == "[REDACTED SENSITIVE LOG LINE]")] | length) == 2' "${output}" >/dev/null; then
  cat "${output}" >&2
  exit 1
fi
if grep -Eq 'token-value-must-not-survive|user:password' "${output}"; then
  echo '상태: Fail - AI canary 증거에 secret fixture가 남았습니다.' >&2
  exit 1
fi
success_output="${work}/ai-canary-success.json"
FAKE_CANARY_SUCCESS=1 PATH="${work}/bin:${PATH}" "${root}/infra/deploy/run-ai-canary.sh" \
  home-search-budget-production \
  arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-ai-canary:1 \
  "${success_output}" v1.0.24 "$(printf 'b%.0s' {1..40})"
jq -e '.status == "pass" and .checks.running and .checks.healthy and .checks.cleaned_up
  and .checks.failure_evidence_capable and .redactions_applied' "${success_output}" >/dev/null
echo '상태: Pass - AI canary 실패 증거의 stopped reason과 sanitized log를 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root_dir}/infra/deploy/run-budget-generated-value-normalization.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

mkdir -p "${tmp_dir}/bin"
cat >"${tmp_dir}/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >>"${FAKE_AWS_LOG}"
printf '\n' >>"${FAKE_AWS_LOG}"
case "${1:-} ${2:-}" in
  'sts get-caller-identity') printf '%s\n' '399291871263' ;;
  'ecs describe-services')
    if [[ "${FAKE_SERVICE_ACTIVE:-false}" == 'true' ]]; then
      printf '%s\n' '{"failures":[],"services":[{"serviceName":"budget-postgres","desiredCount":1,"runningCount":1,"pendingCount":0},{"serviceName":"budget-valkey","desiredCount":0,"runningCount":0,"pendingCount":0}]}'
    else
      printf '%s\n' '{"failures":[],"services":[{"serviceName":"budget-postgres","desiredCount":0,"runningCount":0,"pendingCount":0},{"serviceName":"budget-valkey","desiredCount":0,"runningCount":0,"pendingCount":0}]}'
    fi
    ;;
  'ecs describe-task-definition')
    cat <<'JSON'
{"taskRoleArn":"arn:aws:iam::399291871263:role/home-search-budget-production-secret-bootstrap-runtime","executionRoleArn":"arn:aws:iam::399291871263:role/home-search-budget-production-secret-bootstrap-execution","networkMode":"bridge","containerDefinitions":[{"name":"secret-bootstrap","image":"399291871263.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ops-bootstrap@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","essential":true,"environment":[],"secrets":[],"healthCheck":{"command":["CMD-SHELL","false"]}}],"volumes":[],"placementConstraints":[],"requiresCompatibilities":["EC2"],"cpu":"256","memory":"512","runtimePlatform":{"cpuArchitecture":"X86_64","operatingSystemFamily":"LINUX"},"enableFaultInjection":false}
JSON
    ;;
  'ecs register-task-definition')
    input=''
    for ((index = 1; index <= $#; index++)); do
      if [[ "${!index}" == '--cli-input-json' ]]; then
        next=$((index + 1))
        input="${!next}"
      fi
    done
    jq -e '
      .family == "home-search-budget-production-generated-value-normalization"
      and .containerDefinitions[0].entryPoint == ["/bin/bash"]
      and (.containerDefinitions[0].command[3] | contains("stage_normalized_random"))
      and (.containerDefinitions[0] | has("healthCheck") | not)
      and ([.tags[].key] | sort) == ["Environment", "Purpose"]
    ' "${input#file://}" >/dev/null
    printf '%s\n' 'arn:aws:ecs:ap-northeast-2:399291871263:task-definition/home-search-budget-production-generated-value-normalization:1'
    ;;
  'ecs deregister-task-definition') ;;
  *) exit 2 ;;
esac
FAKE_AWS
cat >"${tmp_dir}/fake-run-task" <<'FAKE_RUN'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >"${FAKE_RUN_LOG}"
FAKE_RUN
chmod +x "${tmp_dir}/bin/aws" "${tmp_dir}/fake-run-task"
export PATH="${tmp_dir}/bin:${PATH}"
export FAKE_AWS_LOG="${tmp_dir}/aws.log"
export FAKE_RUN_LOG="${tmp_dir}/run.log"
: >"${FAKE_AWS_LOG}"

BUDGET_RUN_TASK_SCRIPT="${tmp_dir}/fake-run-task" "${script}" \
  home-search-budget-production \
  arn:aws:ecs:ap-northeast-2:399291871263:task-definition/home-search-budget-production-secret-bootstrap:1 \
  budget-test-run >"${tmp_dir}/success.out"
grep -Fq 'home-search-budget-production-generated-value-normalization:1 budget-test-run' "${FAKE_RUN_LOG}"
grep -Fq 'ecs deregister-task-definition' "${FAKE_AWS_LOG}"

registers_before="$(grep -c 'ecs register-task-definition' "${FAKE_AWS_LOG}")"
set +e
FAKE_SERVICE_ACTIVE=true BUDGET_RUN_TASK_SCRIPT="${tmp_dir}/fake-run-task" "${script}" \
  home-search-budget-production \
  arn:aws:ecs:ap-northeast-2:399291871263:task-definition/home-search-budget-production-secret-bootstrap:1 \
  budget-test-active >"${tmp_dir}/active.out" 2>"${tmp_dir}/active.err"
active_code=$?
set -e
[[ "${active_code}" == '1' ]]
[[ "$(grep -c 'ecs register-task-definition' "${FAKE_AWS_LOG}")" == "${registers_before}" ]]

echo '상태: Pass - dark-only normalization task 등록·실행·정리를 확인했습니다.'

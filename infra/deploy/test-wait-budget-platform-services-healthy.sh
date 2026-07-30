#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root_dir}/infra/deploy/wait-budget-platform-services-healthy.sh"
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
  'ecs describe-services')
    if [[ "${FAKE_SERVICE_COUNTS:-healthy}" == 'zero' ]]; then
      printf '%s\n' '{"failures":[],"services":[{"serviceName":"budget-postgres","desiredCount":1,"runningCount":1,"pendingCount":0},{"serviceName":"budget-valkey","desiredCount":1,"runningCount":0,"pendingCount":0}]}'
    else
      printf '%s\n' '{"failures":[],"services":[{"serviceName":"budget-postgres","desiredCount":1,"runningCount":1,"pendingCount":0},{"serviceName":"budget-valkey","desiredCount":1,"runningCount":1,"pendingCount":0}]}'
    fi
    ;;
  'ecs list-tasks')
    service=''
    for ((index = 1; index <= $#; index++)); do
      if [[ "${!index}" == '--service-name' ]]; then
        next=$((index + 1))
        service="${!next}"
      fi
    done
    printf '{"taskArns":["arn:aws:ecs:ap-northeast-2:399291871263:task/home-search-budget-production/%s"]}\n' "${service}"
    ;;
  'ecs describe-tasks')
    if [[ "${FAKE_TASK_HEALTH:-healthy}" == 'unknown' ]]; then
      printf '%s\n' '{"failures":[],"tasks":[{"lastStatus":"RUNNING","healthStatus":"UNKNOWN","containers":[{"lastStatus":"RUNNING","healthStatus":"UNKNOWN"}]}]}'
    else
      printf '%s\n' '{"failures":[],"tasks":[{"lastStatus":"RUNNING","healthStatus":"HEALTHY","containers":[{"lastStatus":"RUNNING","healthStatus":"HEALTHY"}]}]}'
    fi
    ;;
  *) exit 2 ;;
esac
FAKE_AWS
chmod +x "${tmp_dir}/bin/aws"
export PATH="${tmp_dir}/bin:${PATH}"
export FAKE_AWS_LOG="${tmp_dir}/aws.log"
: >"${FAKE_AWS_LOG}"

BUDGET_PLATFORM_HEALTH_ATTEMPTS=1 BUDGET_PLATFORM_HEALTH_INTERVAL_SECONDS=0 \
  "${script}" home-search-budget-production >"${tmp_dir}/healthy.out"
grep -Fq '상태: Pass' "${tmp_dir}/healthy.out"

set +e
FAKE_TASK_HEALTH=unknown BUDGET_PLATFORM_HEALTH_ATTEMPTS=1 BUDGET_PLATFORM_HEALTH_INTERVAL_SECONDS=0 \
  "${script}" home-search-budget-production >"${tmp_dir}/unknown.out" 2>"${tmp_dir}/unknown.err"
unknown_code=$?
FAKE_SERVICE_COUNTS=zero BUDGET_PLATFORM_HEALTH_ATTEMPTS=1 BUDGET_PLATFORM_HEALTH_INTERVAL_SECONDS=0 \
  "${script}" home-search-budget-production >"${tmp_dir}/zero.out" 2>"${tmp_dir}/zero.err"
zero_code=$?
set -e
[[ "${unknown_code}" == '1' && "${zero_code}" == '1' ]]
grep -Fq '상태: Fail' "${tmp_dir}/unknown.err" "${tmp_dir}/zero.err"

echo '상태: Pass - platform service count와 task/container HEALTHY gate를 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/run-budget-rtms-catchup.sh"
bash -n "${script}"
for expected in \
  '--desired-status RUNNING' 'sleep 300' \
  'remainingResources[] | select(.name == "CPU")' 'integerValue][0] >= 512' \
  'remainingResources[] | select(.name == "MEMORY")' 'integerValue][0] >= 1024' \
  '--service ml --desired-count 0' '--task-definition "${ml_task_definition}" --desired-count "${ml_desired}"' \
  'BUDGET_RTMS_TASK_TIMEOUT_SECONDS=5400' 'if ((run_status == 0)); then' \
  'ML_RECOVERY_CRITICAL' '.prediction.status == "UNAVAILABLE"' '.prediction.status == "READY"'; do
  grep -Fq -- "${expected}" "${script}"
done
! grep -Fq -- '--desired-status PENDING' "${script}"
if bash "${script}" home-search-budget-production \
  arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-rtms-daily-refresh:1 \
  test-started-by 00000000-0000-0000-0000-00000000000z 00000000-0000-0000-0000-000000000002 >/dev/null 2>&1; then
  echo '상태: Fail - canonical UUID가 아닌 RTMS request ID를 허용했습니다.' >&2
  exit 1
fi
echo '상태: Pass - RTMS catch-up의 backup/capacity/ML 복구/first-repeat 제한을 확인했습니다.'

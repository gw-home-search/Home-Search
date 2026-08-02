#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/run-budget-ecs-task.sh"
bash -n "${script}"
for expected in '--launch-type EC2' '--count 1' '--started-by' '--overrides' 'containerOverrides' 'property-flyway override는 exact V40 validate만 허용합니다.' 'scheduled-backup override는 release별 property search audit만 허용합니다.' 'RTMS 수동 실행은 canonical requestId 하나만 허용합니다.' '뉴스 bootstrap은 BOOTSTRAP canonical requestId만 허용합니다.' 'policyVersion=NEWS_V5' 'timeout 7200' 'all(.tasks[0].containers[]; .exitCode == 0)' 'aws ecs stop-task' 'completed=false'; do
  grep -Fq -- "${expected}" "${script}"
done
! grep -Fq -- '--launch-type FARGATE' "${script}"
echo '상태: Pass - budget one-shot task의 EC2 launch, 단일 실행, exit-code gate를 확인했습니다.'

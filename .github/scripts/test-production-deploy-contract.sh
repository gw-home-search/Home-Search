#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="${root}/.github/workflows/deploy-production.yml"
rollback="${root}/.github/workflows/rollback-supervisor-graph.yml"
grep -Fq 'environment: production' "${workflow}"
grep -Fq '(.images | length == 17)' "${workflow}"
grep -Fq '.vulnerability_policy_gate_passed' "${workflow}"
grep -Fq '[[ "${release_sha}" == "${GITHUB_SHA}" ]]' "${workflow}"
grep -Fq 'index("delete")' "${workflow}"
grep -Fq 'capture-service-state.sh' "${workflow}"
grep -Fq 'for task in property-flyway admin-migration user-flyway source-data-migration' "${workflow}"
grep -Fq 'HOME_AI_SUPERVISOR_GRAPH_MODE",value:"off"' "${rollback}"
if grep -Eq 'down -v|volume (rm|prune)|flyway.*clean|terraform destroy' "${workflow}" "${rollback}"; then
  echo '상태: Fail - production workflow에 destructive rollback이 포함됐습니다.' >&2
  exit 1
fi
echo '상태: Pass - immutable manifest, approval, 0 destroy, migration ordering, Graph-only rollback 계약을 확인했습니다.'

#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="${root}/infra/budget/run-recovery-rehearsal.sh"
terraform="${root}/infra/terraform/budget-production/recovery.tf"

bash -n "${runner}"
for required in \
  'shutdown -h +240' \
  '--instance-type t3a.large' \
  "--credit-specification 'CpuCredits=unlimited'" \
  'CpuCredits=standard' \
  'HttpPutResponseHopLimit=1' \
  'Purpose,Value=budget-production-recovery' \
  'Environment,Value=budget-production' \
  'RunId,Value=${run_id}' \
  '--timeout-seconds 14400' \
  '--network none' \
  'create-volume' \
  'xfs_repair -n' \
  'map_marker_active_generation' \
  'delete-volume' \
  'actual_run_id' \
  'terminate-instances' \
  'restore-evidence/${run_id}'; do
  grep -Fq -- "${required}" "${runner}"
done

grep -Fq 'intentionally no ingress' "${terraform}"
! grep -Fq 'aws_vpc_security_group_ingress_rule' "${terraform}"
grep -Fq 'repository/home-search/backup' "${terraform}"
grep -Fq 'aws_ecr_repository.platform["budget-postgres"].arn' "${terraform}"
grep -Fq '"${aws_s3_bucket.backup[0].arn}/logical/*"' "${terraform}"
grep -Fq '"${aws_s3_bucket.backup[0].arn}/restore-evidence/*"' "${terraform}"

echo '상태: Pass - ephemeral recovery runner의 ingress 0, 4시간 제한, tag 재검증 종료 경계를 확인했습니다.'

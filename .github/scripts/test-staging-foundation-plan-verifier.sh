#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="${root}/infra/deploy/verify-staging-foundation-plan.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

jq -n '
  def change($address; $type; $after): {
    address:$address, mode:"managed", type:$type,
    change:{actions:["create"],after:$after}
  };
  ([
    change("aws_vpc.this";"aws_vpc";{tags_all:{Environment:"staging"}}),
    change("aws_db_instance.primary";"aws_db_instance";{tags_all:{Environment:"staging"}}),
    change("aws_lb.public";"aws_lb";{tags_all:{Environment:"staging"}}),
    change("aws_ecs_cluster.this";"aws_ecs_cluster";{tags_all:{Environment:"staging"}}),
    change("aws_ecs_service.user_insight_worker";"aws_ecs_service";{desired_count:0,tags_all:{Environment:"staging"}}),
    change("aws_scheduler_schedule.database_backup[\"daily\"]";"aws_scheduler_schedule";{state:"DISABLED",tags_all:{Environment:"staging"}})
  ] + ([
    "secret-bootstrap", "database-bootstrap", "property-flyway", "admin-migration",
    "user-flyway", "source-data-migration", "runtime-grants"
  ] | map(
    change("aws_ecs_task_definition.one_shot[\"\(.)\"]";"aws_ecs_task_definition";{tags_all:{Environment:"staging"}})
  ))) as $changes | {resource_changes:$changes}
' >"${tmp_dir}/pass.json"

"${verifier}" "${tmp_dir}/pass.json" >/dev/null

jq '(.resource_changes[] | select(.address == "aws_vpc.this") | .change.actions) = ["delete","create"]' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/destroy.json"
if "${verifier}" "${tmp_dir}/destroy.json" >/dev/null 2>&1; then
  echo '상태: Fail - foundation verifier가 destroy를 허용했습니다.' >&2
  exit 1
fi

jq '(.resource_changes[] | select(.address == "aws_ecs_service.user_insight_worker") | .change.after.desired_count) = 1' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/service.json"
if "${verifier}" "${tmp_dir}/service.json" >/dev/null 2>&1; then
  echo '상태: Fail - foundation verifier가 service 활성화를 허용했습니다.' >&2
  exit 1
fi

jq '(.resource_changes[] | select(.type == "aws_scheduler_schedule") | .change.after.state) = "ENABLED"' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/schedule.json"
if "${verifier}" "${tmp_dir}/schedule.json" >/dev/null 2>&1; then
  echo '상태: Fail - foundation verifier가 schedule 활성화를 허용했습니다.' >&2
  exit 1
fi

jq '(.resource_changes[] | select(.address == "aws_vpc.this") | .change.after.tags_all.Environment) = "production"' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/production.json"
if "${verifier}" "${tmp_dir}/production.json" >/dev/null 2>&1; then
  echo '상태: Fail - foundation verifier가 production tag resource를 허용했습니다.' >&2
  exit 1
fi

jq 'del(.resource_changes[] | select(.address == "aws_ecs_task_definition.one_shot[\"source-data-migration\"]"))' \
  "${tmp_dir}/pass.json" >"${tmp_dir}/missing-bootstrap-task.json"
if "${verifier}" "${tmp_dir}/missing-bootstrap-task.json" >/dev/null 2>&1; then
  echo '상태: Fail - foundation verifier가 누락된 bootstrap task definition을 허용했습니다.' >&2
  exit 1
fi

echo '상태: Pass - staging foundation plan verifier의 pass/destroy/service/schedule/environment/task fixture를 확인했습니다.'

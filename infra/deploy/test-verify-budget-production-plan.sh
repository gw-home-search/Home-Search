#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/verify-budget-production-plan.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_ecr_repository.platform[\"budget-postgres\"]",type:"aws_ecr_repository",change:{actions:["create"],after:{name:"home-search/budget-postgres"}}},
  {mode:"managed",address:"aws_ecr_lifecycle_policy.platform[\"budget-postgres\"]",type:"aws_ecr_lifecycle_policy",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/registry.json"
"${script}" "${tmp_dir}/registry.json" registry registry >/dev/null

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_instance.host[0]",type:"aws_instance",change:{actions:["create"],after:{tags_all:{Environment:"budget-production"}}}}
]}' >"${tmp_dir}/foundation.json"
"${script}" "${tmp_dir}/foundation.json" foundation registry >/dev/null

if "${script}" "${tmp_dir}/foundation.json" foundation private >/dev/null 2>&1; then
  echo '상태: Fail - phase backslide를 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].change.actions = ["delete","create"]' \
  "${tmp_dir}/foundation.json" >"${tmp_dir}/replacement.json"
if "${script}" "${tmp_dir}/replacement.json" foundation registry >/dev/null 2>&1; then
  echo '상태: Fail - replacement/delete plan을 허용했습니다.' >&2
  exit 1
fi

old_image='399291871263.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
new_image='399291871263.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
jq -n --arg old_image "${old_image}" --arg new_image "${new_image}" '{resource_changes:[
  {mode:"managed",address:"aws_ecs_task_definition.platform[\"budget-postgres\"]",type:"aws_ecs_task_definition",change:{
    actions:["delete","create"],
    replace_paths:[["container_definitions"]],
    before:{family:"home-search-budget-production-budget-postgres",skip_destroy:true,container_definitions:([{name:"budget-postgres",image:$old_image,essential:true}] | tojson),tags:{Service:"budget-postgres",Release:"v1.0.9"},tags_all:{Environment:"budget-production",Service:"budget-postgres",Release:"v1.0.9"},arn:"old",arn_without_revision:"old",id:"old",revision:1},
    after:{family:"home-search-budget-production-budget-postgres",skip_destroy:true,container_definitions:([{name:"budget-postgres",image:$new_image,essential:true}] | tojson),tags:{Service:"budget-postgres",Release:"v1.0.10"},tags_all:{Environment:"budget-production",Service:"budget-postgres",Release:"v1.0.10"},arn:null,arn_without_revision:null,id:null,revision:null}
  }}
]}' >"${tmp_dir}/task-definition-release.json"
"${script}" "${tmp_dir}/task-definition-release.json" data data >/dev/null
if "${script}" "${tmp_dir}/task-definition-release.json" foundation foundation >/dev/null 2>&1; then
  echo '상태: Fail - data resume 밖에서 task definition revision을 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].change.after.pid_mode = "host"' \
  "${tmp_dir}/task-definition-release.json" >"${tmp_dir}/task-definition-host-pid.json"
if "${script}" "${tmp_dir}/task-definition-host-pid.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - task definition host PID 변경을 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].change.after.tags.Release = "latest"
    | .resource_changes[0].change.after.tags_all.Release = "latest"' \
  "${tmp_dir}/task-definition-release.json" >"${tmp_dir}/task-definition-mutable-release.json"
if "${script}" "${tmp_dir}/task-definition-mutable-release.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - canonical release tag가 아닌 task revision을 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].change.after.container_definitions |=
      (fromjson | .[0].environment = [{name:"UNREVIEWED",value:"true"}] | tojson)' \
  "${tmp_dir}/task-definition-release.json" >"${tmp_dir}/task-definition-drift.json"
if "${script}" "${tmp_dir}/task-definition-drift.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - image 외 task definition 변경을 허용했습니다.' >&2
  exit 1
fi

jq '.resource_changes[0].address = "aws_ecs_task_definition.platform[\"unreviewed\"]"' \
  "${tmp_dir}/task-definition-release.json" >"${tmp_dir}/task-definition-address.json"
if "${script}" "${tmp_dir}/task-definition-address.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - 허용 목록 밖 task definition revision을 허용했습니다.' >&2
  exit 1
fi

jq '
  .resource_changes[0].address = "aws_ecs_task_definition.one_shot[\"data-import-reconcile\"]"
  | .resource_changes[0].change.before.family = "home-search-budget-production-data-import-reconcile"
  | .resource_changes[0].change.after.family = "home-search-budget-production-data-import-reconcile"
  | .resource_changes[0].change.before.container_definitions |=
      (fromjson | .[0].name = "data-import-reconcile" | .[0].image |= sub("budget-postgres"; "backup")
        | .[0].environment = [{name:"HOME_MIGRATION_EVIDENCE_S3_URI",value:"s3://home-search-budget-production-backup-399291871263/deployment-evidence/v1.0.9"}] | tojson)
  | .resource_changes[0].change.after.container_definitions |=
      (fromjson | .[0].name = "data-import-reconcile" | .[0].image |= sub("budget-postgres"; "backup")
        | .[0].environment = [{name:"HOME_MIGRATION_EVIDENCE_S3_URI",value:"s3://home-search-budget-production-backup-399291871263/deployment-evidence/v1.0.10"}] | tojson)
  ' "${tmp_dir}/task-definition-release.json" >"${tmp_dir}/data-import-release.json"
"${script}" "${tmp_dir}/data-import-release.json" data data >/dev/null

jq '.resource_changes[0].change.after.container_definitions |=
      (fromjson | .[0].environment += [{name:"UNREVIEWED",value:"true"}] | tojson)' \
  "${tmp_dir}/data-import-release.json" >"${tmp_dir}/data-import-drift.json"
if "${script}" "${tmp_dir}/data-import-drift.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - release evidence URI 외 data import 환경 변경을 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_vpc_security_group_egress_rule.host[\"https\"]",type:"aws_vpc_security_group_egress_rule",change:{actions:["forget"],before:null,after:null}},
  {mode:"managed",address:"aws_vpc_security_group_egress_rule.host[\"dns-t\"]",type:"aws_vpc_security_group_egress_rule",change:{actions:["forget"],before:null,after:null}},
  {mode:"managed",address:"aws_vpc_security_group_egress_rule.host[\"dns-u\"]",type:"aws_vpc_security_group_egress_rule",change:{actions:["forget"],before:null,after:null}},
  {mode:"managed",address:"aws_vpc_security_group_egress_rule.host[\"ntp\"]",type:"aws_vpc_security_group_egress_rule",change:{actions:["forget"],before:null,after:null}}
]}' >"${tmp_dir}/allowed-forget.json"
"${script}" "${tmp_dir}/allowed-forget.json" foundation foundation >/dev/null
if "${script}" "${tmp_dir}/allowed-forget.json" data data >/dev/null 2>&1; then
  echo '상태: Fail - foundation ownership migration을 다른 phase에서 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_instance.host[0]",type:"aws_instance",change:{actions:["forget"],before:null,after:null}}
]}' >"${tmp_dir}/foreign-forget.json"
if "${script}" "${tmp_dir}/foreign-forget.json" foundation foundation >/dev/null 2>&1; then
  echo '상태: Fail - 허용 목록 밖의 state forget을 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_vpc_security_group_egress_rule.host[\"other\"]",type:"aws_vpc_security_group_egress_rule",change:{actions:["forget"],before:null,after:null}}
]}' >"${tmp_dir}/foreign-egress-forget.json"
if "${script}" "${tmp_dir}/foreign-egress-forget.json" foundation foundation >/dev/null 2>&1; then
  echo '상태: Fail - 다른 host egress state forget을 허용했습니다.' >&2
  exit 1
fi

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_nat_gateway.this",type:"aws_nat_gateway",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/forbidden.json"
if "${script}" "${tmp_dir}/forbidden.json" foundation registry >/dev/null 2>&1; then
  echo '상태: Fail - 금지된 managed service를 허용했습니다.' >&2
  exit 1
fi

for forbidden_type in aws_rds_cluster_instance aws_vpc_endpoint aws_grafana_workspace aws_ebs_fast_snapshot_restore; do
  jq -n --arg type "${forbidden_type}" '{resource_changes:[
    {mode:"managed",address:("forbidden." + $type),type:$type,change:{actions:["create"],after:{}}}
  ]}' >"${tmp_dir}/forbidden-extra.json"
  if "${script}" "${tmp_dir}/forbidden-extra.json" foundation registry >/dev/null 2>&1; then
    echo "상태: Fail - 금지 resource를 허용했습니다: ${forbidden_type}" >&2
    exit 1
  fi
done

jq -n '{resource_changes:[
  {mode:"managed",address:"aws_ecr_repository.platform[\"budget-postgres\"]",type:"aws_ecr_repository",change:{actions:["create"],after:{name:"home-search/budget-postgres"}}},
  {mode:"managed",address:"aws_vpc.this[0]",type:"aws_vpc",change:{actions:["create"],after:{}}}
]}' >"${tmp_dir}/registry-bypass.json"
if "${script}" "${tmp_dir}/registry-bypass.json" registry registry >/dev/null 2>&1; then
  echo '상태: Fail - registry phase의 foundation 변경을 허용했습니다.' >&2
  exit 1
fi

echo '상태: Pass - budget-production phase backslide, 보존형 task revision 외 zero-destroy, 제한된 state forget, 금지 resource 검증을 확인했습니다.'

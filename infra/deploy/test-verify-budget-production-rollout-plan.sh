#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${root}/infra/deploy/verify-budget-production-rollout-plan.sh"
tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

jq -n --arg before_image '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  --arg after_image '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' '{resource_changes:[
  {mode:"managed",address:"aws_ecs_task_definition.application[\"property-api\"]",type:"aws_ecs_task_definition",change:{actions:["delete","create"],before:{skip_destroy:true,container_definitions:([{name:"property-api",image:$before_image,environment:[]}]|tojson),tags:{Release:"v1.0.10"},tags_all:{Release:"v1.0.10"}},after:{skip_destroy:true,container_definitions:([{name:"property-api",image:$after_image,environment:[]}]|tojson),tags:{Release:"v1.0.11"},tags_all:{Release:"v1.0.11"}}}},
  {mode:"managed",address:"aws_ecs_service.application[\"property-api\"]",type:"aws_ecs_service",change:{actions:["update"],before:{task_definition:"revision-39"},after:{task_definition:"revision-40"}}},
  {mode:"managed",address:"aws_scheduler_schedule.rtms_daily_refresh[0]",type:"aws_scheduler_schedule",change:{actions:["create"],after:{name:"home-search-budget-production-rtms-daily-refresh"}}}
]}' >"${tmp_dir}/allowed.json"
bash "${script}" "${tmp_dir}/allowed.json" >/dev/null

jq '
  .resource_changes = [.resource_changes[0]]
  | .resource_changes[0].address = "aws_ecs_task_definition.one_shot[\"property-flyway\"]"
  | .resource_changes[0].change.before.container_definitions |=
      (fromjson | .[0].name = "property-flyway" | .[0].image |= sub("property-api"; "property-flyway") | .[0].command = ["migrate"] | tojson)
  | .resource_changes[0].change.after.container_definitions |=
      (fromjson | .[0].name = "property-flyway" | .[0].image |= sub("property-api"; "property-flyway") | .[0].command = ["-target=40","migrate"] | tojson)
' "${tmp_dir}/allowed.json" >"${tmp_dir}/property-flyway.json"
bash "${script}" "${tmp_dir}/property-flyway.json" >/dev/null
jq '.resource_changes[0].change.after.container_definitions |= (fromjson | .[0].command = ["clean"] | tojson)' \
  "${tmp_dir}/property-flyway.json" >"${tmp_dir}/property-flyway-clean.json"
if bash "${script}" "${tmp_dir}/property-flyway-clean.json" >/dev/null 2>&1; then
  echo '상태: Fail - property-flyway clean command를 허용했습니다.' >&2
  exit 1
fi

for fixture in platform dns ebs destroy import; do
  case "${fixture}" in
    platform) address='aws_ecs_service.platform["budget-postgres"]'; type=aws_ecs_service; actions='["update"]' ;;
    dns) address='aws_route53_record.public[0]'; type=aws_route53_record; actions='["update"]' ;;
    ebs) address='aws_ebs_volume.data[0]'; type=aws_ebs_volume; actions='["update"]' ;;
    destroy) address='aws_ecs_service.application["property-api"]'; type=aws_ecs_service; actions='["delete"]' ;;
    import) address='aws_ecs_task_definition.data_import'; type=aws_ecs_task_definition; actions='["create"]' ;;
  esac
  jq -n --arg address "${address}" --arg type "${type}" --argjson actions "${actions}" \
    '{resource_changes:[{mode:"managed",address:$address,type:$type,change:{actions:$actions,before:{skip_destroy:false},after:{skip_destroy:false}}}]}' \
    >"${tmp_dir}/${fixture}.json"
  if bash "${script}" "${tmp_dir}/${fixture}.json" >/dev/null 2>&1; then
    echo "상태: Fail - 금지 rollout fixture를 허용했습니다: ${fixture}" >&2
    exit 1
  fi
done

echo '상태: Pass - 증분 rollout allowlist와 platform/DNS/EBS/destroy 차단을 확인했습니다.'

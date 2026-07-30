#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?Terraform plan JSON is required}"
requested_phase="${2:?requested phase is required}"
current_phase="${3:-registry}"
[[ -f "${plan_json}" ]] || exit 2

phase_index() {
  case "$1" in
    registry) echo 0 ;;
    foundation) echo 1 ;;
    data) echo 2 ;;
    private) echo 3 ;;
    public) echo 4 ;;
    *) return 2 ;;
  esac
}

requested_index="$(phase_index "${requested_phase}")" || {
  echo "상태: Fail - 알 수 없는 requested phase입니다: ${requested_phase}" >&2
  exit 2
}
current_index="$(phase_index "${current_phase}")" || {
  echo "상태: Fail - 알 수 없는 current phase입니다: ${current_phase}" >&2
  exit 2
}
if (( requested_index < current_index )); then
  echo "상태: Fail - phase backslide를 거부합니다: ${current_phase} -> ${requested_phase}" >&2
  exit 1
fi

violations="$(jq -c --arg requested_phase "${requested_phase}" --arg current_phase "${current_phase}" '
  def task_definition_without_release_revision:
    .ipc_mode = (.ipc_mode // "")
    | .pid_mode = (.pid_mode // "")
    | del(
      .container_definitions,
      .arn,
      .arn_without_revision,
      .id,
      .revision,
      .tags.Release,
      .tags_all.Release
    );
  def containers_without_release_revision($address):
    map(
      del(.image)
      | if $address == "aws_ecs_task_definition.one_shot[\"data-import-reconcile\"]" then
          .environment |= map(
            if .name == "HOME_MIGRATION_EVIDENCE_S3_URI" then
              .value = "__RELEASE_EVIDENCE_URI__"
            else
              .
            end
          )
        else
          .
        end
    );
  def immutable_budget_image:
    type == "string"
    and test("^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/home-search/[a-z0-9-]+@sha256:[0-9a-f]{64}$");
  def safe_task_definition_release_revision:
    [
      "aws_ecs_task_definition.platform[\"budget-postgres\"]",
      "aws_ecs_task_definition.platform[\"budget-valkey\"]",
      "aws_ecs_task_definition.one_shot[\"secret-bootstrap\"]",
      "aws_ecs_task_definition.one_shot[\"secret-readiness\"]",
      "aws_ecs_task_definition.one_shot[\"property-flyway\"]",
      "aws_ecs_task_definition.one_shot[\"user-flyway\"]",
      "aws_ecs_task_definition.one_shot[\"admin-migration\"]",
      "aws_ecs_task_definition.one_shot[\"ai-migration\"]",
      "aws_ecs_task_definition.one_shot[\"importer-grants\"]",
      "aws_ecs_task_definition.one_shot[\"scheduled-backup\"]",
      "aws_ecs_task_definition.one_shot[\"data-import-reconcile\"]",
      "aws_ecs_task_definition.one_shot[\"map-marker-projection\"]",
      "aws_ecs_task_definition.one_shot[\"runtime-grants\"]"
    ] as $allowed_addresses |
    . as $change |
    (($change.change.before.container_definitions | fromjson?) // []) as $before_containers |
    (($change.change.after.container_definitions | fromjson?) // []) as $after_containers |
    [$before_containers[0].environment[]? | select(.name == "HOME_MIGRATION_EVIDENCE_S3_URI") | .value] as $before_evidence_uris |
    [$after_containers[0].environment[]? | select(.name == "HOME_MIGRATION_EVIDENCE_S3_URI") | .value] as $after_evidence_uris |
    $requested_phase == "data"
    and $current_phase == "data"
    and $change.type == "aws_ecs_task_definition"
    and ($allowed_addresses | index($change.address)) != null
    and $change.change.actions == ["delete", "create"]
    and $change.change.replace_paths == [["container_definitions"]]
    and ($change.change.before | task_definition_without_release_revision)
      == ($change.change.after | task_definition_without_release_revision)
    and $change.change.before.skip_destroy == true
    and $change.change.after.skip_destroy == true
    and ($change.change.after.family | type == "string" and startswith("home-search-budget-production-"))
    and $change.change.after.tags_all.Environment == "budget-production"
    and ($change.change.before.tags.Release | type == "string" and test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
    and ($change.change.after.tags.Release | type == "string" and test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
    and $change.change.before.tags_all.Release == $change.change.before.tags.Release
    and $change.change.after.tags_all.Release == $change.change.after.tags.Release
    and $change.change.before.tags.Release != $change.change.after.tags.Release
    and ($before_containers | length) == 1
    and ($after_containers | length) == 1
    and ($before_containers[0].image | immutable_budget_image)
    and ($after_containers[0].image | immutable_budget_image)
    and $before_containers[0].image != $after_containers[0].image
    and ($before_containers[0].image | split("@sha256:")[0])
      == ($after_containers[0].image | split("@sha256:")[0])
    and (
      $change.address != "aws_ecs_task_definition.one_shot[\"data-import-reconcile\"]"
      or (
        ($before_evidence_uris | length) == 1
        and ($after_evidence_uris | length) == 1
        and ($before_evidence_uris[0] | test("^s3://home-search-budget-production-backup-[0-9]{12}/deployment-evidence/v[0-9]+[.][0-9]+[.][0-9]+$"))
        and ($after_evidence_uris[0] | test("^s3://home-search-budget-production-backup-[0-9]{12}/deployment-evidence/v[0-9]+[.][0-9]+[.][0-9]+$"))
        and ($before_evidence_uris[0] | endswith("/" + $change.change.before.tags.Release))
        and ($after_evidence_uris[0] | endswith("/" + $change.change.after.tags.Release))
        and ($before_evidence_uris[0] | split("/deployment-evidence/")[0])
          == ($after_evidence_uris[0] | split("/deployment-evidence/")[0])
      )
    )
    and ($before_containers | containers_without_release_revision($change.address))
      == ($after_containers | containers_without_release_revision($change.address));
  [
    "aws_db_instance", "aws_db_cluster", "aws_rds_cluster", "aws_rds_cluster_instance", "aws_msk_cluster", "aws_msk_serverless_cluster",
    "aws_nat_gateway", "aws_vpc_endpoint", "aws_lb", "aws_lb_listener", "aws_lb_target_group", "aws_vpn_gateway",
    "aws_ec2_client_vpn_endpoint",
    "aws_customer_gateway", "aws_elasticache_cluster", "aws_elasticache_replication_group",
    "aws_prometheus_workspace", "aws_grafana_workspace", "aws_ebs_fast_snapshot_restore"
  ] as $forbidden_types |
  [
    "aws_vpc_security_group_egress_rule.host[\"https\"]",
    "aws_vpc_security_group_egress_rule.host[\"dns-t\"]",
    "aws_vpc_security_group_egress_rule.host[\"dns-u\"]",
    "aws_vpc_security_group_egress_rule.host[\"ntp\"]"
  ] as $allowed_forget_addresses |
  [.resource_changes[]
   | select(.mode == "managed")
   | select(.change.actions != ["no-op"] and .change.actions != ["read"])
   | . as $change
   | select(
       (
         ((.change.actions | index("delete")) != null)
         and (($change | safe_task_definition_release_revision) | not)
       )
       or (
         (.change.actions | index("forget")) != null
         and ((
           $requested_phase == "foundation"
           and $current_phase == "foundation"
           and $change.change.actions == ["forget"]
           and $change.type == "aws_vpc_security_group_egress_rule"
           and ($allowed_forget_addresses | index($change.address)) != null
         ) | not)
       )
       or ($forbidden_types | index($change.type)) != null
       or ((.change.after | tostring) | test("home-search-(staging|production)([^-]|$)"))
     )
   | {address,type,actions:.change.actions}]
' "${plan_json}")"

if [[ "$(jq 'length' <<<"${violations}")" != '0' ]]; then
  echo '상태: Fail - budget-production plan이 zero-destroy 또는 금지 resource/state 경계를 벗어났습니다.' >&2
  jq . <<<"${violations}" >&2
  exit 1
fi

if [[ "${requested_phase}" == 'registry' ]]; then
  jq -e '
    all(.resource_changes[];
      .change.actions == ["no-op"] or .change.actions == ["read"]
      or .type == "aws_ecr_repository" or .type == "aws_ecr_lifecycle_policy")
  ' "${plan_json}" >/dev/null || {
    echo '상태: Fail - registry phase에 ECR 외 변경이 포함됐습니다.' >&2
    exit 1
  }
fi

echo "상태: Pass - ${current_phase} -> ${requested_phase} plan은 monotonic, 보존형 task revision 외 zero-destroy, 제한된 state forget, budget allowlist 경계를 충족합니다."

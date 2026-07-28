#!/usr/bin/env bash
set -Eeuo pipefail

plan_json="${1:?Terraform plan JSON is required}"
[[ -f "${plan_json}" ]] || exit 2

violations="$(jq -c '
  [
    "aws_cloudwatch_log_group", "aws_cloudwatch_log_metric_filter", "aws_cloudwatch_metric_alarm",
    "aws_db_instance", "aws_db_parameter_group", "aws_db_subnet_group", "aws_ecr_lifecycle_policy",
    "aws_ecr_repository", "aws_ecs_cluster", "aws_ecs_service", "aws_ecs_task_definition",
    "aws_efs_backup_policy", "aws_efs_file_system", "aws_efs_mount_target", "aws_eip",
    "aws_elasticache_replication_group", "aws_elasticache_subnet_group", "aws_glue_registry",
    "aws_iam_role", "aws_iam_role_policy", "aws_iam_role_policy_attachment", "aws_internet_gateway",
    "aws_kms_alias", "aws_kms_key", "aws_lb", "aws_lb_listener", "aws_lb_target_group",
    "aws_msk_serverless_cluster", "aws_nat_gateway", "aws_route_table", "aws_route_table_association",
    "aws_s3_bucket", "aws_s3_bucket_lifecycle_configuration", "aws_s3_bucket_ownership_controls",
    "aws_s3_bucket_policy", "aws_s3_bucket_public_access_block",
    "aws_s3_bucket_server_side_encryption_configuration", "aws_s3_bucket_versioning",
    "aws_scheduler_schedule", "aws_scheduler_schedule_group", "aws_secretsmanager_secret",
    "aws_security_group", "aws_service_discovery_private_dns_namespace", "aws_service_discovery_service",
    "aws_sns_topic", "aws_sns_topic_policy", "aws_sqs_queue", "aws_subnet", "aws_vpc",
    "aws_vpc_endpoint", "aws_vpc_security_group_egress_rule", "aws_vpc_security_group_ingress_rule"
  ] as $allowed_types |
  [.resource_changes[]
   | select(.mode == "managed")
   | select(.change.actions != ["no-op"] and .change.actions != ["read"])
   | . as $resource
   | select(
       ($resource.change.actions | index("delete")) != null
       or ($allowed_types | index($resource.type)) == null
       or $resource.type == "aws_ecr_repository"
       or (($resource.change.after | tostring) | contains("home-search-production"))
       or (($resource.change.after.tags_all? | type) == "object" and $resource.change.after.tags_all.Environment != "staging")
       or ($resource.type == "aws_ecs_service" and (
         $resource.address != "aws_ecs_service.user_insight_worker"
         or $resource.change.after.desired_count != 0
       ))
       or ($resource.type == "aws_scheduler_schedule" and $resource.change.after.state != "DISABLED")
     )
   | {address,type,actions:.change.actions}]
' "${plan_json}")"

if [[ "$(jq 'length' <<<"${violations}")" != '0' ]]; then
  echo '상태: Fail - staging foundation plan이 create/update allowlist 또는 zero-destroy 경계를 벗어났습니다.' >&2
  jq . <<<"${violations}" >&2
  exit 1
fi

for address in aws_vpc.this aws_db_instance.primary aws_lb.public aws_ecs_cluster.this; do
  jq -e --arg address "${address}" '
    any(.resource_changes[];
      .address == $address and
      (.change.actions == ["create"] or .change.actions == ["update"] or .change.actions == ["no-op"]))
  ' "${plan_json}" >/dev/null || {
    echo "상태: Fail - foundation 필수 resource가 plan에 없습니다: ${address}" >&2
    exit 1
  }
done

for task in secret-bootstrap database-bootstrap property-flyway admin-migration user-flyway source-data-migration runtime-grants; do
  jq -e --arg address "aws_ecs_task_definition.one_shot[\"${task}\"]" '
    any(.resource_changes[];
      .address == $address and
      (.change.actions == ["create"] or .change.actions == ["update"] or .change.actions == ["no-op"]))
  ' "${plan_json}" >/dev/null || {
    echo "상태: Fail - foundation bootstrap task definition이 plan에 없습니다: ${task}" >&2
    exit 1
  }
done

echo '상태: Pass - staging foundation plan은 staging allowlist, disabled service/schedule, zero-destroy 경계를 충족합니다.'

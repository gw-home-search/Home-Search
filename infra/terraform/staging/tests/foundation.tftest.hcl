mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c", "ap-northeast-2b"] }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
      user_id    = "AIDATEST"
    }
  }
}

run "private_encrypted_staging_foundation" {
  command = plan
  variables {
    admin_allowed_cidrs    = ["203.0.113.10/32"]
    public_origin          = "https://staging.example.test"
    admin_origin           = "https://admin.staging.example.test"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition     = length(aws_subnet.public) == 2 && length(aws_subnet.application) == 2 && length(aws_subnet.data) == 2
    error_message = "Each subnet tier must span two availability zones."
  }
  assert {
    condition     = aws_eip.nat.domain == "vpc"
    error_message = "Staging must use exactly one public NAT gateway."
  }
  assert {
    condition     = aws_security_group.public_alb.name != aws_security_group.admin_alb.name
    error_message = "Public and admin ALBs must use separate security groups."
  }
  assert {
    condition     = length(aws_security_group.task) == 11 && aws_security_group.database_primary.name != aws_security_group.database_coordinate.name
    error_message = "Workload identities and the two database network boundaries must remain separate."
  }
  assert {
    condition     = toset(one(aws_security_group.admin_alb.ingress).cidr_blocks) == toset(["203.0.113.10/32"])
    error_message = "Admin ingress must contain only the explicit CIDR allowlist."
  }
  assert {
    condition = alltrue([
      !aws_db_instance.primary.publicly_accessible,
      !aws_db_instance.coordinate_source.publicly_accessible,
      aws_db_instance.primary.storage_encrypted,
      aws_db_instance.coordinate_source.storage_encrypted,
      aws_db_instance.primary.deletion_protection,
      aws_db_instance.coordinate_source.deletion_protection,
      !aws_db_instance.primary.multi_az,
      !aws_db_instance.coordinate_source.multi_az,
      aws_db_instance.primary.backup_retention_period >= 7,
      aws_db_instance.coordinate_source.backup_retention_period >= 7,
      aws_db_instance.primary.manage_master_user_password,
      aws_db_instance.coordinate_source.manage_master_user_password,
    ])
    error_message = "Both staging RDS instances must be private, encrypted, protected, single-AZ, and AWS-secret-managed."
  }
  assert {
    condition = alltrue([
      aws_elasticache_replication_group.this.at_rest_encryption_enabled,
      aws_elasticache_replication_group.this.transit_encryption_enabled,
      aws_elasticache_replication_group.this.transit_encryption_mode == "required",
    ])
    error_message = "Redis must enforce at-rest and in-transit encryption."
  }
  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.image : repository.image_tag_mutability == "IMMUTABLE" && repository.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "Every ECR repository must be immutable and scan on push."
  }
  assert {
    condition     = alltrue([for group in aws_cloudwatch_log_group.service : group.retention_in_days >= 30])
    error_message = "Every workload log group must retain at least 30 days."
  }
  assert {
    condition = toset(keys(local.required_tags)) == toset([
      "Project", "Environment", "Service", "Owner", "ManagedBy", "DataClass",
    ])
    error_message = "Every taggable staging resource must inherit the six required ownership and data-class tags."
  }
  assert {
    condition = (
      length(aws_secretsmanager_secret.container) == 19
      && contains(keys(aws_secretsmanager_secret.container), "kakao-local-provider")
      && aws_kms_key.data.enable_key_rotation
    )
    error_message = "External, transition, and workload-specific secret containers must use the rotating staging KMS key."
  }
  assert {
    condition = (
      length(aws_ecs_service.service) == 0 &&
      length(aws_ecs_task_definition.one_shot) == 13 &&
      alltrue([for schedule in aws_scheduler_schedule.database_backup : schedule.state == "DISABLED"]) &&
      alltrue([for schedule in aws_scheduler_schedule.market_news : schedule.state == "DISABLED"]) &&
      aws_scheduler_schedule.property_event_relay.state == "DISABLED" &&
      aws_scheduler_schedule.property_event_retention.state == "DISABLED"
    )
    error_message = "Initial apply must define bootstrap tasks without starting services against empty secrets."
  }
}

run "reject_world_open_admin_ingress" {
  command = plan
  variables {
    admin_allowed_cidrs    = ["0.0.0.0/0"]
    public_origin          = "https://staging.example.test"
    admin_origin           = "https://admin.staging.example.test"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }
  expect_failures = [var.admin_allowed_cidrs]
}

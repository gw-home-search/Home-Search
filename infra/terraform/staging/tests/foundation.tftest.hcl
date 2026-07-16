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
  variables { admin_allowed_cidrs = ["203.0.113.10/32"] }

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
    condition     = length(aws_security_group.task) == 7 && aws_security_group.database_primary.name != aws_security_group.database_coordinate.name
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
    condition     = length(aws_secretsmanager_secret.container) == 6 && aws_kms_key.data.enable_key_rotation
    error_message = "Secret containers and the rotating staging KMS key must be present."
  }
}

run "reject_world_open_admin_ingress" {
  command = plan
  variables { admin_allowed_cidrs = ["0.0.0.0/0"] }
  expect_failures = [var.admin_allowed_cidrs]
}

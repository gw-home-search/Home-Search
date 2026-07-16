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

run "encrypted_scheduled_backup_and_restore_verification" {
  command = plan
  variables {
    admin_allowed_cidrs     = ["203.0.113.10/32"]
    public_origin           = "https://staging.example.test"
    admin_origin            = "https://admin.staging.example.test"
    public_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn   = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    enable_backup_schedules = true
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      aws_kms_key.database_backup.description != aws_kms_key.data.description,
      aws_kms_key.database_backup.enable_key_rotation,
      aws_s3_bucket_public_access_block.database_backup.block_public_acls,
      aws_s3_bucket_public_access_block.database_backup.block_public_policy,
      aws_s3_bucket_public_access_block.database_backup.ignore_public_acls,
      aws_s3_bucket_public_access_block.database_backup.restrict_public_buckets,
      aws_s3_bucket_versioning.database_backup.versioning_configuration[0].status == "Enabled",
      aws_s3_bucket_lifecycle_configuration.database_backup.rule[0].expiration[0].days == 30,
    ])
    error_message = "Backup artifacts require a separate rotating KMS key, versioning, public blocking, and 30-day staging retention."
  }

  assert {
    condition = alltrue([
      aws_scheduler_schedule.database_backup["daily-backup"].schedule_expression == "cron(30 3 * * ? *)",
      aws_scheduler_schedule.database_backup["weekly-restore-verification"].schedule_expression == "cron(30 4 ? * SUN *)",
      aws_scheduler_schedule.database_backup["daily-backup"].schedule_expression_timezone == "Asia/Seoul",
      aws_scheduler_schedule.database_backup["weekly-restore-verification"].schedule_expression_timezone == "Asia/Seoul",
      aws_scheduler_schedule.database_backup["daily-backup"].state == "ENABLED",
      aws_scheduler_schedule.database_backup["weekly-restore-verification"].state == "ENABLED",
      !aws_scheduler_schedule.database_backup["daily-backup"].target[0].ecs_parameters[0].network_configuration[0].assign_public_ip,
      !aws_scheduler_schedule.database_backup["weekly-restore-verification"].target[0].ecs_parameters[0].network_configuration[0].assign_public_ip,
    ])
    error_message = "Backup and restore schedules must use KST and private one-shot Fargate networking."
  }

  assert {
    condition = alltrue([
      local.one_shot_specs["backup"].command[0] == "--backup-all",
      local.one_shot_specs["restore-verification"].command[0] == "--verify-latest-s3",
      output.backup_automation.included_databases == ["home_search", "home_search_admin", "home_search_user"],
      output.backup_automation.excluded_databases == ["home_search_coordinate_source"],
    ])
    error_message = "Schedules must include only property/admin/user backup and latest restore verification, never coordinate-source."
  }

  assert {
    condition     = aws_iam_role.backup_task.name != aws_iam_role.runtime_task.name && length(aws_cloudwatch_log_metric_filter.database_backup) == 7
    error_message = "Backup tasks require their scoped S3/KMS role and complete success/failure/age/checksum/duration metrics."
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.schedule_target_error) == 2 && aws_cloudwatch_metric_alarm.backup_age.treat_missing_data == "breaching"
    error_message = "Both schedules need target-error alarms and missing backup-age evidence must alarm."
  }
}

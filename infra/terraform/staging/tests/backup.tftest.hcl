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
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff", "seo-renderer",
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
      one([for item in local.one_shot_specs["backup"].environment : item.value if item.name == "HOME_BACKUP_LOGICAL_DATABASES"]) == "property,admin,user",
      length([for item in local.one_shot_specs["backup"].environment : item.name if item.name == "HOME_BACKUP_KMS_KEY_ID"]) == 1,
      one([for item in local.one_shot_specs["restore-verification"].environment : item.value if item.name == "HOME_BACKUP_LOGICAL_DATABASES"]) == "property,admin,user",
      output.backup_automation.included_databases == ["home_search", "home_search_admin", "home_search_user"],
      output.backup_automation.excluded_databases == ["home_search_coordinate_source"],
    ])
    error_message = "Schedules must include only property/admin/user backup and latest restore verification, never coordinate-source."
  }

  assert {
    condition = alltrue([
      local.workload_task_role_names["backup"] != local.workload_task_role_names["property-api"],
      local.workload_task_role_names["restore-verification"] != local.workload_task_role_names["backup"],
      length(aws_iam_role_policy.backup_task) == 2,
      contains(local.backup_task_permissions["backup"].object_actions, "s3:PutObject"),
      !contains(local.backup_task_permissions["restore-verification"].object_actions, "s3:PutObject"),
      local.backup_task_permissions["restore-verification"].kms_actions == ["kms:Decrypt"],
      length(aws_cloudwatch_log_metric_filter.database_backup) == 7,
    ])
    error_message = "Backup tasks require their scoped S3/KMS role and complete success/failure/age/checksum/duration metrics."
  }

  assert {
    condition = alltrue([
      length(local.scheduler_assume_policies) == 4,
      alltrue([
        for group, policy in local.scheduler_assume_policies :
        strcontains(policy, "\"aws:SourceAccount\"") &&
        strcontains(policy, "\"aws:SourceArn\"") &&
        strcontains(policy, "schedule-group/home-search-staging-${group}") &&
        !strcontains(policy, "schedule-group/home-search-staging-*")
      ]),
      aws_iam_role.backup_scheduler.assume_role_policy == local.scheduler_assume_policies["database-backup"],
      aws_iam_role.market_news_scheduler.assume_role_policy == local.scheduler_assume_policies["market-news"],
      aws_iam_role.property_event_relay_scheduler.assume_role_policy == local.scheduler_assume_policies["property-event-relay"],
      aws_iam_role.property_event_retention_scheduler.assume_role_policy == local.scheduler_assume_policies["property-event-retention"],
    ])
    error_message = "Each Scheduler role must trust only its exact account-scoped staging schedule group."
  }

  assert {
    condition = alltrue([
      length(aws_cloudwatch_metric_alarm.schedule_target_error) == 4,
      alltrue([
        for alarm in values(aws_cloudwatch_metric_alarm.schedule_target_error) :
        length(keys(alarm.dimensions)) == 1 && contains(keys(alarm.dimensions), "ScheduleGroup")
      ]),
      aws_cloudwatch_metric_alarm.backup_age.threshold == 93600,
      aws_cloudwatch_metric_alarm.backup_age.treat_missing_data == "breaching",
    ])
    error_message = "Scheduler alarms must use valid ScheduleGroup-only dimensions and the backup-age alarm must use the 26-hour threshold."
  }

  assert {
    condition = alltrue(concat(
      [
        for alarm in values(aws_cloudwatch_metric_alarm.schedule_target_error) :
        length(alarm.alarm_actions) > 0
      ],
      [
        length(aws_cloudwatch_metric_alarm.checksum_mismatch.alarm_actions) > 0,
        length(aws_cloudwatch_metric_alarm.backup_age.alarm_actions) > 0,
        length(aws_cloudwatch_metric_alarm.scheduler_dlq_visible.alarm_actions) > 0,
        length(aws_cloudwatch_metric_alarm.database_task_failure) == 2,
        alltrue([
          for alarm in values(aws_cloudwatch_metric_alarm.database_task_failure) :
          length(alarm.alarm_actions) > 0
        ]),
      ],
    ))
    error_message = "Scheduler, checksum, DLQ, and backup-age alarms must have non-empty SNS actions."
  }

  assert {
    condition = alltrue([
      aws_kms_key.operations.enable_key_rotation,
      strcontains(aws_kms_key.operations.policy, "cloudwatch.amazonaws.com"),
      strcontains(aws_kms_key.operations.policy, "kms:GenerateDataKey*"),
      strcontains(aws_kms_key.operations.policy, "kms:Decrypt"),
      strcontains(aws_sns_topic_policy.operations.policy, "cloudwatch.amazonaws.com"),
      strcontains(aws_sns_topic_policy.operations.policy, "sns:Publish"),
      strcontains(aws_sns_topic_policy.operations.policy, "aws:SourceArn"),
      strcontains(aws_sns_topic_policy.operations.policy, "aws:SourceAccount"),
      aws_sqs_queue.scheduler_failure.sqs_managed_sse_enabled,
      aws_sqs_queue.scheduler_failure.message_retention_seconds == 1209600,
      alltrue([
        for schedule in values(aws_scheduler_schedule.database_backup) :
        length(schedule.target[0].dead_letter_config) == 1
      ]),
    ])
    error_message = "Every database schedule must send exhausted invocations to the encrypted 14-day Scheduler DLQ."
  }
}

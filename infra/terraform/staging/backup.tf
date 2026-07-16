resource "aws_kms_key" "database_backup" {
  description             = "Home Search staging database backup artifacts"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "database_backup" {
  name          = "alias/${local.name}-database-backup"
  target_key_id = aws_kms_key.database_backup.key_id
}

resource "aws_s3_bucket" "database_backup" {
  bucket = "${local.name}-database-backup-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_ownership_controls" "database_backup" {
  bucket = aws_s3_bucket.database_backup.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_public_access_block" "database_backup" {
  bucket                  = aws_s3_bucket.database_backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "database_backup" {
  bucket = aws_s3_bucket.database_backup.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "database_backup" {
  bucket = aws_s3_bucket.database_backup.id
  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.database_backup.arn
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "database_backup" {
  bucket = aws_s3_bucket.database_backup.id
  rule {
    id     = "expire-staging-backups"
    status = "Enabled"
    filter { prefix = "staging/" }
    expiration { days = 30 }
    noncurrent_version_expiration { noncurrent_days = 30 }
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
  depends_on = [aws_s3_bucket_versioning.database_backup]
}

resource "aws_s3_bucket_policy" "database_backup" {
  bucket = aws_s3_bucket.database_backup.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.database_backup.arn, "${aws_s3_bucket.database_backup.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.database_backup]
}

resource "aws_iam_role" "backup_task" {
  name               = "${local.name}-backup-task"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy" "backup_task" {
  name = "encrypted-staging-backups"
  role = aws_iam_role.backup_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.database_backup.arn]
        Condition = {
          StringLike = { "s3:prefix" = ["staging", "staging/*"] }
        }
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = ["${aws_s3_bucket.database_backup.arn}/staging/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.database_backup.arn]
      },
    ]
  })
}

locals {
  scheduler_assume_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "scheduler.amazonaws.com" }
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
  backup_schedules = {
    daily-backup = {
      expression = "cron(30 3 * * ? *)"
      task       = "backup"
    }
    weekly-restore-verification = {
      expression = "cron(30 4 ? * SUN *)"
      task       = "restore-verification"
    }
  }
}

resource "aws_iam_role" "backup_scheduler" {
  name               = "${local.name}-backup-scheduler"
  assume_role_policy = local.scheduler_assume_policy
}

resource "aws_iam_role_policy" "backup_scheduler" {
  name = "run-backup-tasks-only"
  role = aws_iam_role.backup_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["ecs:RunTask"]
        Resource = [
          aws_ecs_task_definition.one_shot["backup"].arn,
          aws_ecs_task_definition.one_shot["restore-verification"].arn,
        ]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this.arn }
        }
      },
      {
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.task_execution.arn, aws_iam_role.backup_task.arn]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}

resource "aws_scheduler_schedule_group" "database_backup" {
  name = "${local.name}-database-backup"
}

resource "aws_scheduler_schedule" "database_backup" {
  for_each                     = local.backup_schedules
  name                         = "${local.name}-${each.key}"
  group_name                   = aws_scheduler_schedule_group.database_backup.name
  schedule_expression          = each.value.expression
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.enable_backup_schedules ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.this.arn
    role_arn = aws_iam_role.backup_scheduler.arn
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot[each.value.task].arn
      launch_type         = "FARGATE"
      platform_version    = "LATEST"
      task_count          = 1
      network_configuration {
        subnets          = values(aws_subnet.application)[*].id
        security_groups  = [aws_security_group.task["ops"].id]
        assign_public_ip = false
      }
    }
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 2
    }
  }
}

locals {
  backup_metric_filters = {
    backup-success = {
      log_group = aws_cloudwatch_log_group.service["backup"].name
      pattern   = "{ $.metric = \"backup_success\" }"
      metric    = "BackupSuccessCount"
      value     = "$.value"
      unit      = "Count"
    }
    backup-failure = {
      log_group = aws_cloudwatch_log_group.service["backup"].name
      pattern   = "\"ERROR:\""
      metric    = "BackupFailureCount"
      value     = "1"
      unit      = "Count"
    }
    restore-success = {
      log_group = aws_cloudwatch_log_group.service["restore-verification"].name
      pattern   = "{ $.metric = \"restore_success\" }"
      metric    = "RestoreSuccessCount"
      value     = "$.value"
      unit      = "Count"
    }
    restore-failure = {
      log_group = aws_cloudwatch_log_group.service["restore-verification"].name
      pattern   = "\"ERROR:\""
      metric    = "RestoreFailureCount"
      value     = "1"
      unit      = "Count"
    }
    checksum-mismatch = {
      log_group = aws_cloudwatch_log_group.service["restore-verification"].name
      pattern   = "\"checksum mismatch\""
      metric    = "ChecksumMismatchCount"
      value     = "1"
      unit      = "Count"
    }
    restore-duration = {
      log_group = aws_cloudwatch_log_group.service["restore-verification"].name
      pattern   = "{ $.metric = \"restore_duration_seconds\" }"
      metric    = "RestoreDurationSeconds"
      value     = "$.value"
      unit      = "Seconds"
    }
    backup-age = {
      log_group = aws_cloudwatch_log_group.service["restore-verification"].name
      pattern   = "{ $.metric = \"backup_age_seconds\" }"
      metric    = "BackupAgeSeconds"
      value     = "$.value"
      unit      = "Seconds"
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "database_backup" {
  for_each       = local.backup_metric_filters
  name           = "${local.name}-${each.key}"
  pattern        = each.value.pattern
  log_group_name = each.value.log_group
  metric_transformation {
    name      = each.value.metric
    namespace = "HomeSearch/StagingBackup"
    value     = each.value.value
    unit      = each.value.unit
  }
}

resource "aws_cloudwatch_metric_alarm" "schedule_target_error" {
  for_each            = aws_scheduler_schedule.database_backup
  alarm_name          = "${local.name}-${each.key}-target-errors"
  namespace           = "AWS/Scheduler"
  metric_name         = "TargetErrorCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    ScheduleGroup = aws_scheduler_schedule_group.database_backup.name
    ScheduleName  = each.value.name
  }
}

resource "aws_cloudwatch_metric_alarm" "checksum_mismatch" {
  alarm_name          = "${local.name}-backup-checksum-mismatch"
  namespace           = "HomeSearch/StagingBackup"
  metric_name         = "ChecksumMismatchCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "backup_age" {
  alarm_name          = "${local.name}-backup-older-than-48h"
  namespace           = "HomeSearch/StagingBackup"
  metric_name         = "BackupAgeSeconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 172800
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
}

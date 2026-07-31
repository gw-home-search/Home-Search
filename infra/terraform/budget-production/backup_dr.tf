resource "aws_iam_role" "dlm" {
  count = local.foundation_enabled ? 1 : 0
  name  = "${local.name}-dlm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "dlm.amazonaws.com" }
    }]
  })
  tags = { Service = "backup" }
}

resource "aws_iam_role_policy_attachment" "dlm" {
  count      = local.foundation_enabled ? 1 : 0
  role       = aws_iam_role.dlm[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data" {
  count              = local.foundation_enabled ? 1 : 0
  description        = "Daily crash-consistent budget-production data EBS snapshot"
  execution_role_arn = aws_iam_role.dlm[0].arn
  state              = var.backup_schedules_enabled ? "ENABLED" : "DISABLED"

  policy_details {
    resource_types = ["VOLUME"]
    target_tags    = { Backup = "daily" }
    schedule {
      name      = "daily-0130-kst-retain-seven"
      copy_tags = true
      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["16:30"]
      }
      retain_rule { count = 7 }
      tags_to_add = {
        BackupClass = "crash-consistent"
        RPO         = "24h"
      }
    }
  }
  depends_on = [aws_iam_role_policy_attachment.dlm]
  tags       = { Service = "backup" }
}

resource "aws_iam_role" "backup_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-backup-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "scheduler.amazonaws.com" }
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          "aws:SourceArn"     = "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule-group/${local.name}-backup"
        }
      }
    }]
  })
  tags = { Service = "backup" }
}

resource "aws_iam_role_policy" "backup_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "run-reviewed-logical-backup"
  role  = aws_iam_role.backup_scheduler[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = [aws_ecs_task_definition.one_shot["scheduled-backup"].arn]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this[0].arn }
        }
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.task_execution["scheduled-backup"].arn,
          aws_iam_role.task_runtime["scheduled-backup"].arn,
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}

resource "aws_scheduler_schedule_group" "backup" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-backup"
  tags  = { Service = "backup" }
}

resource "aws_scheduler_schedule" "logical_backup" {
  count                        = local.data_enabled ? 1 : 0
  name                         = "${local.name}-logical-backup"
  group_name                   = aws_scheduler_schedule_group.backup[0].name
  schedule_expression          = "cron(30 3 * * ? *)"
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.backup_schedules_enabled ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.this[0].arn
    role_arn = aws_iam_role.backup_scheduler[0].arn
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot["scheduled-backup"].arn
      launch_type         = "EC2"
      task_count          = 1
    }
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 1
    }
  }
}

resource "aws_iam_role" "rtms_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-rtms-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "scheduler.amazonaws.com" }
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          "aws:SourceArn"     = "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule-group/${local.name}-data-refresh"
        }
      }
    }]
  })
  tags = { Service = "property-batch" }
}

resource "aws_iam_role_policy" "rtms_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "run-reviewed-rtms-daily-refresh"
  role  = aws_iam_role.rtms_scheduler[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = [aws_ecs_task_definition.one_shot["rtms-daily-refresh"].arn]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this[0].arn }
        }
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.task_execution["rtms-daily-refresh"].arn,
          aws_iam_role.task_runtime["rtms-daily-refresh"].arn,
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}

resource "aws_scheduler_schedule_group" "data_refresh" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-data-refresh"
  tags  = { Service = "property-batch" }
}

resource "aws_scheduler_schedule" "rtms_daily_refresh" {
  count                        = local.data_enabled ? 1 : 0
  name                         = "${local.name}-rtms-daily-refresh"
  group_name                   = aws_scheduler_schedule_group.data_refresh[0].name
  schedule_expression          = "cron(30 7 * * ? *)"
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.backup_schedules_enabled ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.this[0].arn
    role_arn = aws_iam_role.rtms_scheduler[0].arn
    input = jsonencode({
      containerOverrides = [{
        name    = "rtms-daily-refresh"
        command = ["schedulerExecutionId=<aws.scheduler.execution-id>"]
      }]
    })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot["rtms-daily-refresh"].arn
      launch_type         = "EC2"
      task_count          = 1
    }
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 1
    }
  }
}

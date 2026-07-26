locals {
  operations_topic_arn = "arn:aws:sns:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${local.name}-operations"
  operations_kms_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnableAccountKeyAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowCloudWatchAlarmNotificationEncryption"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey*",
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "sns.${var.aws_region}.amazonaws.com"
          }
        }
      },
    ]
  })
  operations_sns_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action = [
          "sns:AddPermission",
          "sns:DeleteTopic",
          "sns:GetTopicAttributes",
          "sns:ListSubscriptionsByTopic",
          "sns:Publish",
          "sns:Receive",
          "sns:RemovePermission",
          "sns:SetTopicAttributes",
          "sns:Subscribe",
        ]
        Resource = local.operations_topic_arn
      },
      {
        Sid       = "AllowCloudWatchAlarmNotifications"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = local.operations_topic_arn
        Condition = {
          ArnLike = {
            "aws:SourceArn" = "arn:aws:cloudwatch:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alarm:*"
          }
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
    ]
  })
}

resource "aws_kms_key" "operations" {
  description             = "Home Search staging operations alarm notifications"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = local.operations_kms_policy
}

resource "aws_kms_alias" "operations" {
  name          = "alias/${local.name}-operations"
  target_key_id = aws_kms_key.operations.key_id
}

resource "aws_sns_topic" "operations" {
  name              = "${local.name}-operations"
  kms_master_key_id = aws_kms_key.operations.arn
}

resource "aws_sns_topic_policy" "operations" {
  arn    = aws_sns_topic.operations.arn
  policy = local.operations_sns_policy
}

resource "aws_sqs_queue" "scheduler_failure" {
  name                      = "${local.name}-scheduler-failure"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

locals {
  scheduler_alarm_groups = {
    database-backup          = aws_scheduler_schedule_group.database_backup.name
    market-news              = aws_scheduler_schedule_group.market_news.name
    property-event-relay     = aws_scheduler_schedule_group.property_event_relay.name
    property-event-retention = aws_scheduler_schedule_group.property_event_retention.name
  }
  database_task_failure_metrics = {
    backup  = "BackupFailureCount"
    restore = "RestoreFailureCount"
  }
}

resource "aws_cloudwatch_metric_alarm" "schedule_target_error" {
  for_each            = local.scheduler_alarm_groups
  alarm_name          = "${local.name}-${each.key}-target-errors"
  namespace           = "AWS/Scheduler"
  metric_name         = "TargetErrorCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    ScheduleGroup = each.value
  }
}

resource "aws_cloudwatch_metric_alarm" "scheduler_dlq_visible" {
  alarm_name          = "${local.name}-scheduler-dlq-visible"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    QueueName = aws_sqs_queue.scheduler_failure.name
  }
}

resource "aws_cloudwatch_metric_alarm" "database_task_failure" {
  for_each            = local.database_task_failure_metrics
  alarm_name          = "${local.name}-${each.key}-task-failure"
  namespace           = "HomeSearch/StagingBackup"
  metric_name         = each.value
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
}

moved {
  from = aws_cloudwatch_metric_alarm.schedule_target_error["daily-backup"]
  to   = aws_cloudwatch_metric_alarm.schedule_target_error["database-backup"]
}

moved {
  from = aws_cloudwatch_metric_alarm.schedule_target_error["weekly-restore-verification"]
  to   = aws_cloudwatch_metric_alarm.schedule_target_error["property-event-relay"]
}

resource "aws_cloudwatch_metric_alarm" "public_target_5xx" {
  alarm_name          = "${local.name}-public-target-5xx-percent"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]

  metric_query {
    id          = "error_rate"
    expression  = "IF(requests > 0, 100 * errors / requests, 0)"
    label       = "Target 5xx percent"
    return_data = true
  }
  metric_query {
    id          = "errors"
    return_data = false
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions = {
        LoadBalancer = aws_lb.public.arn_suffix
        TargetGroup  = aws_lb_target_group.gateway["public-gateway"].arn_suffix
      }
    }
  }
  metric_query {
    id          = "requests"
    return_data = false
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 300
      stat        = "Sum"
      dimensions = {
        LoadBalancer = aws_lb.public.arn_suffix
        TargetGroup  = aws_lb_target_group.gateway["public-gateway"].arn_suffix
      }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "ecs_running_task" {
  for_each            = aws_ecs_service.service
  alarm_name          = "${local.name}-${each.key}-running-task"
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = each.value.desired_count - 0.5
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = each.value.name
  }
}

resource "aws_cloudwatch_metric_alarm" "user_insight_worker_running" {
  count               = var.enable_services ? 1 : 0
  alarm_name          = "${local.name}-user-insight-worker-running-task"
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = aws_ecs_service.user_insight_worker.desired_count - 0.5
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.user_insight_worker.name
  }
}

locals {
  observed_databases = {
    primary           = aws_db_instance.primary
    coordinate-source = aws_db_instance.coordinate_source
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  for_each            = local.observed_databases
  alarm_name          = "${local.name}-${each.key}-rds-cpu"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    DBInstanceIdentifier = each.value.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  for_each            = local.observed_databases
  alarm_name          = "${local.name}-${each.key}-rds-free-storage"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  threshold           = each.value.allocated_storage * 1024 * 1024 * 1024 * 0.2
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    DBInstanceIdentifier = each.value.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${local.name}-primary-rds-connections"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.rds_connection_alarm_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.primary.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "valkey_evictions" {
  alarm_name          = "${local.name}-valkey-evictions"
  namespace           = "AWS/ElastiCache"
  metric_name         = "Evictions"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.replication_group_id
  }
}

resource "aws_cloudwatch_metric_alarm" "user_insight_lag" {
  alarm_name          = "${local.name}-user-insight-lag-age"
  namespace           = "AWS/Kafka"
  metric_name         = "EstimatedMaxTimeLag"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 300
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    "Cluster Name"   = aws_msk_serverless_cluster.events.cluster_name
    "Consumer Group" = local.user_insight_group
    Topic            = local.user_insight_main_topic
  }
}

resource "aws_cloudwatch_metric_alarm" "user_insight_dlq_messages" {
  alarm_name          = "${local.name}-user-insight-dlq-messages"
  namespace           = "AWS/Kafka"
  metric_name         = "MessagesInPerSec"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  dimensions = {
    "Cluster Name" = aws_msk_serverless_cluster.events.cluster_name
    Topic          = local.user_insight_dlq_topic
  }
}

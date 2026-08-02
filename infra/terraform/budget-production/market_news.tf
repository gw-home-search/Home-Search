locals {
  market_news_schedules = {
    general         = { expression = "cron(30 0,12,18 * * ? *)", task = "market-news-general" }
    morning         = { expression = "cron(30 6 * * ? *)", task = "market-news-morning" }
    major-selection = { expression = "cron(30 5 ? * MON *)", task = "market-news-major-selection" }
    retention       = { expression = "cron(30 20 * * ? *)", task = "market-news-retention" }
  }
}

resource "aws_iam_role" "market_news_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-market-news-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "scheduler.amazonaws.com" }
      Condition = { StringEquals = {
        "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        "aws:SourceArn"     = "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule-group/${local.name}-market-news"
      } }
    }]
  })
  tags = { Service = "property-batch" }
}

resource "aws_iam_role_policy" "market_news_scheduler" {
  count = local.data_enabled ? 1 : 0
  name  = "run-reviewed-market-news-tasks"
  role  = aws_iam_role.market_news_scheduler[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    {
      Effect    = "Allow", Action = ["ecs:RunTask"]
      Resource  = [for schedule in values(local.market_news_schedules) : aws_ecs_task_definition.one_shot[schedule.task].arn]
      Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this[0].arn } }
    },
    {
      Effect = "Allow", Action = ["iam:PassRole"]
      Resource = distinct(flatten([for schedule in values(local.market_news_schedules) : [
        aws_iam_role.task_execution[schedule.task].arn,
        aws_iam_role.task_runtime[schedule.task].arn,
      ]]))
      Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
    },
  ] })
}

resource "aws_scheduler_schedule_group" "market_news" {
  count = local.data_enabled ? 1 : 0
  name  = "${local.name}-market-news"
  tags  = { Service = "property-batch" }
}

resource "aws_scheduler_schedule" "market_news" {
  for_each                     = local.data_enabled ? local.market_news_schedules : {}
  name                         = "${local.name}-market-news-${each.key}"
  group_name                   = aws_scheduler_schedule_group.market_news[0].name
  schedule_expression          = each.value.expression
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.market_news_schedules_enabled ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.this[0].arn
    role_arn = aws_iam_role.market_news_scheduler[0].arn
    input = jsonencode({ containerOverrides = [{
      name = each.value.task, command = ["schedulerExecutionId=<aws.scheduler.execution-id>"]
    }] })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot[each.value.task].arn
      launch_type         = "EC2"
      task_count          = 1
    }
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 2
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "market_news_scheduler_failure" {
  count               = local.data_enabled ? 1 : 0
  alarm_name          = "${local.name}-market-news-scheduler-failure"
  namespace           = "AWS/Scheduler"
  metric_name         = "TargetErrorCount"
  dimensions          = { ScheduleGroup = aws_scheduler_schedule_group.market_news[0].name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

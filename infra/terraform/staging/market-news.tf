locals {
  market_news_schedules = {
    general = {
      expression = "cron(30 0,12,18 * * ? *)"
      job_name   = "marketNewsGeneralJob"
    }
    morning = {
      expression = "cron(30 6 * * ? *)"
      job_name   = "marketNewsMorningJob"
    }
    major-selection = {
      expression = "cron(30 5 ? * MON *)"
      job_name   = "marketNewsMajorSelectionJob"
    }
    retention = {
      expression = "cron(30 20 * * ? *)"
      job_name   = "marketNewsRetentionJob"
    }
  }
}

resource "aws_iam_role" "market_news_scheduler" {
  name               = "${local.name}-market-news-scheduler"
  assume_role_policy = local.scheduler_assume_policies["market-news"]
}

resource "aws_iam_role_policy" "market_news_scheduler" {
  name = "run-market-news-batch-only"
  role = aws_iam_role.market_news_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = [aws_ecs_task_definition.one_shot["property-batch"].arn]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this.arn }
        }
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.workload_execution["property-batch"].arn,
          aws_iam_role.workload_task["property-batch"].arn,
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      {
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = [aws_sqs_queue.scheduler_failure.arn]
      },
    ]
  })
}

resource "aws_scheduler_schedule_group" "market_news" {
  name = "${local.name}-market-news"
}

resource "aws_scheduler_schedule" "market_news" {
  for_each                     = local.market_news_schedules
  name                         = "${local.name}-market-news-${each.key}"
  group_name                   = aws_scheduler_schedule_group.market_news.name
  schedule_expression          = each.value.expression
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.enable_market_news_schedules ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }

  target {
    arn      = aws_ecs_cluster.this.arn
    role_arn = aws_iam_role.market_news_scheduler.arn
    dead_letter_config {
      arn = aws_sqs_queue.scheduler_failure.arn
    }
    input = jsonencode({
      containerOverrides = [{
        name    = "property-batch"
        command = ["schedulerExecutionId=<aws.scheduler.execution-id>"]
        environment = [
          {
            name  = "SPRING_BATCH_JOB_NAME"
            value = each.value.job_name
          },
          {
            name  = "HOME_NEWS_NAVER_ENABLED"
            value = "true"
          },
        ]
      }]
    })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot["property-batch"].arn
      launch_type         = "FARGATE"
      platform_version    = "LATEST"
      task_count          = 1
      network_configuration {
        subnets          = values(aws_subnet.application)[*].id
        security_groups  = [aws_security_group.task["property-batch"].id]
        assign_public_ip = false
      }
    }
    retry_policy {
      maximum_event_age_in_seconds = 900
      maximum_retry_attempts       = 1
    }
  }
}

locals {
  property_event_topics = toset([
    "property.trade-events.v1",
    "property.complex-events.v1",
    "property.insight-events.v1",
  ])
  property_event_producer_cluster_actions = toset([
    "kafka-cluster:Connect",
    "kafka-cluster:DescribeCluster",
    "kafka-cluster:WriteDataIdempotently",
  ])
  property_event_producer_topic_actions = toset([
    "kafka-cluster:DescribeTopic",
    "kafka-cluster:WriteData",
  ])
  user_insight_main_topic = "property.insight-events.v1"
  user_insight_dlq_topic  = "property.insight-events.v1.dlq"
  user_insight_group      = "user-digest-v1"
  user_insight_consumer_topic_actions = toset([
    "kafka-cluster:DescribeTopic",
    "kafka-cluster:ReadData",
  ])
  user_insight_dlq_topic_actions = toset([
    "kafka-cluster:DescribeTopic",
    "kafka-cluster:WriteData",
  ])
  user_insight_group_actions = toset([
    "kafka-cluster:AlterGroup",
    "kafka-cluster:DescribeGroup",
  ])
}

resource "aws_security_group" "streaming" {
  name        = "${local.name}-streaming"
  description = "MSK IAM ingress from explicit event workloads"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_ingress_rule" "streaming_from_workload" {
  for_each                     = toset(["property-event-relay", "user-insight-worker"])
  security_group_id            = aws_security_group.streaming.id
  referenced_security_group_id = aws_security_group.task[each.key].id
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  description                  = "MSK IAM from ${each.key} workloads"
}

resource "aws_iam_role_policy" "user_insight_consumer" {
  name = "consume-property-insight-events"
  role = aws_iam_role.workload_task["user-insight-worker"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:DescribeCluster",
          "kafka-cluster:WriteDataIdempotently",
        ]
        Resource = [aws_msk_serverless_cluster.events.arn]
      },
      {
        Effect = "Allow"
        Action = local.user_insight_consumer_topic_actions
        Resource = [
          "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/${aws_msk_serverless_cluster.events.cluster_name}/${aws_msk_serverless_cluster.events.cluster_uuid}/${local.user_insight_main_topic}",
        ]
      },
      {
        Effect = "Allow"
        Action = local.user_insight_dlq_topic_actions
        Resource = [
          "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/${aws_msk_serverless_cluster.events.cluster_name}/${aws_msk_serverless_cluster.events.cluster_uuid}/${local.user_insight_dlq_topic}",
        ]
      },
      {
        Effect = "Allow"
        Action = local.user_insight_group_actions
        Resource = [
          "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:group/${aws_msk_serverless_cluster.events.cluster_name}/${aws_msk_serverless_cluster.events.cluster_uuid}/${local.user_insight_group}",
        ]
      },
    ]
  })
}

resource "aws_msk_serverless_cluster" "events" {
  cluster_name = "${local.name}-events"

  vpc_config {
    subnet_ids         = values(aws_subnet.data)[*].id
    security_group_ids = [aws_security_group.streaming.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}

resource "aws_glue_registry" "events" {
  registry_name = "${local.name}-events"
  description   = "Governed JSON Schema contracts promoted independently from Terraform"
}

resource "aws_iam_role_policy" "property_event_producer" {
  name = "publish-property-event-topics"
  role = aws_iam_role.workload_task["property-event-relay"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = local.property_event_producer_cluster_actions
        Resource = [aws_msk_serverless_cluster.events.arn]
      },
      {
        Effect = "Allow"
        Action = local.property_event_producer_topic_actions
        Resource = [
          for topic in local.property_event_topics :
          "arn:aws:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/${aws_msk_serverless_cluster.events.cluster_name}/${aws_msk_serverless_cluster.events.cluster_uuid}/${topic}"
        ]
      },
    ]
  })
}

resource "aws_iam_role" "property_event_relay_scheduler" {
  name               = "${local.name}-property-event-relay-scheduler"
  assume_role_policy = local.scheduler_assume_policies["property-event-relay"]
}

resource "aws_iam_role_policy" "property_event_relay_scheduler" {
  name = "run-property-event-relay-only"
  role = aws_iam_role.property_event_relay_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = [aws_ecs_task_definition.one_shot["property-event-relay"].arn]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this.arn }
        }
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.workload_execution["property-event-relay"].arn,
          aws_iam_role.workload_task["property-event-relay"].arn,
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

resource "aws_scheduler_schedule_group" "property_event_relay" {
  name = "${local.name}-property-event-relay"
}

resource "aws_scheduler_schedule" "property_event_relay" {
  name                         = "${local.name}-property-event-relay"
  group_name                   = aws_scheduler_schedule_group.property_event_relay.name
  schedule_expression          = "rate(5 minutes)"
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.enable_property_event_relay_schedule ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }

  target {
    arn      = aws_ecs_cluster.this.arn
    role_arn = aws_iam_role.property_event_relay_scheduler.arn
    dead_letter_config {
      arn = aws_sqs_queue.scheduler_failure.arn
    }
    input = jsonencode({
      containerOverrides = [{
        name    = "property-event-relay"
        command = ["schedulerExecutionId=<aws.scheduler.execution-id>"]
        environment = [{
          name  = "SPRING_BATCH_JOB_NAME"
          value = "propertyEventRelayJob"
        }]
      }]
    })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot["property-event-relay"].arn
      launch_type         = "FARGATE"
      platform_version    = "LATEST"
      task_count          = 1
      network_configuration {
        subnets          = values(aws_subnet.application)[*].id
        security_groups  = [aws_security_group.task["property-event-relay"].id]
        assign_public_ip = false
      }
    }
    retry_policy {
      maximum_event_age_in_seconds = 300
      maximum_retry_attempts       = 2
    }
  }
}

resource "aws_iam_role" "property_event_retention_scheduler" {
  name               = "${local.name}-property-event-retention-scheduler"
  assume_role_policy = local.scheduler_assume_policies["property-event-retention"]
}

resource "aws_iam_role_policy" "property_event_retention_scheduler" {
  name = "run-property-event-retention-only"
  role = aws_iam_role.property_event_retention_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = [aws_ecs_task_definition.one_shot["property-event-maintenance"].arn]
        Condition = {
          ArnEquals = { "ecs:cluster" = aws_ecs_cluster.this.arn }
        }
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.workload_execution["property-event-maintenance"].arn,
          aws_iam_role.workload_task["property-event-maintenance"].arn,
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

resource "aws_scheduler_schedule_group" "property_event_retention" {
  name = "${local.name}-property-event-retention"
}

locals {
  property_event_retention_security_group_name = "property-event-maintenance"
}

resource "aws_scheduler_schedule" "property_event_retention" {
  name                         = "${local.name}-property-event-retention"
  group_name                   = aws_scheduler_schedule_group.property_event_retention.name
  schedule_expression          = "cron(15 4 * * ? *)"
  schedule_expression_timezone = "Asia/Seoul"
  state                        = var.enable_property_event_retention_schedule ? "ENABLED" : "DISABLED"
  flexible_time_window { mode = "OFF" }

  target {
    arn      = aws_ecs_cluster.this.arn
    role_arn = aws_iam_role.property_event_retention_scheduler.arn
    dead_letter_config {
      arn = aws_sqs_queue.scheduler_failure.arn
    }
    input = jsonencode({
      containerOverrides = [{
        name    = "property-event-maintenance"
        command = ["schedulerExecutionId=<aws.scheduler.execution-id>"]
        environment = [{
          name  = "SPRING_BATCH_JOB_NAME"
          value = "propertyEventOutboxRetentionJob"
        }]
      }]
    })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.one_shot["property-event-maintenance"].arn
      launch_type         = "FARGATE"
      platform_version    = "LATEST"
      task_count          = 1
      network_configuration {
        subnets          = values(aws_subnet.application)[*].id
        security_groups  = [aws_security_group.task[local.property_event_retention_security_group_name].id]
        assign_public_ip = false
      }
    }
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 2
    }
  }
}

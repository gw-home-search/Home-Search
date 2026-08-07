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

override_resource {
  target          = aws_sns_topic.operations
  override_during = plan
  values = {
    arn = "arn:aws:sns:ap-northeast-2:123456789012:home-search-staging-operations"
  }
}

override_resource {
  target          = aws_lb.public
  override_during = plan
  values = {
    arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/home-search-staging-public/0123456789abcdef"
    arn_suffix = "app/home-search-staging-public/0123456789abcdef"
  }
}

override_resource {
  target          = aws_lb_target_group.gateway["public-gateway"]
  override_during = plan
  values = {
    arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/home-search-staging-public/0123456789abcdef"
    arn_suffix = "targetgroup/home-search-staging-public/0123456789abcdef"
  }
}

run "runtime_alarms_have_actions_and_owned_dimensions" {
  command = plan
  variables {
    admin_allowed_cidrs    = ["203.0.113.10/32"]
    public_origin          = "https://staging.example.test"
    admin_origin           = "https://admin.staging.example.test"
    public_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn  = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    enable_services        = true
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff", "seo-renderer",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      aws_cloudwatch_metric_alarm.public_target_5xx.threshold == 1,
      aws_cloudwatch_metric_alarm.public_target_5xx.comparison_operator == "GreaterThanThreshold",
      length(aws_cloudwatch_metric_alarm.public_target_5xx.alarm_actions) > 0,
      length(aws_cloudwatch_metric_alarm.public_target_5xx.metric_query) == 3,
    ])
    error_message = "Public target 5xx must alarm above one percent with an SNS action."
  }

  assert {
    condition = alltrue([
      length(aws_cloudwatch_metric_alarm.ecs_running_task) == length(aws_ecs_service.service),
      alltrue([
        for alarm in values(aws_cloudwatch_metric_alarm.ecs_running_task) :
        alarm.namespace == "ECS/ContainerInsights" &&
        alarm.metric_name == "RunningTaskCount" &&
        alarm.treat_missing_data == "breaching" &&
        length(alarm.alarm_actions) > 0
      ]),
      aws_cloudwatch_metric_alarm.user_insight_worker_running[0].metric_name == "RunningTaskCount",
      length(aws_cloudwatch_metric_alarm.user_insight_worker_running[0].alarm_actions) > 0,
    ])
    error_message = "Every enabled ECS service, including the worker, needs a running-task alarm."
  }

  assert {
    condition = alltrue([
      length(aws_cloudwatch_metric_alarm.rds_cpu) == 2,
      length(aws_cloudwatch_metric_alarm.rds_free_storage) == 2,
      aws_cloudwatch_metric_alarm.rds_connections.threshold > 0,
      aws_cloudwatch_metric_alarm.valkey_evictions.threshold == 0,
      alltrue([
        for alarm in concat(
          values(aws_cloudwatch_metric_alarm.rds_cpu),
          values(aws_cloudwatch_metric_alarm.rds_free_storage),
          [aws_cloudwatch_metric_alarm.rds_connections, aws_cloudwatch_metric_alarm.valkey_evictions]
        ) : length(alarm.alarm_actions) > 0
      ]),
    ])
    error_message = "RDS and Valkey alarms must cover CPU, storage, connections, and any eviction."
  }

  assert {
    condition = alltrue([
      aws_cloudwatch_metric_alarm.user_insight_lag.namespace == "AWS/Kafka",
      aws_cloudwatch_metric_alarm.user_insight_lag.metric_name == "EstimatedMaxTimeLag",
      aws_cloudwatch_metric_alarm.user_insight_lag.threshold == 300,
      aws_cloudwatch_metric_alarm.user_insight_lag.dimensions["Cluster Name"] == aws_msk_serverless_cluster.events.cluster_name,
      aws_cloudwatch_metric_alarm.user_insight_lag.dimensions["Consumer Group"] == local.user_insight_group,
      aws_cloudwatch_metric_alarm.user_insight_lag.dimensions.Topic == local.user_insight_main_topic,
      length(aws_cloudwatch_metric_alarm.user_insight_lag.alarm_actions) > 0,
      aws_cloudwatch_metric_alarm.user_insight_dlq_messages.metric_name == "MessagesInPerSec",
      aws_cloudwatch_metric_alarm.user_insight_dlq_messages.dimensions.Topic == local.user_insight_dlq_topic,
      aws_cloudwatch_metric_alarm.user_insight_dlq_messages.threshold == 0,
      length(aws_cloudwatch_metric_alarm.user_insight_dlq_messages.alarm_actions) > 0,
    ])
    error_message = "The user insight consumer needs supported MSK Serverless time-lag and DLQ ingress alarms."
  }
}

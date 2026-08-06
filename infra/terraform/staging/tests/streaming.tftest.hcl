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

run "private_iam_streaming_and_registry_ownership" {
  command = plan
  variables {
    admin_allowed_cidrs                      = ["203.0.113.10/32"]
    public_origin                            = "https://staging.example.test"
    admin_origin                             = "https://admin.staging.example.test"
    public_certificate_arn                   = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn                    = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    enable_property_event_relay_schedule     = true
    enable_property_event_retention_schedule = true
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff", "seo-renderer",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      aws_msk_serverless_cluster.events.client_authentication[0].sasl[0].iam[0].enabled,
      length(aws_subnet.data) == 2,
      length(aws_vpc_security_group_ingress_rule.streaming_from_workload) == 2,
      toset(keys(aws_vpc_security_group_ingress_rule.streaming_from_workload)) == toset(["property-event-relay", "user-insight-worker"]),
      contains(local.task_security_group_names, "property-event-relay"),
      aws_glue_registry.events.registry_name == "home-search-staging-events",
    ])
    error_message = "Staging streaming must use private two-AZ MSK Serverless with IAM auth and a Terraform-owned Glue registry."
  }

  assert {
    condition = alltrue([
      local.property_event_topics == toset([
        "property.trade-events.v1",
        "property.complex-events.v1",
        "property.insight-events.v1",
      ]),
      local.property_event_producer_cluster_actions == toset([
        "kafka-cluster:Connect",
        "kafka-cluster:DescribeCluster",
        "kafka-cluster:WriteDataIdempotently",
      ]),
      local.property_event_producer_topic_actions == toset([
        "kafka-cluster:DescribeTopic",
        "kafka-cluster:WriteData",
      ]),
      contains(
        [for item in local.one_shot_specs["property-event-relay"].environment : item.name],
        "HOME_KAFKA_BOOTSTRAP_SERVERS",
      ),
      one([
        for item in local.one_shot_specs["property-event-relay"].environment :
        item.value if item.name == "HOME_EVENTS_RELAY_ENABLED"
      ]) == "true",
    ])
    error_message = "The property relay role must be producer-only for the three governed property topics."
  }

  assert {
    condition = alltrue([
      aws_scheduler_schedule.property_event_relay.schedule_expression == "rate(5 minutes)",
      aws_scheduler_schedule.property_event_relay.schedule_expression_timezone == "Asia/Seoul",
      aws_scheduler_schedule.property_event_relay.state == "ENABLED",
      !aws_scheduler_schedule.property_event_relay.target[0].ecs_parameters[0].network_configuration[0].assign_public_ip,
      length(aws_scheduler_schedule.property_event_relay.target[0].ecs_parameters[0].network_configuration[0].security_groups) == 1,
      length(aws_scheduler_schedule.property_event_relay.target[0].dead_letter_config) == 1,
      jsondecode(aws_scheduler_schedule.property_event_relay.target[0].input).containerOverrides[0].name == "property-event-relay",
      jsondecode(aws_scheduler_schedule.property_event_relay.target[0].input).containerOverrides[0].environment[0].value == "propertyEventRelayJob",
      jsondecode(aws_scheduler_schedule.property_event_relay.target[0].input).containerOverrides[0].command == ["schedulerExecutionId=<aws.scheduler.execution-id>"],
    ])
    error_message = "The property relay must run as a private five-minute one-shot task with a unique scheduler execution id."
  }

  assert {
    condition = alltrue([
      aws_scheduler_schedule.property_event_retention.schedule_expression == "cron(15 4 * * ? *)",
      aws_scheduler_schedule.property_event_retention.schedule_expression_timezone == "Asia/Seoul",
      aws_scheduler_schedule.property_event_retention.state == "ENABLED",
      !aws_scheduler_schedule.property_event_retention.target[0].ecs_parameters[0].network_configuration[0].assign_public_ip,
      local.property_event_retention_security_group_name == "property-event-maintenance",
      jsondecode(aws_scheduler_schedule.property_event_retention.target[0].input).containerOverrides[0].name == "property-event-maintenance",
      jsondecode(aws_scheduler_schedule.property_event_retention.target[0].input).containerOverrides[0].environment[0].value == "propertyEventOutboxRetentionJob",
      jsondecode(aws_scheduler_schedule.property_event_retention.target[0].input).containerOverrides[0].command == ["schedulerExecutionId=<aws.scheduler.execution-id>"],
      local.workload_execution_secret_names["property-event-maintenance"] == ["property-runtime-db"],
      local.workload_task_role_names["property-event-relay"] != local.workload_task_role_names["property-event-maintenance"],
    ])
    error_message = "Outbox retention must run daily as a private DB-only task without Kafka producer permissions."
  }

  assert {
    condition = alltrue([
      aws_iam_role_policy.user_insight_consumer.name == "consume-property-insight-events",
      local.user_insight_main_topic == "property.insight-events.v1",
      local.user_insight_dlq_topic == "property.insight-events.v1.dlq",
      local.user_insight_group == "user-digest-v1",
      local.user_insight_consumer_topic_actions == toset(["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"]),
      local.user_insight_dlq_topic_actions == toset(["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"]),
      local.user_insight_group_actions == toset(["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]),
      contains(local.workload_names, "user-insight-worker"),
    ])
    error_message = "The user insight worker must consume only insight events and write only its DLQ with its own group."
  }
}

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

run "private_market_news_schedules_are_explicitly_enabled" {
  command = plan
  variables {
    admin_allowed_cidrs          = ["203.0.113.10/32"]
    public_origin                = "https://staging.example.test"
    admin_origin                 = "https://admin.staging.example.test"
    public_certificate_arn       = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111"
    admin_certificate_arn        = "arn:aws:acm:ap-northeast-2:123456789012:certificate/22222222-2222-2222-2222-222222222222"
    enable_market_news_schedules = true
    enable_market_news_public    = true
    image_digests = { for name in [
      "property-api", "property-batch", "property-flyway", "admin-api", "admin-migration", "admin-ops",
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      aws_scheduler_schedule.market_news["general"].schedule_expression == "cron(30 0,12,18 * * ? *)",
      aws_scheduler_schedule.market_news["morning"].schedule_expression == "cron(30 6 * * ? *)",
      aws_scheduler_schedule.market_news["major-selection"].schedule_expression == "cron(30 5 ? * MON *)",
      aws_scheduler_schedule.market_news["retention"].schedule_expression == "cron(30 20 * * ? *)",
      alltrue([
        for schedule in values(aws_scheduler_schedule.market_news) :
        schedule.schedule_expression_timezone == "Asia/Seoul" &&
        schedule.state == "ENABLED" &&
        !schedule.target[0].ecs_parameters[0].network_configuration[0].assign_public_ip &&
        length(schedule.target[0].dead_letter_config) == 1
      ]),
    ])
    error_message = "Market news schedules must use the reviewed KST cadence, private Fargate networking, and the shared Scheduler DLQ."
  }

  assert {
    condition = alltrue([
      toset([
        for schedule in values(aws_scheduler_schedule.market_news) :
        jsondecode(schedule.target[0].input).containerOverrides[0].environment[0].value
        ]) == toset([
        "marketNewsGeneralJob",
        "marketNewsMorningJob",
        "marketNewsMajorSelectionJob",
        "marketNewsRetentionJob",
      ]),
      alltrue([
        for schedule in values(aws_scheduler_schedule.market_news) :
        jsondecode(schedule.target[0].input).containerOverrides[0].command == [
          "schedulerExecutionId=<aws.scheduler.execution-id>"
        ] &&
        jsondecode(schedule.target[0].input).containerOverrides[0].environment[1].value == "true"
      ]),
    ])
    error_message = "Every market news task must use a scheduler-scoped idempotency key and enable the provider only in its one-shot container."
  }

  assert {
    condition = alltrue([
      one([
        for item in local.service_specs["property-api"].environment :
        item.value if item.name == "HOME_NEWS_PUBLIC_ENABLED"
      ]) == "true",
      one([
        for item in local.one_shot_specs["property-batch"].environment :
        item.value if item.name == "SPRING_DATA_REDIS_SSL_ENABLED"
      ]) == "true",
    ])
    error_message = "News publication must be independently enabled while property Batch reaches TLS Redis through the ops task boundary."
  }
}

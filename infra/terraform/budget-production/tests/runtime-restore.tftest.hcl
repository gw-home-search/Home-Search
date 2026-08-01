mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

mock_provider "aws" { alias = "retained_ssm" }

variables {
  ami_id                   = "ami-0123456789abcdef0"
  availability_zone        = "ap-northeast-2a"
  hosted_zone_id           = "Z0123456789ABCDEFG"
  alarm_email              = "operator@example.com"
  cost_anomaly_monitor_arn = "arn:aws:ce::123456789012:anomalymonitor/11111111-1111-1111-1111-111111111111"
  deployment_release_tag   = "v1.2.3"
  image_uris = {
    property-api = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-batch = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-batch@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-flyway = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-api = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-migration = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-ops = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-ops@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-api = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-insight-worker = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-insight-worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-flyway = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    source-data-migration = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/source-data-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    public-gateway = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/public-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-gateway = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    backup = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/backup@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ops-bootstrap = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ops-bootstrap@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ml = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ml@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ai = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ai@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    chat-bff = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/chat-bff@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  platform_image_uris = {
    budget-postgres = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    budget-valkey = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-valkey@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
}

run "prep_pins_every_application_service_without_activation" {
  command = plan
  variables {
    deployment_phase      = "public"
    data_services_enabled = true
    public_dns_enabled    = true
    application_service_task_definition_arns = {
      property-api = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/property-api:7"
      admin-api = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/admin-api:7"
      user-api = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/user-api:7"
      ai = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/ai:7"
      chat-bff = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/chat-bff:7"
      public-gateway = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/public-gateway:7"
      admin-gateway = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/admin-gateway:7"
      ml = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/ml:7"
    }
    application_service_desired_counts = {
      property-api = 1, admin-api = 0, user-api = 1, ai = 1,
      chat-bff = 1, public-gateway = 1, admin-gateway = 0, ml = 0
    }
  }

  assert {
    condition = alltrue([
      for name, service in aws_ecs_service.application :
      service.task_definition == var.application_service_task_definition_arns[name]
      && service.desired_count == var.application_service_desired_counts[name]
    ])
    error_message = "Prep plan must pin all eight application service task definitions and desired counts."
  }
}

run "runtime_restore_enables_only_approved_features" {
  command = plan
  variables {
    deployment_phase              = "public"
    data_services_enabled         = true
    public_dns_enabled            = true
    backup_schedules_enabled      = true
    market_news_public_enabled    = true
    market_news_schedules_enabled = true
    rtms_refresh_schedule_enabled = true
    prediction_enabled            = true
    ml_service_enabled            = true
    user_oauth_enabled_providers  = ["google", "kakao", "naver"]
  }

  assert {
    condition = (
      aws_ecs_service.application["ml"].desired_count == 1
      && one([for item in local.application_specs["property-api"].environment : item.value if item.name == "HOME_NEWS_PUBLIC_ENABLED"]) == "true"
      && one([for item in local.application_specs["property-api"].environment : item.value if item.name == "HOME_PREDICTION_ENABLED"]) == "true"
      && one([for item in local.application_specs["property-api"].environment : item.value if item.name == "HOME_PREDICTION_CLIENT_BASE_URL"]) == "http://172.31.255.1:18085"
      && one([for item in local.application_specs["user-api"].environment : item.value if item.name == "HOME_USER_OAUTH_ENABLED_PROVIDERS"]) == "google,kakao,naver"
      && toset(keys(local.application_secret_parameters["user-api"])) == toset([
        "USER_DB_PASSWORD", "GOOGLE_OAUTH_CLIENT_ID", "GOOGLE_OAUTH_CLIENT_SECRET",
        "KAKAO_OAUTH_CLIENT_ID", "KAKAO_OAUTH_CLIENT_SECRET", "NAVER_OAUTH_CLIENT_ID", "NAVER_OAUTH_CLIENT_SECRET",
      ])
    )
    error_message = "Property prediction/news, ML, and three-provider OAuth must be wired together."
  }

  assert {
    condition = (
      aws_scheduler_schedule.rtms_daily_refresh[0].state == "ENABLED"
      && length(aws_scheduler_schedule.market_news) == 4
      && alltrue([for schedule in aws_scheduler_schedule.market_news : schedule.state == "ENABLED"])
      && toset([for name in keys(local.one_shot_specs) : name if startswith(name, "market-news-")]) == toset([
        "market-news-general", "market-news-morning", "market-news-major-complex",
        "market-news-major-selection", "market-news-retention", "market-news-quality-sample", "market-news-withdrawal",
      ])
    )
    error_message = "RTMS and four market-news schedules must be independently enabled with seven reviewed one-shot tasks."
  }
}

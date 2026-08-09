mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

mock_provider "aws" { alias = "retained_ssm" }

variables {
  ami_id                           = "ami-0123456789abcdef0"
  availability_zone                = "ap-northeast-2a"
  hosted_zone_id                   = "Z0123456789ABCDEFG"
  alarm_email                      = "operator@example.com"
  cost_anomaly_monitor_arn         = "arn:aws:ce::123456789012:anomalymonitor/11111111-1111-1111-1111-111111111111"
  deployment_release_tag           = "v1.2.3"
  rtms_refresh_task_definition_arn = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-rtms-daily-refresh:23"
  image_uris = {
    property-api          = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-batch        = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-batch@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    property-flyway       = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/property-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-api             = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-migration       = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-ops             = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-ops@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-api              = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-insight-worker   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-insight-worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    user-flyway           = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/user-flyway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    source-data-migration = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/source-data-migration@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    public-gateway        = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/public-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    admin-gateway         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/admin-gateway@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    backup                = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/backup@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ops-bootstrap         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ops-bootstrap@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ml                    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ml@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ai                    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/ai@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    chat-bff              = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/chat-bff@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    seo-renderer          = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/seo-renderer@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  platform_image_uris = {
    budget-postgres = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    budget-valkey   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-valkey@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
}

run "prep_pins_every_application_service_without_activation" {
  command = plan
  variables {
    deployment_phase      = "public"
    data_services_enabled = true
    public_dns_enabled    = true
    application_service_task_definition_arns = {
      property-api   = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-property-api:7"
      admin-api      = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-admin-api:7"
      user-api       = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-user-api:7"
      ai             = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-ai:7"
      chat-bff       = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-chat-bff:7"
      public-gateway = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-public-gateway:7"
      admin-gateway  = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-admin-gateway:7"
      ml             = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-ml:7"
    }
    application_service_desired_counts = {
      property-api = 1, admin-api = 0, user-api = 1, ai = 1,
      chat-bff     = 1, public-gateway = 1, admin-gateway = 0, ml = 0
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

run "prep_rejects_cross_family_application_pin" {
  command = plan
  variables {
    application_service_task_definition_arns = {
      property-api   = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-property-api:7"
      admin-api      = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-admin-api:7"
      user-api       = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-user-api:7"
      ai             = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-property-api:7"
      chat-bff       = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-chat-bff:7"
      public-gateway = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-public-gateway:7"
      admin-gateway  = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-admin-gateway:7"
      ml             = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/home-search-budget-production-ml:7"
    }
  }
  expect_failures = [var.application_service_task_definition_arns]
}

run "runtime_restore_enables_only_approved_features" {
  command = plan
  variables {
    deployment_phase              = "public"
    data_services_enabled         = true
    public_dns_enabled            = true
    backup_schedules_enabled      = true
    market_news_public_enabled    = true
    market_news_schedules_enabled = false
    rtms_refresh_schedule_enabled = true
    prediction_enabled            = true
    ml_service_enabled            = true
    user_oauth_enabled_providers  = ["google", "kakao", "naver"]
  }

  assert {
    condition = (
      strcontains(
        jsonencode(local.rtms_refresh_definition),
        var.rtms_refresh_task_definition_arn,
      )
    )
    error_message = "RTMS Scheduler must preserve the explicitly reviewed immutable task definition revision."
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
      && length(setintersection(local.managed_runtime_parameter_names, local.bootstrap_external_runtime_parameter_names)) == 0
      && alltrue([
        for name in local.bootstrap_external_runtime_parameter_names :
        endswith(local.runtime_parameter_arns[name], "/home-search/budget-production/${name}")
      ])
    )
    error_message = "Property prediction/news, ML, and three-provider OAuth must be wired together."
  }

  assert {
    condition = (
      aws_ecs_task_definition.application["public-gateway"].cpu == "256"
      && aws_ecs_task_definition.application["public-gateway"].memory == "512"
      && toset([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.name]) == toset(["public-gateway", "seo-renderer"])
      && one([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.essential if container.name == "seo-renderer"]) == false
      && one([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.memoryReservation if container.name == "seo-renderer"]) == 128
      && one([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.memory if container.name == "seo-renderer"]) == 192
      && one([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.image if container.name == "seo-renderer"]) == var.image_uris["seo-renderer"]
      && one([for container in jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions) : container.links if container.name == "public-gateway"]) == ["seo-renderer:seo-renderer"]
    )
    error_message = "Public gateway must run the non-essential immutable SEO renderer sidecar within the reviewed CPU/memory envelope."
  }

  assert {
    condition = (
      aws_scheduler_schedule.rtms_daily_refresh[0].state == "ENABLED"
      && length(aws_scheduler_schedule.market_news) == 4
      && alltrue([for schedule in aws_scheduler_schedule.market_news : schedule.state == "DISABLED"])
      && toset([for name in keys(local.one_shot_specs) : name if startswith(name, "market-news-")]) == toset([
        "market-news-general", "market-news-morning", "market-news-major-complex",
        "market-news-major-selection", "market-news-retention", "market-news-quality-sample", "market-news-withdrawal",
      ])
    )
    error_message = "RTMS must be enabled while all four market-news schedules remain disabled."
  }
}

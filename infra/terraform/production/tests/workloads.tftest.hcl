mock_provider "aws" {
  mock_data "aws_availability_zones" { defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

mock_provider "aws" { alias = "backup" }

run "private_digest_pinned_production_workloads" {
  command = plan
  variables {
    owner                             = "platform"
    client_vpn_cidr                   = "10.90.0.0/22"
    operator_group_id                 = "operators"
    client_vpn_server_certificate_arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/server"
    client_vpn_saml_provider_arn      = "arn:aws:iam::123456789012:saml-provider/operators"
    public_certificate_arn            = "arn:aws:acm:ap-northeast-2:123456789012:certificate/public"
    admin_certificate_arn             = "arn:aws:acm:ap-northeast-2:123456789012:certificate/admin"
    public_origin                     = "https://home.example.invalid"
    monthly_budget_usd                = 5000
    budget_notification_emails        = ["ops@example.invalid"]
    alarm_topic_arn                   = "arn:aws:sns:ap-northeast-2:123456789012:alarms"
    enable_services                   = true
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
    }
  }

  assert {
    condition     = length(aws_ecs_task_definition.service) == 9 && length(aws_ecs_service.service) == 9
    error_message = "All request-serving services and the required worker must have production task definitions and services."
  }

  assert {
    condition = alltrue([
      for service in aws_ecs_service.service :
      service.desired_count >= 2
      && service.deployment_minimum_healthy_percent == 100
      && service.deployment_maximum_percent == 200
      && service.deployment_circuit_breaker[0].rollback
      && service.availability_zone_rebalancing == "ENABLED"
      && !service.network_configuration[0].assign_public_ip
    ])
    error_message = "Production workloads must run at least two private tasks with fail-closed rolling rollback."
  }

  assert {
    condition = (
      aws_lb.admin.internal
      && !aws_lb.public.internal
      && aws_lb_listener.public_https.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06"
      && length([aws_wafv2_web_acl_association.public]) == 1
      && aws_vpc_security_group_ingress_rule.admin_alb_https.cidr_ipv4 == "10.90.0.0/22"
    )
    error_message = "Only the WAF-protected public ALB may be internet-facing; Admin must use an internal TLS ALB."
  }

  assert {
    condition = (
      length(aws_ecs_service.service["ai"].load_balancer) == 0
      && length(aws_ecs_service.service["chat-bff"].load_balancer) == 0
      && one([for item in local.service_specs["ai"].environment : item.value if item.name == "HOME_AI_DEPLOYMENT_TIER"]) == "production"
      && one([for item in local.service_specs["ai"].environment : item.value if item.name == "HOME_AI_SUPERVISOR_GRAPH_MODE"]) == "active"
      && one([for item in local.service_specs["ai"].environment : item.value if item.name == "HOME_AI_ENABLED_REFERENCE_CAPABILITIES"]) == "academy_lookup,rail_station_lookup,school_location,retail_location"
      && strcontains(join(" ", local.service_specs["property-api"].health), "/actuator/health/readiness")
      && strcontains(join(" ", local.service_specs["admin-api"].health), "/actuator/health/readiness")
      && strcontains(join(" ", local.service_specs["user-api"].health), "/actuator/health/readiness")
    )
    error_message = "AI must remain private while the approved production Supervisor Graph capability set is active."
  }

  assert {
    condition = alltrue([
      for uri in values(var.image_uris) :
      strcontains(uri, "@sha256:")
    ])
    error_message = "Every production service image must be pinned to the immutable release digest."
  }

  assert {
    condition = (
      length(aws_ecs_task_definition.one_shot) >= 9
      && contains(keys(aws_ecs_task_definition.one_shot), "property-flyway")
      && contains(keys(aws_ecs_task_definition.one_shot), "admin-migration")
      && contains(keys(aws_ecs_task_definition.one_shot), "user-flyway")
      && contains(keys(aws_ecs_task_definition.one_shot), "runtime-grants")
      && contains(keys(aws_ecs_task_definition.one_shot), "map-marker-projection")
    )
    error_message = "Production migrations and operations must be finite ECS tasks, not permanent services."
  }
}

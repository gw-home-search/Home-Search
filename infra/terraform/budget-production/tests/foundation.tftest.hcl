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
  platform_image_uris = {
    budget-postgres = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-postgres@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    budget-valkey   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/home-search/budget-valkey@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
}

run "registry_phase_owns_only_budget_platform_repositories" {
  command = plan
  variables { deployment_phase = "registry" }

  assert {
    condition = (
      toset(keys(aws_ecr_repository.platform)) == toset(["budget-postgres", "budget-valkey"])
      && length(aws_instance.host) == 0
      && length(aws_ebs_volume.data) == 0
      && length(aws_ecs_cluster.this) == 0
    )
    error_message = "Registry phase must create only the two budget platform ECR repositories."
  }
}

run "foundation_is_single_az_single_instance_and_data_safe" {
  command = plan
  variables { deployment_phase = "foundation" }

  assert {
    condition = (
      length(aws_instance.host) == 1
      && aws_instance.host[0].instance_type == "t3a.large"
      && aws_instance.host[0].availability_zone == "ap-northeast-2a"
      && aws_instance.host[0].metadata_options[0].http_tokens == "required"
      && aws_instance.host[0].disable_api_termination
      && aws_instance.host[0].instance_initiated_shutdown_behavior == "stop"
      && output.ami_id == "ami-0123456789abcdef0"
      && output.availability_zone == "ap-northeast-2a"
    )
    error_message = "Foundation must use one protected t3a.large with IMDSv2 in the pinned AZ."
  }

  assert {
    condition = (
      length(aws_instance.host) == 1
      && length(regexall(
        "ignore_changes\\s*=\\s*\\[[^]]*associate_public_ip_address",
        file("foundation.tf"),
      )) == 1
    )
    error_message = "The retained EIP host must ignore provider-normalized public-IP association drift instead of replacing the instance."
  }

  assert {
    condition = (
      aws_ebs_volume.data[0].size == 80
      && aws_ebs_volume.data[0].type == "gp3"
      && aws_ebs_volume.data[0].iops == 3000
      && aws_ebs_volume.data[0].throughput == 125
      && aws_ebs_volume.data[0].encrypted
      && !aws_volume_attachment.data[0].force_detach
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "mkfs.xfs")
      && !strcontains(file("files/host-bootstrap.sh.tftpl"), "mkfs.xfs -f")
      && !strcontains(file("files/host-bootstrap.sh.tftpl"), "defaults,nofail")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "findmnt --mountpoint")
      && !strcontains(file("files/host-bootstrap.sh.tftpl"), "findmnt --noheadings --output SOURCE --target")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "http://127.0.0.1:51678/v1/metadata")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "aws ecs deregister-container-instance")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "unlink /var/lib/ecs/data/agent.db")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "systemctl restart docker\nsystemctl restart home-search-docker-guard.service\nsystemctl restart ecs")
      && strcontains(file("files/host-bootstrap.sh.tftpl"), "ConditionPathIsMountPoint=/srv/home-search")
      && length(regexall("install -d -m 0700 -o 70 -g 70", file("files/host-bootstrap.sh.tftpl"))) == 2
    )
    error_message = "Data EBS must stay protected and host bootstrap must mount the exact target and migrate ECS agent state safely."
  }

  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.public : rule.from_port]) == toset([80, 443])
      && alltrue([for rule in aws_vpc_security_group_ingress_rule.public : rule.to_port == rule.from_port])
      && toset([for rule in aws_security_group.host[0].egress : "${rule.protocol}:${rule.from_port}:${rule.to_port}"]) == toset([
        "tcp:443:443",
        "tcp:53:53",
        "udp:53:53",
        "udp:123:123",
      ])
      && alltrue([
        for rule in aws_security_group.host[0].egress :
        length(rule.cidr_blocks) == 1 && one(rule.cidr_blocks) == "0.0.0.0/0"
      ])
      && !strcontains(file("foundation.tf"), "resource \"aws_vpc_security_group_egress_rule\" \"host\"")
      && strcontains(file("foundation.tf"), "from = aws_vpc_security_group_egress_rule.host")
      && strcontains(file("foundation.tf"), "destroy = false")
      && length(aws_ssm_document.configure_edge) == 1
      && strcontains(file("files/configure-edge.sh.tftpl"), "proxy_buffering off")
      && strcontains(file("files/configure-edge.sh.tftpl"), "acm export-certificate")
      && aws_dlm_lifecycle_policy.data[0].state == "DISABLED"
      && aws_dlm_lifecycle_policy.data[0].policy_details[0].schedule[0].retain_rule[0].count == 7
      && aws_security_group.recovery[0].description == "Ephemeral recovery rehearsal; intentionally no ingress"
      && length(aws_ssm_document.configure_observability) == 1
      && strcontains(file("host_observability.tf"), "resource \"aws_ssm_document\" \"configure_host\"")
      && strcontains(file("host_observability.tf"), "resource \"aws_ssm_association\" \"configure_host\"")
      && length(regexall("depends_on\\s*=\\s*\\[aws_ssm_association.configure_host\\]", file("host_observability.tf"))) == 1
    )
    error_message = "The host must remove default egress, support retryable bootstrap, and preserve edge TLS behavior."
  }

  assert {
    condition = (
      length(aws_budgets_budget.monthly[0].notification) == 3
      && length(aws_ce_anomaly_subscription.daily) == 1
      && one(aws_ce_anomaly_subscription.daily[0].monitor_arn_list) == var.cost_anomaly_monitor_arn
      && length(regexall("resource \"aws_ce_anomaly_monitor\"", file("cost.tf"))) == 0
    )
    error_message = "Foundation must reuse the exact external account-wide anomaly monitor and own only its budget subscription."
  }

  assert {
    condition = (
      aws_iam_role_policy_attachment.dlm[0].policy_arn == "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
      && alltrue([
        for parameter in aws_ssm_parameter.runtime :
        parameter.value_wo_version == 1
      ])
      && aws_ssm_parameter.retained_apt_service_key[0].value_wo_version == 1
      && strcontains(file("ssm_parameter_containers.tf"), "value_wo")
      && !strcontains(file("ssm_parameter_containers.tf"), "value       = \"UNSET\"")
      && !strcontains(file("ssm_parameter_containers.tf"), "ignore_changes")
    )
    error_message = "Foundation must use the official DLM snapshot role and write-only SSM seed values that never enter Terraform state."
  }
}

run "public_dns_is_an_explicit_last_step" {
  command = plan
  variables {
    deployment_phase      = "public"
    data_services_enabled = true
    public_dns_enabled    = false
  }

  assert {
    condition = (
      length(aws_route53_record.public) == 0
      && aws_ecs_service.application["public-gateway"].desired_count == 1
    )
    error_message = "Public phase must keep DNS disabled until a separate approved apply."
  }
}

run "data_phase_keeps_platform_services_dark_before_secret_bootstrap" {
  command = plan
  variables { deployment_phase = "data" }

  assert {
    condition = (
      toset(keys(aws_ecs_task_definition.platform)) == toset(["budget-postgres", "budget-valkey"])
      && toset(keys(aws_ecs_task_definition.one_shot)) == toset([
        "secret-bootstrap",
        "secret-readiness",
        "property-flyway",
        "user-flyway",
        "admin-migration",
        "ai-migration",
        "importer-grants",
        "scheduled-backup",
        "data-import-reconcile",
        "map-marker-projection",
        "rtms-daily-refresh",
        "runtime-grants",
      ])
      && length(aws_ecs_task_definition.application) == 0
      && aws_ecs_service.platform["budget-postgres"].desired_count == 0
      && aws_ecs_service.platform["budget-valkey"].desired_count == 0
      && aws_ecs_task_definition.platform["budget-postgres"].enable_fault_injection == false
      && aws_ecs_task_definition.one_shot["secret-bootstrap"].enable_fault_injection == false
      && length(jsondecode(aws_ecs_task_definition.one_shot["secret-bootstrap"].container_definitions)[0].portMappings) == 0
      && length(jsondecode(aws_ecs_task_definition.one_shot["secret-bootstrap"].container_definitions)[0].systemControls) == 0
      && length(jsondecode(aws_ecs_task_definition.one_shot["secret-bootstrap"].container_definitions)[0].volumesFrom) == 0
      && length(jsondecode(aws_ecs_task_definition.one_shot["secret-bootstrap"].container_definitions)[0].linuxParameters.capabilities.add) == 0
      && strcontains(file("runtime.tf"), "configure_at_launch = false")
      && strcontains(file("runtime.tf"), "systemControls         = []")
      && strcontains(file("runtime.tf"), "volumesFrom            = []")
      && strcontains(file("runtime.tf"), "capabilities       = { add = [], drop = [\"NET_RAW\"] }")
      && strcontains(file("one_shot.tf"), "configure_at_launch = false")
      && one([
        for item in local.one_shot_specs["secret-readiness"].environment :
        item.value if item.name == "HOME_USER_OAUTH_ENABLED_PROVIDERS"
      ]) == "kakao"
      && local.one_shot_specs["property-flyway"].command == ["-target=40", "migrate"]
      && contains(local.external_runtime_parameter_names, "property/apt-service-key")
      && toset(keys(local.one_shot_secret_parameters["rtms-daily-refresh"])) == toset([
        "DB_PASSWORD",
        "APT_SERVICE_KEY",
      ])
      && one([
        for item in local.one_shot_specs["rtms-daily-refresh"].environment :
        item.value if item.name == "SPRING_BATCH_JOB_NAME"
      ]) == "rtmsDailyRefreshJob"
      && one([
        for item in local.one_shot_specs["rtms-daily-refresh"].environment :
        item.value if item.name == "HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY"
      ]) == "true"
      && one([
        for item in local.one_shot_specs["rtms-daily-refresh"].environment :
        item.value if item.name == "HOME_INSIGHT_TRADE_ENABLED"
      ]) == "true"
      && length(aws_scheduler_schedule.rtms_daily_refresh) == 1
      && aws_scheduler_schedule.rtms_daily_refresh[0].schedule_expression == "cron(30 7 * * ? *)"
      && aws_scheduler_schedule.rtms_daily_refresh[0].schedule_expression_timezone == "Asia/Seoul"
      && aws_scheduler_schedule.rtms_daily_refresh[0].state == "DISABLED"
      && jsondecode(aws_scheduler_schedule.rtms_daily_refresh[0].target[0].input).containerOverrides[0].command == [
        "schedulerExecutionId=<aws.scheduler.execution-id>",
      ]
    )
    error_message = "Data phase must define digest-pinned platform tasks but keep them stopped before secret bootstrap."
  }
}

run "data_phase_starts_platform_services_only_after_secret_bootstrap" {
  command = plan
  variables {
    deployment_phase      = "data"
    data_services_enabled = true
  }

  assert {
    condition = (
      aws_ecs_service.platform["budget-postgres"].desired_count == 1
      && aws_ecs_service.platform["budget-valkey"].desired_count == 1
      && length(aws_iam_role_policy.secret_bootstrap) == 1
      && length(setintersection(
        local.generated_runtime_parameter_names,
        local.external_runtime_parameter_names,
      )) == 0
      && aws_scheduler_schedule.logical_backup[0].state == "DISABLED"
      && strcontains(file("one_shot.tf"), "/usr/local/bin/run-budget-pg-backup")
    )
    error_message = "The explicit post-bootstrap gate must start exactly one PostgreSQL and one Valkey task."
  }
}

run "post_cutover_enables_backup_and_public_alarms" {
  command = plan
  variables {
    deployment_phase         = "public"
    data_services_enabled    = true
    public_dns_enabled       = true
    backup_schedules_enabled = true
  }

  assert {
    condition = (
      length(aws_route53_record.public) == 1
      && aws_dlm_lifecycle_policy.data[0].state == "ENABLED"
      && aws_scheduler_schedule.logical_backup[0].state == "ENABLED"
      && aws_scheduler_schedule.rtms_daily_refresh[0].state == "ENABLED"
      && length(aws_cloudwatch_metric_alarm.ecs_running) == 1
      && length(aws_cloudwatch_metric_alarm.backup_age) == 1
      && length(aws_cloudwatch_metric_alarm.map_p95) == 1
      && length(aws_cloudwatch_metric_alarm.public_5xx) == 1
    )
    error_message = "Only explicit post-cutover input may enable backups, DNS, and public service alarms."
  }
}

run "private_phase_uses_fixed_bridge_ports_and_least_privilege_roles" {
  command = plan
  variables {
    deployment_phase                        = "private"
    data_services_enabled                   = true
    ai_supervisor_graph_mode                = "off"
    ai_supervisor_graph_canary_percent      = 0
    application_deployment_maximum_percents = {}
  }

  assert {
    condition = (
      aws_ecs_task_definition.application["public-gateway"].network_mode == "bridge"
      && jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions)[0].portMappings[0].containerPort == 8080
      && jsondecode(aws_ecs_task_definition.application["public-gateway"].container_definitions)[0].portMappings[0].hostPort == 18000
      && aws_ecs_task_definition.application["public-gateway"].enable_fault_injection == false
      && strcontains(file("runtime.tf"), "configure_at_launch = false")
      && strcontains(file("runtime.tf"), "portMappings           = []")
      && strcontains(file("runtime.tf"), "systemControls         = []")
      && strcontains(file("runtime.tf"), "volumesFrom            = []")
      && strcontains(file("runtime.tf"), "capabilities       = { add = [], drop = [\"NET_RAW\"] }")
      && aws_ecs_service.application["public-gateway"].desired_count == 0
      && aws_ecs_service.application["property-api"].desired_count == 1
      && aws_ecs_service.application["user-api"].desired_count == 1
      && aws_ecs_service.application["ai"].desired_count == 1
      && aws_ecs_service.application["chat-bff"].desired_count == 1
      && aws_ecs_service.application["property-api"].deployment_minimum_healthy_percent == 0
      && aws_ecs_service.application["property-api"].deployment_maximum_percent == 100
      && aws_iam_role.task_execution["property-api"].name != aws_iam_role.task_execution["user-api"].name
      && one([
        for item in local.application_specs["user-api"].environment :
        item.value if item.name == "HOME_USER_OAUTH_ENABLED_PROVIDERS"
      ]) == "kakao"
      && one([
        for item in local.application_specs["ai"].environment :
        item.value if item.name == "HOME_AI_SUPERVISOR_GRAPH_MODE"
      ]) == "off"
      && one([
        for item in local.application_specs["ai"].environment :
        item.value if item.name == "HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT"
      ]) == "0"
      && strcontains(file("ssm_parameter_containers.tf"), "retained_apt_service_key")
      && toset(keys(local.application_secret_parameters["user-api"])) == toset([
        "USER_DB_PASSWORD",
        "KAKAO_OAUTH_CLIENT_ID",
        "KAKAO_OAUTH_CLIENT_SECRET",
      ])
    )
    error_message = "Private phase must use fixed bridge ports, keep the gateway dark, and separate execution roles."
  }
}

run "incremental_rollout_preserves_live_operational_settings" {
  command = plan
  variables {
    deployment_phase                   = "private"
    data_services_enabled              = true
    ai_supervisor_graph_mode           = "active"
    ai_supervisor_graph_canary_percent = 100
    application_deployment_maximum_percents = {
      property-api = 200
      chat-bff     = 200
    }
    platform_deployment_release_tag = "v1.0.10"
  }

  assert {
    condition = (
      aws_ecs_service.application["property-api"].deployment_maximum_percent == 200
      && aws_ecs_service.application["chat-bff"].deployment_maximum_percent == 200
      && aws_ecs_service.application["user-api"].deployment_maximum_percent == 100
      && one([
        for item in local.application_specs["ai"].environment :
        item.value if item.name == "HOME_AI_SUPERVISOR_GRAPH_MODE"
      ]) == "active"
      && one([
        for item in local.application_specs["ai"].environment :
        item.value if item.name == "HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT"
      ]) == "100"
      && aws_ecs_task_definition.platform["budget-postgres"].tags.Release == "v1.0.10"
      && aws_ecs_task_definition.platform["budget-valkey"].tags.Release == "v1.0.10"
    )
    error_message = "Incremental rollout must preserve live AI, service deployment, and platform release settings."
  }
}

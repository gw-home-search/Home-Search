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

run "digest_pinned_private_rollback_capable_workloads" {
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
      "user-api", "user-insight-worker", "user-flyway", "source-data-migration", "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    ] : name => "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }

  assert {
    condition = alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[0-9a-f]{64}$", digest))
    ]) && length(var.image_digests) == 17
    error_message = "Every service and one-shot task image must be immutable and digest pinned."
  }

  assert {
    condition = length(setsubtract(toset(keys(aws_ecs_service.service)), toset([
      "property-api", "admin-api", "user-api", "public-gateway", "admin-gateway", "ai", "chat-bff",
    ]))) == 0 && length(keys(aws_ecs_service.service)) == 7
    error_message = "All request-serving workloads and only request-serving workloads must be ECS services when optional ML is disabled."
  }

  assert {
    condition = alltrue([
      for service in aws_ecs_service.service :
      service.deployment_circuit_breaker[0].enable && service.deployment_circuit_breaker[0].rollback &&
      !service.network_configuration[0].assign_public_ip
    ])
    error_message = "Every ECS service must be private and automatically roll back failed deployments."
  }

  assert {
    condition = alltrue([
      for name in ["ai", "chat-bff"] :
      local.service_specs[name].readonly_root && local.service_specs[name].stop_timeout == 120
    ])
    error_message = "AI and chat-bff must use read-only roots and retain enough shutdown time for admitted requests."
  }

  assert {
    condition = length(setsubtract(toset(keys(aws_ecs_task_definition.one_shot)), toset([
      "secret-bootstrap", "database-bootstrap", "property-flyway", "admin-migration",
      "user-flyway", "source-data-migration", "runtime-grants", "property-batch",
      "map-marker-projection", "property-event-relay", "property-event-maintenance", "admin-ops", "backup",
      "restore-verification",
    ]))) == 0 && length(keys(aws_ecs_task_definition.one_shot)) == 14
    error_message = "Bootstrap, migrations, batch, ops, and backup must remain one-shot task definitions."
  }

  assert {
    condition = one([
      for volume in aws_ecs_task_definition.service["ml"].volume :
      volume.efs_volume_configuration[0].transit_encryption if volume.name == "model"
    ]) == "ENABLED"
    error_message = "The ML model must mount encrypted EFS with transit encryption enabled."
  }

  assert {
    condition = alltrue([
      local.workload_task_role_names["secret-bootstrap"] != local.workload_task_role_names["property-api"],
      local.workload_task_role_names["database-bootstrap"] != local.workload_task_role_names["property-api"],
      local.one_shot_specs["secret-bootstrap"].command == ["secret-bootstrap"],
      local.one_shot_specs["database-bootstrap"].command == ["db-bootstrap"],
    ])
    error_message = "Secret and database bootstraps must use distinct roles and explicit idempotent modes."
  }

  assert {
    condition = alltrue(concat(
      [for task in aws_ecs_task_definition.service : task.skip_destroy],
      [for task in aws_ecs_task_definition.one_shot : task.skip_destroy],
    ))
    error_message = "Previous task revisions must remain registered for deployment rollback."
  }

  assert {
    condition = alltrue([
      one([
        for item in local.service_specs["property-api"].environment :
        item.value if item.name == "SPRING_PROFILES_ACTIVE"
      ]) == "staging",
      one([
        for item in local.one_shot_specs["property-batch"].environment :
        item.value if item.name == "SPRING_PROFILES_ACTIVE"
      ]) == "staging",
    ])
    error_message = "Staging property workloads must fail fast through the staging Spring profile."
  }

  assert {
    condition = alltrue([
      one([
        for item in local.service_specs["user-api"].environment :
        item.value if item.name == "USER_OAUTH_SUCCESS_REDIRECT"
      ]) == "${var.public_origin}/auth/success",
      one([
        for item in local.service_specs["user-api"].environment :
        item.value if item.name == "USER_OAUTH_FAILURE_REDIRECT"
      ]) == "${var.public_origin}/auth/failure",
      strcontains(
        file("${path.module}/../../../apps/web/src/app/App.tsx"),
        "path=\"/auth/success\"",
      ),
      strcontains(
        file("${path.module}/../../../apps/web/src/app/App.tsx"),
        "path=\"/auth/failure\"",
      ),
    ])
    error_message = "Staging OAuth redirects must match the frontend success and failure callback routes."
  }

  assert {
    condition = alltrue([
      length(local.workload_execution_role_names) == length(local.workload_names),
      length(distinct(values(local.workload_execution_role_names))) == length(local.workload_names),
      length(local.workload_task_role_names) == length(local.workload_names),
      length(distinct(values(local.workload_task_role_names))) == length(local.workload_names),
      toset(keys(local.workload_execution_secret_names)) == local.workload_names,
      local.runtime_grants_secret_names == toset([
        "property-migrator-db", "admin-migrator-db", "user-migrator-db",
      ]),
    ])
    error_message = "Every service and one-shot workload must own distinct execution and task roles."
  }

  assert {
    condition = alltrue([
      local.workload_execution_secret_names["property-api"] == ["property-runtime-db", "admin-internal-jwt-public", "kakao-local-provider"],
      local.workload_execution_secret_names["admin-api"] == ["admin-runtime-db", "admin-internal-jwt"],
      local.workload_execution_secret_names["user-api"] == ["user-runtime-db", "oauth-providers", "user-jwt"],
      local.workload_execution_secret_names["property-batch"] == ["property-runtime-db", "public-data-providers"],
      local.workload_execution_secret_names["map-marker-projection"] == ["property-runtime-db"],
      local.workload_execution_secret_names["property-event-relay"] == ["property-runtime-db"],
      local.workload_execution_secret_names["property-event-maintenance"] == ["property-runtime-db"],
      alltrue(flatten([
        for secret_names in values(local.workload_execution_secret_names) : [
          for secret_name in secret_names : !contains(["database-runtime", "database-bootstrap"], secret_name)
        ]
      ])),
      length(local.workload_execution_secret_names["public-gateway"]) == 0,
      length(local.workload_execution_secret_names["admin-gateway"]) == 0,
      length(local.workload_execution_secret_names["ml"]) == 0,
      alltrue(flatten([
        for secret_names in values(local.workload_execution_secret_names) : [
          for secret_name in secret_names : contains(local.secret_containers, secret_name)
        ]
      ])),
    ])
    error_message = "Execution roles may read only the workload's declared container secrets and never an RDS master secret."
  }

  assert {
    condition = alltrue([
      var.enable_coordinate_source_runtime == false,
      alltrue([
        for item in local.service_specs["property-api"].environment :
        !startswith(item.name, "COORDINATE_SOURCE_DB_")
      ]),
      alltrue([
        for item in local.service_specs["property-api"].secrets :
        !startswith(item.name, "COORDINATE_SOURCE_DB_")
      ]),
      alltrue([
        for name in ["property-batch", "map-marker-projection"] : alltrue([
          for item in local.one_shot_specs[name].environment :
          !startswith(item.name, "COORDINATE_SOURCE_DB_")
        ])
      ]),
      alltrue([
        for name in ["property-batch", "map-marker-projection"] : alltrue([
          for item in local.one_shot_specs[name].secrets :
          !startswith(item.name, "COORDINATE_SOURCE_DB_")
        ])
      ]),
    ])
    error_message = "Coordinate source runtime credentials and endpoints must remain disabled until operator migration is approved."
  }

  assert {
    condition = alltrue([
      one([
        for item in local.service_specs["property-api"].environment :
        item.value if item.name == "HOME_PLACE_KAKAO_ENABLED"
      ]) == "true",
      one([
        for secret in local.service_specs["property-api"].secrets :
        secret.name if secret.name == "KAKAO_REST_API_KEY"
      ]) == "KAKAO_REST_API_KEY",
      length(local.service_specs["property-api"].secrets) == 2,
      !contains(local.workload_execution_secret_names["property-api"], "public-data-providers"),
    ])
    error_message = "The staging property API must enable Kakao Local and materialize only its server-side REST API key."
  }

  assert {
    condition = alltrue([
      one([
        for secret in local.one_shot_specs["property-batch"].secrets :
        secret.name if secret.name == "APT_SERVICE_KEY"
      ]) == "APT_SERVICE_KEY",
      one([
        for secret in local.one_shot_specs["property-batch"].secrets :
        secret.name if secret.name == "NAVER_NEWS_API_KEY_ID"
      ]) == "NAVER_NEWS_API_KEY_ID",
      one([
        for secret in local.one_shot_specs["property-batch"].secrets :
        secret.name if secret.name == "NAVER_NEWS_API_KEY"
      ]) == "NAVER_NEWS_API_KEY",
    ])
    error_message = "The staging property batch must materialize approved provider credentials from its scoped secret."
  }

  assert {
    condition = (
      [for secret in local.one_shot_specs["property-event-relay"].secrets : secret.name]
      == ["DB_PASSWORD"]
      && !contains(
        [for item in local.one_shot_specs["property-batch"].environment : item.name],
        "HOME_KAFKA_BOOTSTRAP_SERVERS"
      )
    )
    error_message = "The event relay must not inherit coordinate or external-provider secrets from the general property batch."
  }

  assert {
    condition = alltrue([
      [for secret in local.one_shot_specs["property-event-maintenance"].secrets : secret.name] == ["DB_PASSWORD"],
      one([
        for item in local.one_shot_specs["property-event-maintenance"].environment :
        item.value if item.name == "HOME_EVENTS_RETENTION_ENABLED"
      ]) == "true",
      !contains(
        [for item in local.one_shot_specs["property-event-maintenance"].environment : item.name],
        "HOME_KAFKA_BOOTSTRAP_SERVERS"
      ),
    ])
    error_message = "Outbox retention must receive only the property DB secret and no Kafka endpoint."
  }

  assert {
    condition = alltrue([
      aws_ecs_service.user_insight_worker.desired_count == 1,
      !aws_ecs_service.user_insight_worker.network_configuration[0].assign_public_ip,
      aws_ecs_service.user_insight_worker.deployment_circuit_breaker[0].enable,
      aws_ecs_service.user_insight_worker.deployment_circuit_breaker[0].rollback,
      local.workload_execution_secret_names["user-insight-worker"] == ["user-runtime-db"],
      one([
        for item in local.service_specs["user-api"].environment :
        item.value if item.name == "HOME_INSIGHTS_ENABLED"
      ]) == "false",
      contains(
        [for item in local.user_insight_worker_spec.environment : item.name],
        "HOME_KAFKA_BOOTSTRAP_SERVERS",
      ),
      one([
        for item in local.user_insight_worker_spec.environment :
        item.value if item.name == "HOME_INSIGHT_RETENTION_ENABLED"
      ]) == "true",
      [for secret in local.user_insight_worker_spec.secrets : secret.name] == ["USER_DB_PASSWORD"],
      aws_ecs_task_definition.user_insight_worker.skip_destroy,
      contains(output.workload_release.service_names, "user-insight-worker"),
      contains(keys(output.workload_release.service_task_arns), "user-insight-worker"),
    ])
    error_message = "The user insight consumer must be a private rollback-capable service with only its DB secret and MSK endpoint."
  }
}

locals {
  namespace_name = "production.${var.project_name}.internal"
  awslogs = {
    for name, group in aws_cloudwatch_log_group.service : name => {
      logDriver = "awslogs"
      options = {
        awslogs-group         = group.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }
  coordinate_source_environment = var.enable_coordinate_source_runtime ? [
    { name = "COORDINATE_SOURCE_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["coordinate"].address}:5432/home_search_coordinate_source?sslmode=require" },
    { name = "COORDINATE_SOURCE_DB_USERNAME", value = "home_search_coordinate_reader" },
    { name = "COORDINATE_SOURCE_DB_READ_ONLY", value = "true" },
  ] : []
  coordinate_source_secrets = var.enable_coordinate_source_runtime ? [
    { name = "COORDINATE_SOURCE_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["coordinate-reader-db"].arn}:password::" },
  ] : []
  service_specs = {
    property-api = {
      image = "property-api", port = 8080, sg = "property", cpu = 1024, memory = 2048
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SERVER_PORT", value = "8080" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["property"].address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
        { name = "HOME_PREDICTION_ENABLED", value = "false" },
        { name = "HOME_PREDICTION_CLIENT_BASE_URL", value = "http://ml.${local.namespace_name}:8001" },
        { name = "HOME_ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "HOME_ADMIN_INTERNAL_ISSUER", value = "admin-service" },
        { name = "HOME_ADMIN_INTERNAL_AUDIENCE", value = "property-data-admin" },
        { name = "HOME_ADMIN_INTERNAL_PUBLIC_KEYS", value = "production-1=/run/keys/public.pem" },
        { name = "FRONTEND_URL", value = var.public_origin },
      ], local.coordinate_source_environment)
      secrets = concat([
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
        { name = "KAKAO_REST_API_KEY", valueFrom = "${aws_secretsmanager_secret.container["kakao-local-provider"].arn}:rest_api_key::" },
      ], local.coordinate_source_secrets)
      key_secrets  = [{ name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["admin-internal-jwt-public"].arn}:public_key_pem::" }]
      health       = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      metrics_path = "/actuator/prometheus"
    }
    admin-api = {
      image = "admin-api", port = 8081, sg = "admin", cpu = 512, memory = 1024
      environment = [
        { name = "SERVER_PORT", value = "8081" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["admin"].address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
        { name = "ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "ADMIN_INTERNAL_JWT_ISSUER", value = "admin-service" },
        { name = "ADMIN_INTERNAL_JWT_AUDIENCE", value = "property-data-admin" },
        { name = "ADMIN_INTERNAL_JWT_KEY_ID", value = "production-1" },
        { name = "ADMIN_INTERNAL_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "PROPERTY_DATA_INTERNAL_BASE_URL", value = "http://property-api.${local.namespace_name}:8080" },
      ]
      secrets      = [{ name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-runtime-db"].arn}:password::" }]
      key_secrets  = [{ name = "PRIVATE_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["admin-internal-jwt"].arn}:private_key_pem::" }]
      health       = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      metrics_path = "/actuator/prometheus"
    }
    user-api = {
      image = "user-api", port = 8082, sg = "user", cpu = 512, memory = 1024
      environment = [
        { name = "SERVER_PORT", value = "8082" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "USER_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["user"].address}:5432/home_search_user?sslmode=require" },
        { name = "USER_DB_USERNAME", value = "home_search_user_runtime" },
        { name = "USER_ALLOWED_ORIGIN", value = var.public_origin },
        { name = "USER_OAUTH_SUCCESS_REDIRECT", value = "${var.public_origin}/auth/success" },
        { name = "USER_OAUTH_FAILURE_REDIRECT", value = "${var.public_origin}/auth/failure" },
        { name = "USER_COOKIE_SECURE", value = "true" },
        { name = "USER_JWT_ACTIVE_KID", value = "production-1" },
        { name = "USER_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "USER_JWT_ACTIVE_PUBLIC_KEY_PATH", value = "/run/keys/public.pem" },
      ]
      secrets = [
        { name = "USER_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["user-runtime-db"].arn}:password::" },
        { name = "GOOGLE_OAUTH_CLIENT_ID", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:google_client_id::" },
        { name = "GOOGLE_OAUTH_CLIENT_SECRET", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:google_client_secret::" },
        { name = "KAKAO_OAUTH_CLIENT_ID", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:kakao_client_id::" },
        { name = "KAKAO_OAUTH_CLIENT_SECRET", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:kakao_client_secret::" },
        { name = "NAVER_OAUTH_CLIENT_ID", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:naver_client_id::" },
        { name = "NAVER_OAUTH_CLIENT_SECRET", valueFrom = "${aws_secretsmanager_secret.container["oauth-providers"].arn}:naver_client_secret::" },
      ]
      key_secrets = [
        { name = "PRIVATE_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:private_key_pem::" },
        { name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:public_key_pem::" },
      ]
      health       = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8082; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      metrics_path = "/actuator/prometheus"
    }
    public-gateway = {
      image = "public-gateway", port = 8080, sg = "public-gateway", cpu = 256, memory = 512
      environment = [
        { name = "PROPERTY_API_HOST", value = "property-api.${local.namespace_name}" },
        { name = "PROPERTY_API_PORT", value = "8080" },
        { name = "USER_API_HOST", value = "user-api.${local.namespace_name}" },
        { name = "USER_API_PORT", value = "8082" },
        { name = "CHAT_BFF_HOST", value = "chat-bff.${local.namespace_name}" },
        { name = "CHAT_BFF_PORT", value = "8083" },
      ]
      secrets      = [], key_secrets = []
      health       = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
      metrics_path = null
    }
    admin-gateway = {
      image = "admin-gateway", port = 8080, sg = "admin-gateway", cpu = 256, memory = 512
      environment = [
        { name = "ADMIN_API_HOST", value = "admin-api.${local.namespace_name}" },
        { name = "ADMIN_API_PORT", value = "8081" },
      ]
      secrets      = [], key_secrets = []
      health       = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
      metrics_path = null
    }
    ml = {
      image        = "ml", port = 8001, sg = "ml", cpu = 1024, memory = 2048
      environment  = [{ name = "F37_ARTIFACT_DIR", value = "/model" }]
      secrets      = [], key_secrets = []
      health       = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=3)\" || exit 1"]
      metrics_path = "/metrics"
    }
    ai = {
      image = "ai", port = 8000, sg = "ai", cpu = 1024, memory = 2048
      environment = [
        { name = "HOME_AI_JWT_PUBLIC_KEY_PATHS", value = "{\"production-1\":\"/run/keys/public.pem\"}" },
        { name = "HOME_AI_OPENAI_TIMEOUT_SECONDS", value = "8" },
        { name = "HOME_AI_QUERY_TIMEOUT_SECONDS", value = "55" },
        { name = "HOME_AI_PROPERTY_SEARCH_FALLBACK_ENABLED", value = "true" },
        { name = "HOME_AI_PROPERTY_SEARCH_BASE_URL", value = "http://property-api.${local.namespace_name}:8080" },
        { name = "HOME_AI_DEPLOYMENT_TIER", value = "production" },
        { name = "HOME_AI_SUPERVISOR_GRAPH_MODE", value = "active" },
        { name = "HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT", value = "100" },
        { name = "HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", value = "true" },
        { name = "HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", value = "false" },
        { name = "HOME_AI_ENABLED_PROPERTY_CAPABILITIES", value = "complex_identity,recent_trade_lookup,price_trend,recommendation,comparison" },
        { name = "HOME_AI_ENABLED_REFERENCE_CAPABILITIES", value = "academy_lookup,rail_station_lookup,school_location,retail_location" },
      ]
      secrets = [
        { name = "HOME_AI_PROPERTY_DSN", valueFrom = "${aws_secretsmanager_secret.container["ai-runtime"].arn}:property_dsn::" },
        { name = "HOME_AI_REFERENCE_DSN", valueFrom = "${aws_secretsmanager_secret.container["ai-runtime"].arn}:reference_dsn::" },
        { name = "HOME_AI_OPENAI_API_KEY", valueFrom = "${aws_secretsmanager_secret.container["openai-provider"].arn}:api_key::" },
        { name = "HOME_AI_OPENAI_PRIMARY_MODEL", valueFrom = "${aws_secretsmanager_secret.container["openai-provider"].arn}:primary_model::" },
        { name = "HOME_AI_OPENAI_SECONDARY_MODEL", valueFrom = "${aws_secretsmanager_secret.container["openai-provider"].arn}:secondary_model::" },
      ]
      key_secrets  = [{ name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:public_key_pem::" }]
      health       = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)\" || exit 1"]
      metrics_path = "/metrics"
    }
    chat-bff = {
      image = "chat-bff", port = 8083, sg = "chat-bff", cpu = 512, memory = 1024
      environment = [
        { name = "SERVER_PORT", value = "8083" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "HOME_CHAT_BFF_AI_BASE_URL", value = "http://ai.${local.namespace_name}:8000" },
        { name = "HOME_CHAT_BFF_AI_TIMEOUT", value = "70s" },
        { name = "HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS", value = "production-1=/run/keys/public.pem" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
      ]
      secrets      = []
      key_secrets  = [{ name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:public_key_pem::" }]
      health       = ["CMD-SHELL", "curl --fail --silent --max-time 3 http://127.0.0.1:8083/actuator/health/readiness >/dev/null || exit 1"]
      metrics_path = "/actuator/prometheus"
    }
    user-insight-worker = {
      image = "user-insight-worker", port = 0, sg = "user-insight-worker", cpu = 512, memory = 1024
      environment = [
        { name = "USER_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["user"].address}:5432/home_search_user?sslmode=require" },
        { name = "USER_DB_USERNAME", value = "home_search_user_runtime" },
        { name = "HOME_KAFKA_BOOTSTRAP_SERVERS", value = aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam },
        { name = "HOME_INSIGHT_RETENTION_ENABLED", value = "true" },
      ]
      secrets      = [{ name = "USER_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["user-runtime-db"].arn}:password::" }]
      key_secrets  = [], health = null
      metrics_path = null
    }
  }
  metric_service_specs = {
    for name, spec in local.service_specs : name => spec
    if spec.metrics_path != null
  }
  service_desired_counts = {
    for name in keys(local.service_specs) : name => (
      var.service_activation_phase == "off" ? 0 :
      var.service_activation_phase == "consumers" ? (name == "user-insight-worker" ? var.core_desired_count : 0) :
      var.service_activation_phase == "private" ? (name == "public-gateway" ? 0 : var.core_desired_count) :
      var.core_desired_count
    )
  }
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = local.workload_names
  name              = "/${var.project_name}/production/${each.key}"
  retention_in_days = 365
  kms_key_id        = aws_kms_key.data.arn
}

resource "aws_ecs_cluster" "this" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = local.namespace_name
  description = "Private production service boundary"
  vpc         = aws_vpc.this.id
}

resource "aws_service_discovery_service" "service" {
  for_each = { for name, spec in local.service_specs : name => spec if spec.port > 0 }
  name     = each.key
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
  health_check_custom_config {}
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.service_specs
  family                   = each.key
  cpu                      = tostring(each.value.cpu)
  memory                   = tostring(each.value.memory)
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  execution_role_arn       = aws_iam_role.workload_execution[each.key].arn
  task_role_arn            = aws_iam_role.workload_task[each.key].arn
  skip_destroy             = true
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  dynamic "volume" {
    for_each = length(each.value.key_secrets) > 0 ? [1] : []
    content { name = "keys" }
  }
  dynamic "volume" {
    for_each = each.key == "ml" ? [1] : []
    content {
      name = "model"
      efs_volume_configuration {
        file_system_id     = aws_efs_file_system.ml_model.id
        root_directory     = "/"
        transit_encryption = "ENABLED"
      }
    }
  }

  container_definitions = jsonencode(concat(
    [merge({
      name         = each.key
      image        = var.image_uris[each.value.image]
      essential    = true
      portMappings = each.value.port > 0 ? [{ containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp" }] : []
      environment  = each.value.environment
      secrets      = each.value.secrets
      mountPoints = concat(
        length(each.value.key_secrets) > 0 ? [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }] : [],
        each.key == "ml" ? [{ sourceVolume = "model", containerPath = "/model", readOnly = true }] : [],
      )
      dependsOn              = length(each.value.key_secrets) > 0 ? [{ containerName = "key-materializer", condition = "SUCCESS" }] : []
      readonlyRootFilesystem = contains(["ai", "chat-bff"], each.key)
      stopTimeout            = 120
      user                   = "10001:10001"
      linuxParameters        = { initProcessEnabled = true }
      logConfiguration       = local.awslogs[each.key]
    }, each.value.health != null ? { healthCheck = { command = each.value.health, interval = 30, timeout = 5, retries = 3, startPeriod = 60 } } : {})],
    length(each.value.key_secrets) > 0 ? [{
      name                   = "key-materializer"
      image                  = var.image_uris["ops-bootstrap"]
      essential              = false
      command                = ["materialize-keys"]
      environment            = [{ name = "KEY_OUTPUT_DIRECTORY", value = "/run/keys" }]
      secrets                = each.value.key_secrets
      mountPoints            = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = false }]
      user                   = "10001:10001"
      readonlyRootFilesystem = false
      logConfiguration       = local.awslogs[each.key]
    }] : [],
    each.value.metrics_path != null ? [{
      name                   = "adot"
      image                  = var.adot_collector_image_uri
      essential              = true
      command                = ["--config=env:AOT_CONFIG_CONTENT"]
      cpu                    = 128
      memoryReservation      = 256
      readonlyRootFilesystem = true
      user                   = "10001:10001"
      linuxParameters        = { initProcessEnabled = true }
      environment = [{
        name = "AOT_CONFIG_CONTENT"
        value = yamlencode({
          extensions = {
            sigv4auth = { region = var.aws_region }
          }
          receivers = {
            prometheus = {
              config = {
                scrape_configs = [{
                  job_name        = each.key
                  scrape_interval = "30s"
                  scrape_timeout  = "10s"
                  metrics_path    = each.value.metrics_path
                  static_configs  = [{ targets = ["127.0.0.1:${each.value.port}"] }]
                }]
              }
            }
          }
          processors = {
            batch = { timeout = "10s" }
            memory_limiter = {
              check_interval  = "5s"
              limit_mib       = 192
              spike_limit_mib = 64
            }
          }
          exporters = {
            prometheusremotewrite = {
              endpoint = "${aws_prometheus_workspace.this.prometheus_endpoint}api/v1/remote_write"
              auth     = { authenticator = "sigv4auth" }
              external_labels = {
                service        = each.key
                environment    = "production"
                release_digest = element(split("@", var.image_uris[each.value.image]), 1)
              }
            }
          }
          service = {
            extensions = ["sigv4auth"]
            pipelines = {
              metrics = {
                receivers  = ["prometheus"]
                processors = ["memory_limiter", "batch"]
                exporters  = ["prometheusremotewrite"]
              }
            }
          }
        })
      }]
      logConfiguration = local.awslogs[each.key]
    }] : [],
  ))

  lifecycle {
    precondition {
      condition = (
        length(setsubtract(local.image_names, toset(keys(var.image_uris)))) == 0
        && length(setsubtract(toset(keys(var.image_uris)), local.image_names)) == 0
      )
      error_message = "image_uris keys must exactly match the 18-image release manifest."
    }
  }
}

resource "aws_ecs_service" "service" {
  for_each                           = local.service_specs
  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = local.service_desired_counts[each.key]
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  availability_zone_rebalancing      = "ENABLED"
  wait_for_steady_state              = true
  health_check_grace_period_seconds  = contains(["public-gateway", "admin-gateway"], each.key) ? 60 : null

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  deployment_controller { type = "ECS" }
  network_configuration {
    subnets          = values(aws_subnet.application)[*].id
    security_groups  = [aws_security_group.task[each.value.sg].id]
    assign_public_ip = false
  }
  dynamic "service_registries" {
    for_each = each.value.port > 0 ? [1] : []
    content { registry_arn = aws_service_discovery_service.service[each.key].arn }
  }
  dynamic "load_balancer" {
    for_each = contains(["public-gateway", "admin-gateway"], each.key) ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.gateway[each.key].arn
      container_name   = each.key
      container_port   = 8080
    }
  }
  depends_on = [aws_lb_listener.public_https, aws_lb_listener.admin_https]
}

locals {
  generated_secret_names = toset([
    "property-runtime-db", "property-ai-reader-db", "admin-runtime-db", "user-runtime-db",
    "coordinate-reader-db", "property-migrator-db", "admin-migrator-db", "user-migrator-db",
    "coordinate-migrator-db", "coordinate-importer-db", "ai-migrator-db", "ai-importer-db",
    "ai-runtime-db", "ai-runtime", "backup-db", "user-jwt", "admin-internal-jwt",
    "admin-internal-jwt-public",
  ])
  generated_secret_environment = [
    for name in sort(tolist(local.generated_secret_names)) : {
      name  = upper(replace(name, "-", "_")) == "ADMIN_INTERNAL_JWT" ? "ADMIN_JWT_SECRET_ARN" : upper(replace(name, "-", "_")) == "ADMIN_INTERNAL_JWT_PUBLIC" ? "ADMIN_JWT_PUBLIC_SECRET_ARN" : upper(replace(name, "-", "_")) == "AI_RUNTIME" ? "AI_RUNTIME_SECRET_ARN" : "${upper(replace(name, "-", "_"))}_SECRET_ARN"
      value = aws_secretsmanager_secret.container[name].arn
    }
  ]
  readiness_secret_environment = concat(local.generated_secret_environment, [
    { name = "OPENAI_PROVIDER_SECRET_ARN", value = aws_secretsmanager_secret.container["openai-provider"].arn },
    { name = "OAUTH_PROVIDERS_SECRET_ARN", value = aws_secretsmanager_secret.container["oauth-providers"].arn },
    { name = "KAKAO_LOCAL_PROVIDER_SECRET_ARN", value = aws_secretsmanager_secret.container["kakao-local-provider"].arn },
    { name = "PUBLIC_DATA_PROVIDERS_SECRET_ARN", value = aws_secretsmanager_secret.container["public-data-providers"].arn },
  ])
  one_shot_specs = {
    secret-bootstrap = {
      image = "ops-bootstrap", command = ["production-secret-bootstrap"]
      environment = concat(local.generated_secret_environment, [
        { name = "PROPERTY_DB_HOST", value = aws_db_instance.service["property"].address },
        { name = "AI_DB_HOST", value = aws_db_instance.service["ai"].address },
      ])
      secrets = []
    }
    secret-readiness = {
      image       = "ops-bootstrap", command = ["production-secret-readiness"]
      environment = local.readiness_secret_environment
      secrets     = []
    }
    database-bootstrap = {
      image = "ops-bootstrap", command = ["production-db-bootstrap"]
      environment = [
        { name = "PROPERTY_RDS_SECRET_ARN", value = aws_db_instance.service["property"].master_user_secret[0].secret_arn },
        { name = "ADMIN_RDS_SECRET_ARN", value = aws_db_instance.service["admin"].master_user_secret[0].secret_arn },
        { name = "USER_RDS_SECRET_ARN", value = aws_db_instance.service["user"].master_user_secret[0].secret_arn },
        { name = "AI_RDS_SECRET_ARN", value = aws_db_instance.service["ai"].master_user_secret[0].secret_arn },
        { name = "COORDINATE_RDS_SECRET_ARN", value = aws_db_instance.service["coordinate"].master_user_secret[0].secret_arn },
        { name = "PROPERTY_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-runtime-db"].arn },
        { name = "PROPERTY_AI_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-ai-reader-db"].arn },
        { name = "ADMIN_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-runtime-db"].arn },
        { name = "USER_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-runtime-db"].arn },
        { name = "COORDINATE_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-reader-db"].arn },
        { name = "PROPERTY_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-migrator-db"].arn },
        { name = "ADMIN_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-migrator-db"].arn },
        { name = "USER_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-migrator-db"].arn },
        { name = "AI_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["ai-migrator-db"].arn },
        { name = "AI_IMPORTER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["ai-importer-db"].arn },
        { name = "AI_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["ai-runtime-db"].arn },
        { name = "COORDINATE_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-migrator-db"].arn },
        { name = "COORDINATE_IMPORTER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-importer-db"].arn },
        { name = "BACKUP_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["backup-db"].arn },
      ]
      secrets = []
    }
    property-flyway = {
      image = "property-flyway", command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.service["property"].address}:5432/home_search?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_property_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-migrator-db"].arn}:password::" }]
    }
    admin-migration = {
      image = "admin-migration", command = []
      environment = [
        { name = "ADMIN_MIGRATION_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["admin"].address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_MIGRATION_DB_USERNAME", value = "home_search_admin_migrator" },
      ]
      secrets = [{ name = "ADMIN_MIGRATION_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-migrator-db"].arn}:password::" }]
    }
    user-flyway = {
      image = "user-flyway", command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.service["user"].address}:5432/home_search_user?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_user_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["user-migrator-db"].arn}:password::" }]
    }
    ai-migration = {
      image       = "ai", command = ["home-ai-migrate"]
      environment = []
      secrets     = [{ name = "HOME_AI_MIGRATOR_DSN", valueFrom = "${aws_secretsmanager_secret.container["ai-migrator-db"].arn}:dsn::" }]
    }
    data-import-reconcile = {
      image      = "backup"
      entrypoint = ["/usr/local/bin/run-s3-data-migration"]
      command    = []
      environment = [
        { name = "HOME_MIGRATION_ARTIFACT_S3_URI", value = "s3://${var.migration_artifact_bucket}/${var.migration_artifact_prefix}" },
        { name = "HOME_MIGRATION_MANIFEST_SHA256", value = var.migration_manifest_sha256 },
        { name = "HOME_MIGRATION_EVIDENCE_S3_URI", value = "s3://${aws_s3_bucket.audit.id}/deployment-evidence/${var.deployment_release_tag}" },
        { name = "HOME_MIGRATION_EVIDENCE_KMS_KEY_ID", value = aws_kms_key.audit.arn },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_HOST", value = aws_db_instance.service["property"].address },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_PORT", value = "5432" },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_DATABASE", value = "home_search" },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_USER", value = "home_search_property_migrator" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_HOST", value = aws_db_instance.service["ai"].address },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_PORT", value = "5432" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_DATABASE", value = "home_search_ai" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_USER", value = "home_search_ai_importer" },
        { name = "HOME_MIGRATION_RAW_TARGET_BUCKET", value = aws_s3_bucket.reference_raw.id },
        { name = "HOME_MIGRATION_RAW_TARGET_REGION", value = var.aws_region },
        { name = "HOME_MIGRATION_RAW_TARGET_KMS_KEY_ID", value = aws_kms_key.data.arn },
      ]
      secrets = [
        { name = "HOME_MIGRATION_PROPERTY_TARGET_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-migrator-db"].arn}:password::" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["ai-importer-db"].arn}:password::" },
      ]
    }
    source-data-migration = {
      image = "source-data-migration", command = []
      environment = [
        { name = "SOURCE_DATA_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["coordinate"].address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "SOURCE_DATA_DB_USERNAME", value = "home_search_coordinate_migrator" },
      ]
      secrets = [{ name = "SOURCE_DATA_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["coordinate-migrator-db"].arn}:password::" }]
    }
    runtime-grants = {
      image = "ops-bootstrap", command = ["runtime-grants"]
      environment = [
        { name = "PROPERTY_DB_HOST", value = aws_db_instance.service["property"].address },
        { name = "PROPERTY_DB_PORT", value = "5432" },
        { name = "ADMIN_DB_HOST", value = aws_db_instance.service["admin"].address },
        { name = "ADMIN_DB_PORT", value = "5432" },
        { name = "USER_DB_HOST", value = aws_db_instance.service["user"].address },
        { name = "USER_DB_PORT", value = "5432" },
        { name = "PROPERTY_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-migrator-db"].arn },
        { name = "ADMIN_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-migrator-db"].arn },
        { name = "USER_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-migrator-db"].arn },
      ]
      secrets = []
    }
    property-batch = {
      image = "property-batch", command = []
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["property"].address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
      ], local.coordinate_source_environment)
      secrets = concat([
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
        { name = "APT_SERVICE_KEY", valueFrom = "${aws_secretsmanager_secret.container["public-data-providers"].arn}:apt_service_key::" },
      ], local.coordinate_source_secrets)
    }
    map-marker-projection = {
      image = "property-batch", command = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_BATCH_JOB_NAME", value = "mapMarkerProjectionJob" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["property"].address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
      ]
      secrets = [{ name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" }]
    }
    admin-ops = {
      image = "admin-ops", command = []
      environment = [
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.service["admin"].address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
      ]
      secrets = [{ name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-runtime-db"].arn}:password::" }]
    }
    backup = {
      image       = "backup", command = ["--backup-all", "/backup"]
      environment = [{ name = "HOME_BACKUP_LOGICAL_DATABASES", value = "property,admin,user,ai,coordinate" }]
      secrets     = [{ name = "HOME_BACKUP_PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.container["backup-db"].arn}:password::" }]
    }
  }
}

resource "aws_ecs_task_definition" "one_shot" {
  for_each                 = local.one_shot_specs
  family                   = each.key
  cpu                      = "512"
  memory                   = "1024"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  execution_role_arn       = aws_iam_role.workload_execution[each.key].arn
  task_role_arn            = aws_iam_role.workload_task[each.key].arn
  skip_destroy             = true
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  ephemeral_storage { size_in_gib = contains(["backup", "data-import-reconcile"], each.key) ? 100 : 21 }
  container_definitions = jsonencode([merge({
    name                   = each.key
    image                  = var.image_uris[each.value.image]
    essential              = true
    environment            = each.value.environment
    secrets                = each.value.secrets
    user                   = "10001:10001"
    readonlyRootFilesystem = false
    stopTimeout            = 120
    linuxParameters        = { initProcessEnabled = true }
    logConfiguration       = local.awslogs[each.key]
    }, length(each.value.command) > 0 ? { command = each.value.command } : {},
  length(try(each.value.entrypoint, [])) > 0 ? { entryPoint = each.value.entrypoint } : {})])
}

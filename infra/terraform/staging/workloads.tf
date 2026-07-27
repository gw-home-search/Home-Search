locals {
  image_references = {
    for name, repository in aws_ecr_repository.image :
    name => "${repository.repository_url}@${var.image_digests[name]}"
  }
  namespace_name = "staging.${var.project_name}.internal"
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
  service_specs = {
    property-api = {
      image  = "property-api"
      port   = 8080
      sg     = "property"
      cpu    = 512
      memory = 1024
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "staging" },
        { name = "SERVER_PORT", value = "8080" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "COORDINATE_SOURCE_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "COORDINATE_SOURCE_DB_USERNAME", value = "home_search_coordinate_reader" },
        { name = "COORDINATE_SOURCE_DB_READ_ONLY", value = "true" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
        { name = "HOME_PLACE_KAKAO_ENABLED", value = "true" },
        { name = "HOME_NEWS_PUBLIC_ENABLED", value = tostring(var.enable_market_news_public) },
        { name = "HOME_PREDICTION_ENABLED", value = tostring(var.enable_ml) },
        { name = "HOME_PREDICTION_CLIENT_BASE_URL", value = "http://ml.${local.namespace_name}:8001" },
        { name = "HOME_ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "HOME_ADMIN_INTERNAL_ISSUER", value = "admin-service" },
        { name = "HOME_ADMIN_INTERNAL_AUDIENCE", value = "property-data-admin" },
        { name = "HOME_ADMIN_INTERNAL_MAXIMUM_LIFETIME", value = "60s" },
        { name = "HOME_ADMIN_INTERNAL_PUBLIC_KEYS", value = "staging-1=/run/keys/public.pem" },
        { name = "FRONTEND_URL", value = var.public_origin },
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
        { name = "COORDINATE_SOURCE_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["coordinate-reader-db"].arn}:password::" },
        { name = "KAKAO_REST_API_KEY", valueFrom = "${aws_secretsmanager_secret.container["kakao-local-provider"].arn}:rest_api_key::" },
      ]
      key_secrets = [
        { name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["admin-internal-jwt-public"].arn}:public_key_pem::" },
      ]
      mount_points = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }]
      health       = ["CMD-SHELL", "timeout 3 bash -c '</dev/tcp/127.0.0.1/8080' || exit 1"]
    }
    admin-api = {
      image  = "admin-api"
      port   = 8081
      sg     = "admin"
      cpu    = 512
      memory = 1024
      environment = [
        { name = "SERVER_PORT", value = "8081" },
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
        { name = "ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "ADMIN_INTERNAL_JWT_ISSUER", value = "admin-service" },
        { name = "ADMIN_INTERNAL_JWT_AUDIENCE", value = "property-data-admin" },
        { name = "ADMIN_INTERNAL_JWT_LIFETIME", value = "60s" },
        { name = "ADMIN_INTERNAL_JWT_KEY_ID", value = "staging-1" },
        { name = "ADMIN_INTERNAL_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "PROPERTY_DATA_INTERNAL_BASE_URL", value = "http://property-api.${local.namespace_name}:8080" },
      ]
      secrets = [
        { name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-runtime-db"].arn}:password::" },
      ]
      key_secrets = [
        { name = "PRIVATE_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["admin-internal-jwt"].arn}:private_key_pem::" },
      ]
      mount_points = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }]
      health       = ["CMD-SHELL", "timeout 3 bash -c '</dev/tcp/127.0.0.1/8081' || exit 1"]
    }
    user-api = {
      image  = "user-api"
      port   = 8082
      sg     = "user"
      cpu    = 512
      memory = 1024
      environment = [
        { name = "SERVER_PORT", value = "8082" },
        { name = "USER_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_user?sslmode=require" },
        { name = "USER_DB_USERNAME", value = "home_search_user_runtime" },
        { name = "USER_ALLOWED_ORIGIN", value = var.public_origin },
        { name = "USER_OAUTH_SUCCESS_REDIRECT", value = "${var.public_origin}/auth/success" },
        { name = "USER_OAUTH_FAILURE_REDIRECT", value = "${var.public_origin}/auth/failure" },
        { name = "USER_COOKIE_SECURE", value = "true" },
        { name = "USER_JWT_ACTIVE_KID", value = "staging-1" },
        { name = "USER_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "USER_JWT_ACTIVE_PUBLIC_KEY_PATH", value = "/run/keys/public.pem" },
        { name = "HOME_INSIGHTS_ENABLED", value = tostring(var.enable_user_insights_public) },
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
      mount_points = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }]
      health       = ["CMD-SHELL", "timeout 3 bash -c '</dev/tcp/127.0.0.1/8082' || exit 1"]
    }
    public-gateway = {
      image  = "public-gateway"
      port   = 8080
      sg     = "public-gateway"
      cpu    = 256
      memory = 512
      environment = [
        { name = "PROPERTY_API_HOST", value = "property-api.${local.namespace_name}" },
        { name = "PROPERTY_API_PORT", value = "8080" },
        { name = "USER_API_HOST", value = "user-api.${local.namespace_name}" },
        { name = "USER_API_PORT", value = "8082" },
        { name = "CHAT_BFF_HOST", value = "chat-bff.${local.namespace_name}" },
        { name = "CHAT_BFF_PORT", value = "8083" },
      ]
      secrets      = []
      key_secrets  = []
      mount_points = []
      health       = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
    }
    admin-gateway = {
      image  = "admin-gateway"
      port   = 8080
      sg     = "admin-gateway"
      cpu    = 256
      memory = 512
      environment = [
        { name = "ADMIN_API_HOST", value = "admin-api.${local.namespace_name}" },
        { name = "ADMIN_API_PORT", value = "8081" },
      ]
      secrets      = []
      key_secrets  = []
      mount_points = []
      health       = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
    }
    ml = {
      image        = "ml"
      port         = 8001
      sg           = "ml"
      cpu          = 1024
      memory       = 2048
      environment  = [{ name = "F37_ARTIFACT_DIR", value = "/model" }]
      secrets      = []
      key_secrets  = []
      mount_points = [{ sourceVolume = "model", containerPath = "/model", readOnly = true }]
      health       = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=3)\" || exit 1"]
    }
    ai = {
      image         = "ai"
      port          = 8000
      sg            = "ai"
      cpu           = 1024
      memory        = 2048
      readonly_root = true
      stop_timeout  = 120
      environment = [
        { name = "HOME_AI_JWT_PUBLIC_KEY_PATHS", value = "{\"staging-1\":\"/run/keys/public.pem\"}" },
        { name = "HOME_AI_OPENAI_TIMEOUT_SECONDS", value = "30" },
        { name = "HOME_AI_QUERY_TIMEOUT_SECONDS", value = "60" },
        { name = "HOME_AI_DEPLOYMENT_TIER", value = "staging" },
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
      key_secrets = [
        { name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:public_key_pem::" },
      ]
      mount_points = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }]
      health       = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)\" || exit 1"]
    }
    chat-bff = {
      image         = "chat-bff"
      port          = 8083
      sg            = "chat-bff"
      cpu           = 512
      memory        = 1024
      readonly_root = true
      stop_timeout  = 120
      environment = [
        { name = "SERVER_PORT", value = "8083" },
        { name = "HOME_CHAT_BFF_AI_BASE_URL", value = "http://ai.${local.namespace_name}:8000" },
        { name = "HOME_CHAT_BFF_AI_TIMEOUT", value = "60s" },
        { name = "HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS", value = "staging-1=/run/keys/public.pem" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
      ]
      secrets = []
      key_secrets = [
        { name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["user-jwt"].arn}:public_key_pem::" },
      ]
      mount_points = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }]
      health       = ["CMD-SHELL", "curl --fail --silent --max-time 3 http://127.0.0.1:8083/actuator/health/readiness >/dev/null || exit 1"]
    }
  }
  user_insight_worker_spec = {
    image = "user-insight-worker"
    environment = [
      { name = "USER_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_user?sslmode=require" },
      { name = "USER_DB_USERNAME", value = "home_search_user_runtime" },
      { name = "HOME_KAFKA_BOOTSTRAP_SERVERS", value = aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam },
      { name = "HOME_INSIGHT_RETENTION_ENABLED", value = "true" },
    ]
    secrets = [
      { name = "USER_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["user-runtime-db"].arn}:password::" },
    ]
  }
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
  description = "Private service ownership boundary for staging"
  vpc         = aws_vpc.this.id
}

resource "aws_service_discovery_service" "service" {
  for_each = local.service_specs
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

resource "aws_lb_target_group" "gateway" {
  for_each             = toset(["public-gateway", "admin-gateway"])
  name                 = "${local.name}-${each.key == "public-gateway" ? "public" : "admin"}-gw"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.this.id
  deregistration_delay = 30
  health_check {
    enabled             = true
    path                = "/"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
    matcher             = "200-399"
  }
}

resource "aws_lb_listener" "public_http" {
  load_balancer_arn = aws_lb.public.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "public_https" {
  load_balancer_arn = aws_lb.public.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.public_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway["public-gateway"].arn
  }
}

resource "aws_lb_listener" "admin_https" {
  load_balancer_arn = aws_lb.admin.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.admin_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway["admin-gateway"].arn
  }
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.service_specs
  family                   = "${local.name}-${each.key}"
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
    [{
      name         = each.key
      image        = local.image_references[each.value.image]
      essential    = true
      portMappings = [{ containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp" }]
      environment  = each.value.environment
      secrets      = each.value.secrets
      mountPoints  = each.value.mount_points
      dependsOn = length(each.value.key_secrets) > 0 ? [
        { containerName = "key-materializer", condition = "SUCCESS" }
      ] : []
      healthCheck = {
        command     = each.value.health
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
      readonlyRootFilesystem = try(each.value.readonly_root, false)
      stopTimeout            = try(each.value.stop_timeout, 120)
      user                   = "10001:10001"
      logConfiguration       = local.awslogs[each.key]
    }],
    length(each.value.key_secrets) > 0 ? [{
      name                   = "key-materializer"
      image                  = local.image_references["ops-bootstrap"]
      essential              = false
      command                = ["materialize-keys"]
      environment            = [{ name = "KEY_OUTPUT_DIRECTORY", value = "/run/keys" }]
      secrets                = each.value.key_secrets
      mountPoints            = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = false }]
      user                   = "10001:10001"
      readonlyRootFilesystem = false
      logConfiguration       = local.awslogs[each.key]
    }] : [],
  ))
}

resource "aws_ecs_service" "service" {
  for_each = {
    for name, spec in local.service_specs : name => spec
    if var.enable_services && (name != "ml" || var.enable_ml)
  }
  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  health_check_grace_period_seconds  = contains(["public-gateway", "admin-gateway"], each.key) ? 30 : null
  wait_for_steady_state              = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = values(aws_subnet.application)[*].id
    security_groups  = [aws_security_group.task[each.value.sg].id]
    assign_public_ip = false
  }
  service_registries { registry_arn = aws_service_discovery_service.service[each.key].arn }

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

resource "aws_ecs_task_definition" "user_insight_worker" {
  family                   = "${local.name}-user-insight-worker"
  cpu                      = "512"
  memory                   = "1024"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  execution_role_arn       = aws_iam_role.workload_execution["user-insight-worker"].arn
  task_role_arn            = aws_iam_role.workload_task["user-insight-worker"].arn
  skip_destroy             = true
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name                   = "user-insight-worker"
    image                  = local.image_references[local.user_insight_worker_spec.image]
    essential              = true
    environment            = local.user_insight_worker_spec.environment
    secrets                = local.user_insight_worker_spec.secrets
    user                   = "10001:10001"
    readonlyRootFilesystem = false
    stopTimeout            = 120
    logConfiguration       = local.awslogs["user-insight-worker"]
  }])
}

resource "aws_ecs_service" "user_insight_worker" {
  name                               = "user-insight-worker"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.user_insight_worker.arn
  desired_count                      = var.enable_services ? 1 : 0
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  wait_for_steady_state              = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = values(aws_subnet.application)[*].id
    security_groups  = [aws_security_group.task["user-insight-worker"].id]
    assign_public_ip = false
  }
}

locals {
  one_shot_specs = {
    secret-bootstrap = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.workload_task["secret-bootstrap"].arn
      command = ["secret-bootstrap"]
      environment = [
        { name = "PROPERTY_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-runtime-db"].arn },
        { name = "PROPERTY_AI_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-ai-reader-db"].arn },
        { name = "ADMIN_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-runtime-db"].arn },
        { name = "USER_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-runtime-db"].arn },
        { name = "COORDINATE_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-reader-db"].arn },
        { name = "PROPERTY_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-migrator-db"].arn },
        { name = "ADMIN_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-migrator-db"].arn },
        { name = "USER_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-migrator-db"].arn },
        { name = "COORDINATE_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-migrator-db"].arn },
        { name = "COORDINATE_IMPORTER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-importer-db"].arn },
        { name = "BACKUP_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["backup-db"].arn },
        { name = "USER_JWT_SECRET_ARN", value = aws_secretsmanager_secret.container["user-jwt"].arn },
        { name = "ADMIN_JWT_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-internal-jwt"].arn },
        { name = "ADMIN_JWT_PUBLIC_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-internal-jwt-public"].arn },
      ]
      secrets = []
    }
    database-bootstrap = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.workload_task["database-bootstrap"].arn
      command = ["db-bootstrap"]
      environment = [
        { name = "PRIMARY_RDS_SECRET_ARN", value = aws_db_instance.primary.master_user_secret[0].secret_arn },
        { name = "COORDINATE_RDS_SECRET_ARN", value = aws_db_instance.coordinate_source.master_user_secret[0].secret_arn },
        { name = "PROPERTY_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-runtime-db"].arn },
        { name = "PROPERTY_AI_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-ai-reader-db"].arn },
        { name = "ADMIN_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-runtime-db"].arn },
        { name = "USER_RUNTIME_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-runtime-db"].arn },
        { name = "COORDINATE_READER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-reader-db"].arn },
        { name = "PROPERTY_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-migrator-db"].arn },
        { name = "ADMIN_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-migrator-db"].arn },
        { name = "USER_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-migrator-db"].arn },
        { name = "COORDINATE_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-migrator-db"].arn },
        { name = "COORDINATE_IMPORTER_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["coordinate-importer-db"].arn },
        { name = "BACKUP_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["backup-db"].arn },
      ]
      secrets = []
    }
    runtime-grants = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.workload_task["runtime-grants"].arn
      command = ["runtime-grants"]
      environment = [
        { name = "PRIMARY_DB_HOST", value = aws_db_instance.primary.address },
        { name = "PRIMARY_DB_PORT", value = tostring(aws_db_instance.primary.port) },
        { name = "PROPERTY_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["property-migrator-db"].arn },
        { name = "ADMIN_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-migrator-db"].arn },
        { name = "USER_MIGRATOR_DB_SECRET_ARN", value = aws_secretsmanager_secret.container["user-migrator-db"].arn },
      ]
      secrets = []
    }
    property-flyway = {
      image   = "property-flyway"
      role    = aws_iam_role.workload_task["property-flyway"].arn
      command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_property_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-migrator-db"].arn}:password::" }]
    }
    admin-migration = {
      image   = "admin-migration"
      role    = aws_iam_role.workload_task["admin-migration"].arn
      command = []
      environment = [
        { name = "ADMIN_MIGRATION_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_MIGRATION_DB_USERNAME", value = "home_search_admin_migrator" },
      ]
      secrets = [{ name = "ADMIN_MIGRATION_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-migrator-db"].arn}:password::" }]
    }
    user-flyway = {
      image   = "user-flyway"
      role    = aws_iam_role.workload_task["user-flyway"].arn
      command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_user?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_user_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["user-migrator-db"].arn}:password::" }]
    }
    source-data-migration = {
      image   = "source-data-migration"
      role    = aws_iam_role.workload_task["source-data-migration"].arn
      command = []
      environment = [
        { name = "SOURCE_DATA_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "SOURCE_DATA_DB_USERNAME", value = "home_search_coordinate_migrator" },
      ]
      secrets = [{ name = "SOURCE_DATA_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["coordinate-migrator-db"].arn}:password::" }]
    }
    property-batch = {
      image   = "property-batch"
      role    = aws_iam_role.workload_task["property-batch"].arn
      command = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "staging" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "COORDINATE_SOURCE_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "COORDINATE_SOURCE_DB_USERNAME", value = "home_search_coordinate_reader" },
        { name = "COORDINATE_SOURCE_DB_READ_ONLY", value = "true" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
        { name = "COORDINATE_SOURCE_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["coordinate-reader-db"].arn}:password::" },
        { name = "APT_SERVICE_KEY", valueFrom = "${aws_secretsmanager_secret.container["public-data-providers"].arn}:apt_service_key::" },
        { name = "NAVER_NEWS_API_KEY_ID", valueFrom = "${aws_secretsmanager_secret.container["public-data-providers"].arn}:naver_news_api_key_id::" },
        { name = "NAVER_NEWS_API_KEY", valueFrom = "${aws_secretsmanager_secret.container["public-data-providers"].arn}:naver_news_api_key::" },
      ]
    }
    property-event-relay = {
      image   = "property-batch"
      role    = aws_iam_role.workload_task["property-event-relay"].arn
      command = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "staging" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "HOME_KAFKA_BOOTSTRAP_SERVERS", value = aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam },
        { name = "HOME_EVENTS_RELAY_ENABLED", value = "true" },
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
      ]
    }
    property-event-maintenance = {
      image   = "property-batch"
      role    = aws_iam_role.workload_task["property-event-maintenance"].arn
      command = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "staging" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "HOME_EVENTS_RETENTION_ENABLED", value = "true" },
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["property-runtime-db"].arn}:password::" },
      ]
    }
    admin-ops = {
      image   = "admin-ops"
      role    = aws_iam_role.workload_task["admin-ops"].arn
      command = []
      environment = [
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
      ]
      secrets = [{ name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["admin-runtime-db"].arn}:password::" }]
    }
    backup = {
      image   = "backup"
      role    = aws_iam_role.workload_task["backup"].arn
      command = ["--backup-all", "/backup"]
      environment = [
        { name = "HOME_BACKUP_PGHOST", value = aws_db_instance.primary.address },
        { name = "HOME_BACKUP_PGPORT", value = "5432" },
        { name = "HOME_BACKUP_PGUSER", value = "home_search_backup" },
        { name = "HOME_BACKUP_S3_URI", value = "s3://${aws_s3_bucket.database_backup.id}/staging" },
      ]
      secrets = [{ name = "HOME_BACKUP_PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.container["backup-db"].arn}:password::" }]
    }
    restore-verification = {
      image       = "backup"
      role        = aws_iam_role.workload_task["restore-verification"].arn
      command     = ["--verify-latest-s3", "s3://${aws_s3_bucket.database_backup.id}/staging"]
      environment = []
      secrets     = []
    }
  }
}

resource "aws_ecs_task_definition" "one_shot" {
  for_each                 = local.one_shot_specs
  family                   = "${local.name}-${each.key}"
  cpu                      = "512"
  memory                   = "1024"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  execution_role_arn       = aws_iam_role.workload_execution[each.key].arn
  task_role_arn            = each.value.role
  skip_destroy             = true
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  ephemeral_storage { size_in_gib = contains(["backup", "restore-verification"], each.key) ? 40 : 21 }
  container_definitions = jsonencode([merge({
    name                   = each.key
    image                  = local.image_references[each.value.image]
    essential              = true
    environment            = each.value.environment
    secrets                = each.value.secrets
    user                   = "10001:10001"
    readonlyRootFilesystem = false
    logConfiguration       = local.awslogs[each.key]
  }, length(each.value.command) > 0 ? { command = each.value.command } : {})])
}

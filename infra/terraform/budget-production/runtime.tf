locals {
  host_gateway = "172.31.255.1"
  platform_release_tag = (
    var.platform_deployment_release_tag == ""
    ? var.deployment_release_tag
    : var.platform_deployment_release_tag
  )
  awslogs = {
    for name, group in aws_cloudwatch_log_group.runtime : name => {
      logDriver = "awslogs"
      options = {
        awslogs-group         = group.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }

  platform_specs = {
    budget-postgres = {
      container_port = 5432
      host_port      = 15432
      cpu            = 256
      memory         = 1792
      environment = [
        { name = "POSTGRES_USER", value = "home_search_bootstrap" },
        { name = "POSTGRES_DB", value = "home_search" },
        { name = "PGDATA", value = "/var/lib/postgresql/data/pgdata" },
        { name = "TZ", value = "Asia/Seoul" },
      ]
      health = ["CMD-SHELL", "pg_isready -h 127.0.0.1 -p 5432 -U home_search_bootstrap -d home_search || exit 1"]
      volumes = {
        postgres-data = { host_path = "/srv/home-search/postgres", container_path = "/var/lib/postgresql/data" }
        postgres-tls  = { host_path = "/srv/home-search/runtime/postgres-tls", container_path = "/run/home-search-postgres-tls" }
      }
    }
    budget-valkey = {
      container_port = 6379
      host_port      = 16379
      cpu            = 128
      memory         = 384
      environment    = [{ name = "TZ", value = "Asia/Seoul" }]
      health         = ["CMD-SHELL", "valkey-cli --no-auth-warning --user admin -a \"$VALKEY_ADMIN_PASSWORD\" ping | grep -qx PONG"]
      volumes = {
        valkey-data = { host_path = "/srv/home-search/valkey", container_path = "/data" }
      }
    }
  }

  application_specs = {
    property-api = {
      image_key      = "property-api"
      container_port = 8080
      host_port      = 18080
      cpu            = 256
      memory         = 1280
      desired        = 1
      readonly_root  = false
      health         = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SERVER_PORT", value = "8080" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "8" },
        { name = "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", value = "1" },
        { name = "SPRING_DATA_REDIS_HOST", value = local.host_gateway },
        { name = "SPRING_DATA_REDIS_PORT", value = "16379" },
        { name = "SPRING_DATA_REDIS_USERNAME", value = "property" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "false" },
        { name = "HOME_MAP_MARKER_CACHE_ENABLED", value = "true" },
        { name = "HOME_PLACE_KAKAO_ENABLED", value = "true" },
        { name = "HOME_NEWS_PUBLIC_ENABLED", value = tostring(var.market_news_public_enabled) },
        { name = "HOME_PREDICTION_ENABLED", value = tostring(var.prediction_enabled) },
        { name = "HOME_PREDICTION_CLIENT_BASE_URL", value = "http://${local.host_gateway}:18085" },
        { name = "HOME_ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "HOME_ADMIN_INTERNAL_ISSUER", value = "admin-service" },
        { name = "HOME_ADMIN_INTERNAL_AUDIENCE", value = "property-data-admin" },
        { name = "HOME_ADMIN_INTERNAL_PUBLIC_KEYS", value = "budget-production-1=/run/keys/public.pem" },
        { name = "FRONTEND_URL", value = "https://${var.public_hostname}" },
      ]
    }
    admin-api = {
      image_key      = "admin-api"
      container_port = 8081
      host_port      = 18081
      cpu            = 128
      memory         = 640
      desired        = 0
      readonly_root  = false
      health         = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      environment = [
        { name = "SERVER_PORT", value = "8081" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
        { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "4" },
        { name = "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", value = "1" },
        { name = "ADMIN_INTERNAL_ENABLED", value = "true" },
        { name = "ADMIN_INTERNAL_JWT_ISSUER", value = "admin-service" },
        { name = "ADMIN_INTERNAL_JWT_AUDIENCE", value = "property-data-admin" },
        { name = "ADMIN_INTERNAL_JWT_KEY_ID", value = "budget-production-1" },
        { name = "ADMIN_INTERNAL_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "PROPERTY_DATA_INTERNAL_BASE_URL", value = "http://${local.host_gateway}:18080" },
      ]
    }
    user-api = {
      image_key      = "user-api"
      container_port = 8082
      host_port      = 18082
      cpu            = 128
      memory         = 640
      desired        = 1
      readonly_root  = false
      health         = ["CMD-SHELL", "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8082; printf \"GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n\" >&3; head -1 <&3 | grep -q \" 200 \"' || exit 1"]
      environment = [
        { name = "SERVER_PORT", value = "8082" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "USER_DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search_user?sslmode=require" },
        { name = "USER_DB_USERNAME", value = "home_search_user_runtime" },
        { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "8" },
        { name = "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", value = "1" },
        { name = "USER_ALLOWED_ORIGIN", value = "https://${var.public_hostname}" },
        { name = "USER_OAUTH_SUCCESS_REDIRECT", value = "https://${var.public_hostname}/auth/success" },
        { name = "USER_OAUTH_FAILURE_REDIRECT", value = "https://${var.public_hostname}/auth/failure" },
        { name = "USER_COOKIE_SECURE", value = "true" },
        { name = "USER_JWT_ACTIVE_KID", value = "budget-production-1" },
        { name = "USER_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "USER_JWT_ACTIVE_PUBLIC_KEY_PATH", value = "/run/keys/public.pem" },
        { name = "HOME_USER_OAUTH_ENABLED_PROVIDERS", value = join(",", sort(tolist(var.user_oauth_enabled_providers))) },
      ]
    }
    ai = {
      image_key      = "ai"
      container_port = 8000
      host_port      = 18084
      cpu            = 256
      memory         = 1280
      desired        = 1
      readonly_root  = true
      health         = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)\" || exit 1"]
      environment = [
        { name = "HOME_AI_JWT_PUBLIC_KEY_PATHS", value = "{\"budget-production-1\":\"/run/keys/public.pem\"}" },
        { name = "HOME_AI_OPENAI_TIMEOUT_SECONDS", value = "8" },
        { name = "HOME_AI_QUERY_TIMEOUT_SECONDS", value = "55" },
        { name = "HOME_AI_PROPERTY_SEARCH_FALLBACK_ENABLED", value = "true" },
        { name = "HOME_AI_PROPERTY_SEARCH_BASE_URL", value = "http://${local.host_gateway}:18080" },
        { name = "HOME_AI_DEPLOYMENT_TIER", value = "production" },
        { name = "HOME_AI_SUPERVISOR_GRAPH_MODE", value = var.ai_supervisor_graph_mode },
        { name = "HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT", value = tostring(var.ai_supervisor_graph_canary_percent) },
        { name = "HOME_AI_AGENTIC_ORCHESTRATION_ENABLED", value = "true" },
        { name = "HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED", value = "true" },
        { name = "HOME_AI_ENABLED_PROPERTY_CAPABILITIES", value = "complex_identity,recent_trade_lookup,price_trend,recommendation,comparison" },
        { name = "HOME_AI_ENABLED_REFERENCE_CAPABILITIES", value = "academy_lookup,rail_station_lookup,school_location,retail_location" },
        { name = "HOME_AI_DB_POOL_MIN_SIZE", value = "1" },
        { name = "HOME_AI_DB_POOL_MAX_SIZE", value = "2" },
      ]
    }
    chat-bff = {
      image_key      = "chat-bff"
      container_port = 8083
      host_port      = 18083
      cpu            = 128
      memory         = 640
      desired        = 1
      readonly_root  = true
      health         = ["CMD-SHELL", "curl --fail --silent --max-time 3 http://127.0.0.1:8083/actuator/health/readiness >/dev/null || exit 1"]
      environment = [
        { name = "SERVER_PORT", value = "8083" },
        { name = "SERVER_SHUTDOWN", value = "graceful" },
        { name = "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value = "90s" },
        { name = "HOME_CHAT_BFF_AI_BASE_URL", value = "http://${local.host_gateway}:18084" },
        { name = "HOME_CHAT_BFF_AI_TIMEOUT", value = "70s" },
        { name = "HOME_CHAT_BFF_JWT_PUBLIC_KEY_PATHS", value = "budget-production-1=/run/keys/public.pem" },
        { name = "SPRING_DATA_REDIS_HOST", value = local.host_gateway },
        { name = "SPRING_DATA_REDIS_PORT", value = "16379" },
        { name = "SPRING_DATA_REDIS_USERNAME", value = "bff" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "false" },
      ]
    }
    public-gateway = {
      image_key      = "public-gateway"
      container_port = 8080
      host_port      = 18000
      cpu            = 256
      memory         = 512
      desired        = local.public_enabled ? 1 : 0
      readonly_root  = false
      health         = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
      environment = [
        { name = "PROPERTY_API_HOST", value = local.host_gateway },
        { name = "PROPERTY_API_PORT", value = "18080" },
        { name = "USER_API_HOST", value = local.host_gateway },
        { name = "USER_API_PORT", value = "18082" },
        { name = "CHAT_BFF_HOST", value = local.host_gateway },
        { name = "CHAT_BFF_PORT", value = "18083" },
        { name = "SEO_RENDERER_HOST", value = "seo-renderer" },
        { name = "SEO_RENDERER_PORT", value = "3000" },
      ]
    }
    admin-gateway = {
      image_key      = "admin-gateway"
      container_port = 8080
      host_port      = 18001
      cpu            = 128
      memory         = 256
      desired        = 0
      readonly_root  = false
      health         = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1"]
      environment = [
        { name = "ADMIN_API_HOST", value = local.host_gateway },
        { name = "ADMIN_API_PORT", value = "18081" },
      ]
    }
    ml = {
      image_key      = "ml"
      container_port = 8001
      host_port      = 18085
      cpu            = 256
      memory         = 1280
      desired        = var.ml_service_enabled ? 1 : 0
      readonly_root  = false
      health         = ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=3)\" || exit 1"]
      environment    = [{ name = "F37_ARTIFACT_DIR", value = "/model" }]
    }
  }
}

resource "aws_cloudwatch_log_group" "runtime" {
  for_each          = local.execution_parameter_sets
  name              = "/home-search/budget-production/${each.key}"
  retention_in_days = 14
  tags              = { Service = each.key, DataClass = "internal" }
}

resource "aws_ecs_task_definition" "platform" {
  for_each                 = local.data_enabled ? local.platform_specs : {}
  family                   = "${local.name}-${each.key}"
  cpu                      = tostring(each.value.cpu)
  memory                   = tostring(each.value.memory)
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.task_execution[each.key].arn
  task_role_arn            = aws_iam_role.task_runtime[each.key].arn
  skip_destroy             = true
  enable_fault_injection   = false

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  dynamic "volume" {
    for_each = each.value.volumes
    content {
      name                = volume.key
      host_path           = volume.value.host_path
      configure_at_launch = false
    }
  }

  container_definitions = jsonencode([{
    name      = each.key
    image     = var.platform_image_uris[each.key]
    essential = true
    portMappings = [{
      containerPort = each.value.container_port
      hostPort      = each.value.host_port
      protocol      = "tcp"
    }]
    environment = each.value.environment
    secrets = [for name, parameter in local.platform_secret_parameters[each.key] : {
      name      = name
      valueFrom = local.runtime_parameter_arns[parameter]
    }]
    mountPoints = [for name, volume in each.value.volumes : {
      sourceVolume  = name
      containerPath = volume.container_path
      readOnly      = false
    }]
    systemControls         = []
    volumesFrom            = []
    readonlyRootFilesystem = false
    privileged             = false
    linuxParameters = {
      initProcessEnabled = true
      capabilities       = { add = [], drop = ["NET_RAW"] }
    }
    healthCheck = {
      command     = each.value.health
      interval    = 30
      timeout     = 5
      retries     = 5
      startPeriod = 60
    }
    stopTimeout      = 120
    logConfiguration = local.awslogs[each.key]
  }])

  tags = { Service = each.key, Release = local.platform_release_tag }
}

resource "aws_ecs_service" "platform" {
  for_each                           = aws_ecs_task_definition.platform
  name                               = each.key
  cluster                            = aws_ecs_cluster.this[0].id
  task_definition                    = each.value.arn
  desired_count                      = var.data_services_enabled ? 1 : 0
  launch_type                        = "EC2"
  scheduling_strategy                = "REPLICA"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  enable_execute_command             = false
  wait_for_steady_state              = false
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  depends_on = [aws_volume_attachment.data]
  tags       = { Service = each.key }
}

resource "aws_ecs_task_definition" "application" {
  for_each                 = local.private_enabled ? local.application_specs : {}
  family                   = "${local.name}-${each.key}"
  cpu                      = tostring(each.value.cpu)
  memory                   = tostring(each.value.memory)
  network_mode             = "bridge"
  requires_compatibilities = ["EC2"]
  execution_role_arn       = aws_iam_role.task_execution[each.key].arn
  task_role_arn            = aws_iam_role.task_runtime[each.key].arn
  skip_destroy             = true
  enable_fault_injection   = false

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  dynamic "volume" {
    for_each = length(local.application_key_parameters[each.key]) > 0 ? [1] : []
    content {
      name                = "keys"
      configure_at_launch = false
    }
  }

  dynamic "volume" {
    for_each = each.key == "ml" ? [1] : []
    content {
      name                = "model"
      host_path           = "/srv/home-search/runtime/ml-model"
      configure_at_launch = false
    }
  }

  container_definitions = jsonencode(concat(
    [{
      name      = each.key
      image     = var.image_uris[each.value.image_key]
      essential = true
      portMappings = [{
        containerPort = each.value.container_port
        hostPort      = each.value.host_port
        protocol      = "tcp"
      }]
      environment = each.value.environment
      secrets = [for name, parameter in local.application_secret_parameters[each.key] : {
        name      = name
        valueFrom = local.runtime_parameter_arns[parameter]
      }]
      mountPoints = concat(
        length(local.application_key_parameters[each.key]) > 0 ? [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = true }] : [],
        each.key == "ml" ? [{ sourceVolume = "model", containerPath = "/model", readOnly = true }] : [],
      )
      dependsOn              = length(local.application_key_parameters[each.key]) > 0 ? [{ containerName = "key-materializer", condition = "SUCCESS" }] : []
      links                  = each.key == "public-gateway" ? ["seo-renderer:seo-renderer"] : []
      systemControls         = []
      volumesFrom            = []
      readonlyRootFilesystem = each.value.readonly_root
      privileged             = false
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { add = [], drop = ["NET_RAW"] }
      }
      healthCheck = {
        command     = each.value.health
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
      stopTimeout      = 90
      logConfiguration = local.awslogs[each.key]
    }],
    each.key == "public-gateway" ? [{
      name              = "seo-renderer"
      image             = var.image_uris["seo-renderer"]
      essential         = false
      cpu               = 64
      memoryReservation = 128
      memory            = 192
      portMappings      = [{ containerPort = 3000, hostPort = 0, protocol = "tcp" }]
      environment = [
        { name = "PORT", value = "3000" },
        { name = "HOME_SEO_INDEX_MODE", value = "PILOT" },
        { name = "HOME_SEO_CANONICAL_ORIGIN", value = "https://${var.public_hostname}" },
        { name = "HOME_SEO_PROPERTY_API_BASE_URL", value = "http://${local.host_gateway}:18080" },
        { name = "HOME_SEO_PAGE_CACHE_TTL", value = "15m" },
        { name = "HOME_SEO_SITEMAP_CACHE_TTL", value = "6h" },
        { name = "HOME_SEO_STALE_IF_ERROR", value = "24h" },
      ]
      secrets                = []
      mountPoints            = []
      dependsOn              = []
      links                  = []
      systemControls         = []
      volumesFrom            = []
      readonlyRootFilesystem = true
      privileged             = false
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { add = [], drop = ["NET_RAW"] }
      }
      healthCheck = {
        command     = ["CMD-SHELL", "node -e \"fetch('http://127.0.0.1:3000/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))\""]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
      stopTimeout      = 30
      logConfiguration = local.awslogs[each.key]
    }] : [],
    length(local.application_key_parameters[each.key]) > 0 ? [{
      name                   = "key-materializer"
      user                   = "0:0"
      image                  = var.image_uris["ops-bootstrap"]
      essential              = false
      command                = ["materialize-keys"]
      environment            = [{ name = "KEY_OUTPUT_DIRECTORY", value = "/run/keys" }]
      secrets                = [for name, parameter in local.application_key_parameters[each.key] : { name = name, valueFrom = local.runtime_parameter_arns[parameter] }]
      mountPoints            = [{ sourceVolume = "keys", containerPath = "/run/keys", readOnly = false }]
      portMappings           = []
      links                  = []
      systemControls         = []
      volumesFrom            = []
      readonlyRootFilesystem = false
      privileged             = false
      linuxParameters = {
        initProcessEnabled = true
        capabilities       = { add = [], drop = ["NET_RAW"] }
      }
      logConfiguration = local.awslogs[each.key]
    }] : [],
  ))

  tags = { Service = each.key, Release = var.deployment_release_tag }
}

resource "aws_ecs_service" "application" {
  for_each = aws_ecs_task_definition.application
  name     = each.key
  cluster  = aws_ecs_cluster.this[0].id
  task_definition = lookup(
    var.application_service_task_definition_arns,
    each.key,
    each.value.arn,
  )
  desired_count = lookup(
    var.application_service_desired_counts,
    each.key,
    local.application_specs[each.key].desired,
  )
  launch_type                        = "EC2"
  scheduling_strategy                = "REPLICA"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent = lookup(
    var.application_deployment_maximum_percents,
    each.key,
    100,
  )
  enable_execute_command = false
  wait_for_steady_state  = false
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  depends_on = [aws_ecs_service.platform]
  tags       = { Service = each.key }
}

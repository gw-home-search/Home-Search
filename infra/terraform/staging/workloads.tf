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
        { name = "SPRING_PROFILES_ACTIVE", value = "local" },
        { name = "SERVER_PORT", value = "8080" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "COORDINATE_SOURCE_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "COORDINATE_SOURCE_DB_USERNAME", value = "home_search_coordinate_reader" },
        { name = "COORDINATE_SOURCE_DB_READ_ONLY", value = "true" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.this.primary_endpoint_address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
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
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:property_runtime::" },
        { name = "COORDINATE_SOURCE_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:coordinate_reader::" },
      ]
      key_secrets = [
        { name = "PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.container["admin-internal-jwt"].arn}:public_key_pem::" },
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
        { name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:admin_runtime::" },
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
        { name = "USER_OAUTH_SUCCESS_REDIRECT", value = "${var.public_origin}/auth/callback" },
        { name = "USER_OAUTH_FAILURE_REDIRECT", value = "${var.public_origin}/login?error=oauth" },
        { name = "USER_COOKIE_SECURE", value = "true" },
        { name = "USER_JWT_ACTIVE_KID", value = "staging-1" },
        { name = "USER_JWT_PRIVATE_KEY_PATH", value = "/run/keys/private.pem" },
        { name = "USER_JWT_ACTIVE_PUBLIC_KEY_PATH", value = "/run/keys/public.pem" },
      ]
      secrets = [
        { name = "USER_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:user_runtime::" },
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
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.runtime_task.arn
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
      readonlyRootFilesystem = false
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

locals {
  one_shot_specs = {
    secret-bootstrap = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.secret_bootstrap_task.arn
      command = ["secret-bootstrap"]
      environment = [
        { name = "DATABASE_RUNTIME_SECRET_ARN", value = aws_secretsmanager_secret.container["database-runtime"].arn },
        { name = "DATABASE_BOOTSTRAP_SECRET_ARN", value = aws_secretsmanager_secret.container["database-bootstrap"].arn },
        { name = "USER_JWT_SECRET_ARN", value = aws_secretsmanager_secret.container["user-jwt"].arn },
        { name = "ADMIN_JWT_SECRET_ARN", value = aws_secretsmanager_secret.container["admin-internal-jwt"].arn },
      ]
      secrets = []
    }
    database-bootstrap = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.database_bootstrap_task.arn
      command = ["db-bootstrap"]
      environment = [
        { name = "PRIMARY_RDS_SECRET_ARN", value = aws_db_instance.primary.master_user_secret[0].secret_arn },
        { name = "COORDINATE_RDS_SECRET_ARN", value = aws_db_instance.coordinate_source.master_user_secret[0].secret_arn },
        { name = "DATABASE_RUNTIME_SECRET_ARN", value = aws_secretsmanager_secret.container["database-runtime"].arn },
        { name = "DATABASE_BOOTSTRAP_SECRET_ARN", value = aws_secretsmanager_secret.container["database-bootstrap"].arn },
      ]
      secrets = []
    }
    runtime-grants = {
      image   = "ops-bootstrap"
      role    = aws_iam_role.database_bootstrap_task.arn
      command = ["runtime-grants"]
      environment = [
        { name = "PRIMARY_RDS_SECRET_ARN", value = aws_db_instance.primary.master_user_secret[0].secret_arn },
        { name = "DATABASE_BOOTSTRAP_SECRET_ARN", value = aws_secretsmanager_secret.container["database-bootstrap"].arn },
      ]
      secrets = []
    }
    property-flyway = {
      image   = "property-flyway"
      role    = aws_iam_role.runtime_task.arn
      command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_property_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-bootstrap"].arn}:property_migrator::" }]
    }
    admin-migration = {
      image   = "admin-migration"
      role    = aws_iam_role.runtime_task.arn
      command = []
      environment = [
        { name = "ADMIN_MIGRATION_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_MIGRATION_DB_USERNAME", value = "home_search_admin_migrator" },
      ]
      secrets = [{ name = "ADMIN_MIGRATION_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-bootstrap"].arn}:admin_migrator::" }]
    }
    user-flyway = {
      image   = "user-flyway"
      role    = aws_iam_role.runtime_task.arn
      command = ["migrate"]
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_user?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_user_migrator" },
      ]
      secrets = [{ name = "FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-bootstrap"].arn}:user_migrator::" }]
    }
    source-data-migration = {
      image   = "source-data-migration"
      role    = aws_iam_role.runtime_task.arn
      command = []
      environment = [
        { name = "SOURCE_DATA_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "SOURCE_DATA_DB_USERNAME", value = "home_search_coordinate_migrator" },
      ]
      secrets = [{ name = "SOURCE_DATA_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-bootstrap"].arn}:coordinate_migrator::" }]
    }
    property-batch = {
      image   = "property-batch"
      role    = aws_iam_role.runtime_task.arn
      command = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "local" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "COORDINATE_SOURCE_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.coordinate_source.address}:5432/home_search_coordinate_source?sslmode=require" },
        { name = "COORDINATE_SOURCE_DB_USERNAME", value = "home_search_coordinate_reader" },
        { name = "COORDINATE_SOURCE_DB_READ_ONLY", value = "true" },
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:property_runtime::" },
        { name = "COORDINATE_SOURCE_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:coordinate_reader::" },
      ]
    }
    admin-ops = {
      image   = "admin-ops"
      role    = aws_iam_role.runtime_task.arn
      command = []
      environment = [
        { name = "ADMIN_DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.primary.address}:5432/home_search_admin?sslmode=require" },
        { name = "ADMIN_DB_USERNAME", value = "home_search_admin_runtime" },
      ]
      secrets = [{ name = "ADMIN_DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-runtime"].arn}:admin_runtime::" }]
    }
    backup = {
      image   = "backup"
      role    = aws_iam_role.backup_task.arn
      command = ["--backup-all", "/backup"]
      environment = [
        { name = "HOME_BACKUP_PGHOST", value = aws_db_instance.primary.address },
        { name = "HOME_BACKUP_PGPORT", value = "5432" },
        { name = "HOME_BACKUP_PGUSER", value = "home_search_backup" },
        { name = "HOME_BACKUP_S3_URI", value = "s3://${aws_s3_bucket.database_backup.id}/staging" },
      ]
      secrets = [{ name = "HOME_BACKUP_PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.container["database-bootstrap"].arn}:backup::" }]
    }
    restore-verification = {
      image       = "backup"
      role        = aws_iam_role.backup_task.arn
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
  execution_role_arn       = aws_iam_role.task_execution.arn
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

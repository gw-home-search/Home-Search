locals {
  market_news_common_environment = [
    { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
    { name = "DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search?sslmode=require" },
    { name = "DB_USERNAME", value = "home_search_property_runtime" },
    { name = "SPRING_DATA_REDIS_HOST", value = local.host_gateway },
    { name = "SPRING_DATA_REDIS_PORT", value = "16379" },
    { name = "SPRING_DATA_REDIS_USERNAME", value = "property" },
    { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "false" },
    { name = "HOME_NEWS_NAVER_ENABLED", value = "true" },
    { name = "HOME_NEWS_NAVER_PROVIDER_MODE", value = "API_HUB" },
    { name = "HOME_NEWS_CACHE_ENABLED", value = "true" },
    { name = "HOME_NEWS_DAILY_CALL_BUDGET", value = "4000" },
    { name = "HOME_NEWS_CACHE_TTL", value = "31d" },
    { name = "HOME_NEWS_CONNECT_TIMEOUT", value = "2s" },
    { name = "HOME_NEWS_READ_TIMEOUT", value = "5s" },
  ]

  one_shot_specs = {
    secret-bootstrap = {
      image_key  = "ops-bootstrap"
      command    = ["budget-secret-bootstrap"]
      entrypoint = []
      environment = [{
        name  = "BUDGET_PARAMETER_PREFIX"
        value = "/home-search/budget-production"
      }]
    }
    secret-readiness = {
      image_key  = "ops-bootstrap"
      command    = ["budget-secret-readiness"]
      entrypoint = []
      environment = [{
        name  = "BUDGET_PARAMETER_PREFIX"
        value = "/home-search/budget-production"
        }, {
        name  = "HOME_USER_OAUTH_ENABLED_PROVIDERS"
        value = join(",", sort(tolist(var.user_oauth_enabled_providers)))
      }]
    }
    property-flyway = {
      image_key  = "property-flyway"
      command    = ["-target=${var.property_migration_target}", "migrate"]
      entrypoint = []
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_property_migrator" },
      ]
    }
    user-flyway = {
      image_key  = "user-flyway"
      command    = ["migrate"]
      entrypoint = []
      environment = [
        { name = "FLYWAY_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search_user?sslmode=require" },
        { name = "FLYWAY_USER", value = "home_search_user_migrator" },
      ]
    }
    admin-migration = {
      image_key  = "admin-migration"
      command    = []
      entrypoint = []
      environment = [
        { name = "ADMIN_MIGRATION_DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search_admin?sslmode=require" },
        { name = "ADMIN_MIGRATION_DB_USERNAME", value = "home_search_admin_migrator" },
      ]
    }
    ai-migration = {
      image_key   = "ai"
      command     = ["home-ai-migrate"]
      entrypoint  = []
      environment = []
    }
    importer-grants = {
      image_key  = "ops-bootstrap"
      command    = ["budget-importer-grants"]
      entrypoint = []
      environment = [
        { name = "PROPERTY_DB_HOST", value = local.host_gateway },
        { name = "PROPERTY_DB_PORT", value = "15432" },
        { name = "AI_DB_HOST", value = local.host_gateway },
        { name = "AI_DB_PORT", value = "15432" },
      ]
    }
    scheduled-backup = {
      image_key  = "backup"
      command    = []
      entrypoint = ["/usr/local/bin/run-budget-pg-backup"]
      environment = [
        { name = "HOME_BACKUP_PGHOST", value = local.host_gateway },
        { name = "HOME_BACKUP_PGPORT", value = "15432" },
        { name = "HOME_BACKUP_PGUSER", value = "home_search_backup" },
        { name = "HOME_BACKUP_LOGICAL_DATABASES", value = "property,admin,user,ai" },
        { name = "HOME_BACKUP_S3_URI", value = local.data_enabled ? "s3://${aws_s3_bucket.backup[0].id}/logical" : "" },
        { name = "HOME_BACKUP_KMS_KEY_ID", value = "alias/aws/s3" },
      ]
    }
    runtime-feature-audit = {
      image_key  = "backup"
      command    = []
      entrypoint = ["/usr/local/bin/run-budget-runtime-feature-audit"]
      environment = [
        { name = "HOME_BACKUP_PGHOST", value = local.host_gateway },
        { name = "HOME_BACKUP_PGPORT", value = "15432" },
        { name = "HOME_BACKUP_PGUSER", value = "home_search_backup" },
        { name = "HOME_RUNTIME_AUDIT_S3_URI", value = local.data_enabled ? "s3://${aws_s3_bucket.backup[0].id}/deployment-evidence/runtime-audit" : "" },
      ]
    }
    runtime-log-audit = {
      image_key  = "backup"
      command    = []
      entrypoint = ["/usr/local/bin/run-budget-runtime-log-audit"]
      environment = [{
        name  = "HOME_RUNTIME_AUDIT_S3_URI"
        value = local.data_enabled ? "s3://${aws_s3_bucket.backup[0].id}/deployment-evidence/runtime-audit" : ""
      }]
    }
    data-import-reconcile = {
      image_key  = "backup"
      command    = []
      entrypoint = ["/usr/local/bin/run-s3-data-migration"]
      environment = [
        { name = "TMPDIR", value = "/work" },
        { name = "PGSSLMODE", value = "require" },
        { name = "HOME_MIGRATION_ARTIFACT_S3_URI", value = var.migration_artifact_s3_uri },
        { name = "HOME_MIGRATION_MANIFEST_SHA256", value = var.migration_manifest_sha256 },
        { name = "HOME_MIGRATION_EVIDENCE_S3_URI", value = local.data_enabled ? "s3://${aws_s3_bucket.backup[0].id}/deployment-evidence/${var.data_import_preserved_release_tag != "" ? var.data_import_preserved_release_tag : var.deployment_release_tag}" : "" },
        { name = "HOME_MIGRATION_EVIDENCE_KMS_KEY_ID", value = "alias/aws/s3" },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_HOST", value = local.host_gateway },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_PORT", value = "15432" },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_DATABASE", value = "home_search" },
        { name = "HOME_MIGRATION_PROPERTY_TARGET_USER", value = "home_search_property_importer" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_HOST", value = local.host_gateway },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_PORT", value = "15432" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_DATABASE", value = "home_search_ai" },
        { name = "HOME_MIGRATION_REFERENCE_TARGET_USER", value = "home_search_ai_importer" },
        { name = "HOME_MIGRATION_RAW_TARGET_BUCKET", value = local.data_enabled ? aws_s3_bucket.reference_raw[0].id : "" },
        { name = "HOME_MIGRATION_RAW_TARGET_REGION", value = var.aws_region },
        { name = "HOME_MIGRATION_RAW_TARGET_KMS_KEY_ID", value = "alias/aws/s3" },
      ]
    }
    map-marker-projection = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_BATCH_JOB_NAME", value = "mapMarkerProjectionJob" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
      ]
    }
    rtms-daily-refresh = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "SPRING_BATCH_JOB_NAME", value = "rtmsDailyRefreshJob" },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${local.host_gateway}:15432/home_search?sslmode=require" },
        { name = "DB_USERNAME", value = "home_search_property_runtime" },
        { name = "HOME_INGEST_RTMS_DAILY_ENABLED", value = "true" },
        { name = "HOME_INGEST_RTMS_DAILY_LAWD_CDS", value = "" },
        { name = "HOME_INGEST_RTMS_DAILY_LOOKBACK_MONTHS", value = "2" },
        { name = "HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY", value = "true" },
        { name = "HOME_INSIGHT_TRADE_ENABLED", value = "true" },
      ]
    }
    market-news-general = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsGeneralJob" },
      ])
    }
    market-news-morning = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsMorningJob" },
      ])
    }
    market-news-major-complex = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsMajorComplexJob" },
      ])
    }
    market-news-major-selection = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsMajorSelectionJob" },
      ])
    }
    market-news-retention = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsRetentionJob" },
      ])
    }
    market-news-quality-sample = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsQualitySampleJob" },
      ])
    }
    market-news-withdrawal = {
      image_key  = "property-batch"
      command    = []
      entrypoint = []
      environment = concat(local.market_news_common_environment, [
        { name = "SPRING_BATCH_JOB_NAME", value = "marketNewsWithdrawalJob" },
      ])
    }
    runtime-grants = {
      image_key  = "ops-bootstrap"
      command    = ["runtime-grants"]
      entrypoint = []
      environment = [
        { name = "PROPERTY_DB_HOST", value = local.host_gateway },
        { name = "PROPERTY_DB_PORT", value = "15432" },
        { name = "ADMIN_DB_HOST", value = local.host_gateway },
        { name = "ADMIN_DB_PORT", value = "15432" },
        { name = "USER_DB_HOST", value = local.host_gateway },
        { name = "USER_DB_PORT", value = "15432" },
        { name = "AI_DB_HOST", value = local.host_gateway },
        { name = "AI_DB_PORT", value = "15432" },
      ]
    }
  }
}

resource "aws_ecs_task_definition" "one_shot" {
  for_each                 = local.data_enabled ? local.one_shot_specs : {}
  family                   = "${local.name}-${each.key}"
  cpu                      = each.key == "data-import-reconcile" ? "512" : "256"
  memory                   = each.key == "data-import-reconcile" ? "1024" : "512"
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
    for_each = contains(["data-import-reconcile", "scheduled-backup"], each.key) ? [1] : []
    content {
      name                = "task-work"
      host_path           = "/srv/home-search/backup-staging"
      configure_at_launch = false
    }
  }

  container_definitions = jsonencode([merge({
    name = each.key
    image = (
      each.key == "data-import-reconcile" && var.data_import_preserved_image_uri != ""
      ? var.data_import_preserved_image_uri
      : var.image_uris[each.value.image_key]
    )
    essential   = true
    environment = each.value.environment
    secrets = [for name, parameter in local.one_shot_secret_parameters[each.key] : {
      name      = name
      valueFrom = local.runtime_parameter_arns[parameter]
    }]
    mountPoints = contains(["data-import-reconcile", "scheduled-backup"], each.key) ? [{
      sourceVolume  = "task-work"
      containerPath = each.key == "data-import-reconcile" ? "/work" : "/backup-staging"
      readOnly      = false
    }] : []
    portMappings           = []
    systemControls         = []
    volumesFrom            = []
    readonlyRootFilesystem = false
    privileged             = false
    linuxParameters = {
      initProcessEnabled = true
      capabilities       = { add = [], drop = ["NET_RAW"] }
    }
    stopTimeout      = 120
    logConfiguration = local.awslogs[each.key]
    }, length(each.value.command) > 0 ? { command = each.value.command } : {},
  length(each.value.entrypoint) > 0 ? { entryPoint = each.value.entrypoint } : {})])

  tags = {
    Service       = each.key
    WorkloadClass = "one-shot"
    Release = (
      each.key == "data-import-reconcile" && var.data_import_preserved_release_tag != ""
      ? var.data_import_preserved_release_tag
      : var.deployment_release_tag
    )
  }
}

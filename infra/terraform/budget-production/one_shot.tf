locals {
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
      }]
    }
    property-flyway = {
      image_key  = "property-flyway"
      command    = ["migrate"]
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
    data-import-reconcile = {
      image_key  = "backup"
      command    = []
      entrypoint = ["/usr/local/bin/run-s3-data-migration"]
      environment = [
        { name = "TMPDIR", value = "/work" },
        { name = "PGSSLMODE", value = "require" },
        { name = "HOME_MIGRATION_ARTIFACT_S3_URI", value = var.migration_artifact_s3_uri },
        { name = "HOME_MIGRATION_MANIFEST_SHA256", value = var.migration_manifest_sha256 },
        { name = "HOME_MIGRATION_EVIDENCE_S3_URI", value = local.data_enabled ? "s3://${aws_s3_bucket.backup[0].id}/deployment-evidence/${var.deployment_release_tag}" : "" },
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

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  dynamic "volume" {
    for_each = contains(["data-import-reconcile", "scheduled-backup"], each.key) ? [1] : []
    content {
      name      = "task-work"
      host_path = "/srv/home-search/backup-staging"
    }
  }

  container_definitions = jsonencode([merge({
    name        = each.key
    image       = var.image_uris[each.value.image_key]
    essential   = true
    environment = each.value.environment
    secrets = [for name, parameter in local.one_shot_secret_parameters[each.key] : {
      name      = name
      valueFrom = aws_ssm_parameter.runtime[parameter].arn
    }]
    mountPoints = contains(["data-import-reconcile", "scheduled-backup"], each.key) ? [{
      sourceVolume  = "task-work"
      containerPath = each.key == "data-import-reconcile" ? "/work" : "/backup-staging"
      readOnly      = false
    }] : []
    readonlyRootFilesystem = false
    privileged             = false
    linuxParameters = {
      initProcessEnabled = true
      capabilities       = { drop = ["NET_RAW"] }
    }
    stopTimeout      = 120
    logConfiguration = local.awslogs[each.key]
    }, length(each.value.command) > 0 ? { command = each.value.command } : {},
  length(each.value.entrypoint) > 0 ? { entryPoint = each.value.entrypoint } : {})])

  tags = { Service = each.key, WorkloadClass = "one-shot", Release = var.deployment_release_tag }
}

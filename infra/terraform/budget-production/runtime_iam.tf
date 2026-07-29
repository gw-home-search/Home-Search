locals {
  platform_secret_parameters = {
    budget-postgres = {
      POSTGRES_PASSWORD              = "postgres/superuser-password"
      PROPERTY_RUNTIME_DB_PASSWORD   = "postgres/property-runtime-password"
      PROPERTY_MIGRATOR_DB_PASSWORD  = "postgres/property-migrator-password"
      PROPERTY_IMPORTER_DB_PASSWORD  = "postgres/property-importer-password"
      AI_PROPERTY_READER_DB_PASSWORD = "postgres/property-ai-reader-password"
      USER_RUNTIME_DB_PASSWORD       = "postgres/user-runtime-password"
      USER_MIGRATOR_DB_PASSWORD      = "postgres/user-migrator-password"
      ADMIN_RUNTIME_DB_PASSWORD      = "postgres/admin-runtime-password"
      ADMIN_MIGRATOR_DB_PASSWORD     = "postgres/admin-migrator-password"
      AI_DATA_RUNTIME_DB_PASSWORD    = "postgres/ai-runtime-password"
      AI_DATA_MIGRATOR_DB_PASSWORD   = "postgres/ai-migrator-password"
      AI_DATA_IMPORTER_DB_PASSWORD   = "postgres/ai-importer-password"
      BACKUP_DB_PASSWORD             = "postgres/backup-password"
    }
    budget-valkey = {
      VALKEY_ADMIN_PASSWORD    = "valkey/admin-password"
      VALKEY_PROPERTY_PASSWORD = "valkey/property-password"
      VALKEY_BFF_PASSWORD      = "valkey/bff-password"
    }
  }

  application_secret_parameters = {
    property-api = {
      DB_PASSWORD                = "postgres/property-runtime-password"
      SPRING_DATA_REDIS_PASSWORD = "valkey/property-password"
      KAKAO_REST_API_KEY         = "property/kakao-rest-api-key"
    }
    admin-api = {
      ADMIN_DB_PASSWORD = "postgres/admin-runtime-password"
    }
    user-api = {
      USER_DB_PASSWORD           = "postgres/user-runtime-password"
      GOOGLE_OAUTH_CLIENT_ID     = "user/oauth/google-client-id"
      GOOGLE_OAUTH_CLIENT_SECRET = "user/oauth/google-client-secret"
      KAKAO_OAUTH_CLIENT_ID      = "user/oauth/kakao-client-id"
      KAKAO_OAUTH_CLIENT_SECRET  = "user/oauth/kakao-client-secret"
      NAVER_OAUTH_CLIENT_ID      = "user/oauth/naver-client-id"
      NAVER_OAUTH_CLIENT_SECRET  = "user/oauth/naver-client-secret"
    }
    ai = {
      HOME_AI_PROPERTY_DSN           = "ai/property-dsn"
      HOME_AI_REFERENCE_DSN          = "ai/reference-dsn"
      HOME_AI_OPENAI_API_KEY         = "ai/openai-api-key"
      HOME_AI_OPENAI_PRIMARY_MODEL   = "ai/openai-primary-model"
      HOME_AI_OPENAI_SECONDARY_MODEL = "ai/openai-secondary-model"
    }
    chat-bff = {
      SPRING_DATA_REDIS_PASSWORD = "valkey/bff-password"
    }
    public-gateway = {}
    admin-gateway  = {}
    ml             = {}
  }

  application_key_parameters = {
    property-api = {
      PUBLIC_KEY_PEM = "admin/jwt-public-key-pem"
    }
    public-gateway = {}
    admin-gateway  = {}
    ml             = {}
    user-api = {
      PRIVATE_KEY_PEM = "user/jwt-private-key-pem"
      PUBLIC_KEY_PEM  = "user/jwt-public-key-pem"
    }
    admin-api = {
      PRIVATE_KEY_PEM = "admin/jwt-private-key-pem"
      PUBLIC_KEY_PEM  = "admin/jwt-public-key-pem"
    }
    ai = {
      PUBLIC_KEY_PEM = "user/jwt-public-key-pem"
    }
    chat-bff = {
      PUBLIC_KEY_PEM = "user/jwt-public-key-pem"
    }
  }

  one_shot_secret_parameters = {
    secret-bootstrap = {}
    secret-readiness = {}
    property-flyway  = { FLYWAY_PASSWORD = "postgres/property-migrator-password" }
    user-flyway      = { FLYWAY_PASSWORD = "postgres/user-migrator-password" }
    admin-migration  = { ADMIN_MIGRATION_DB_PASSWORD = "postgres/admin-migrator-password" }
    ai-migration     = { HOME_AI_MIGRATOR_DSN = "ai/migrator-dsn" }
    importer-grants = {
      PROPERTY_MIGRATOR_DB_PASSWORD = "postgres/property-migrator-password"
      AI_MIGRATOR_DB_PASSWORD       = "postgres/ai-migrator-password"
    }
    scheduled-backup = {
      HOME_BACKUP_PGPASSWORD = "postgres/backup-password"
    }
    data-import-reconcile = {
      HOME_MIGRATION_PROPERTY_TARGET_PASSWORD  = "postgres/property-importer-password"
      HOME_MIGRATION_REFERENCE_TARGET_PASSWORD = "postgres/ai-importer-password"
    }
    map-marker-projection = { DB_PASSWORD = "postgres/property-runtime-password" }
    runtime-grants = {
      PROPERTY_MIGRATOR_DB_PASSWORD = "postgres/property-migrator-password"
      USER_MIGRATOR_DB_PASSWORD     = "postgres/user-migrator-password"
      ADMIN_MIGRATOR_DB_PASSWORD    = "postgres/admin-migrator-password"
      AI_MIGRATOR_DB_PASSWORD       = "postgres/ai-migrator-password"
    }
  }

  one_shot_image_keys = {
    secret-bootstrap      = "ops-bootstrap"
    secret-readiness      = "ops-bootstrap"
    property-flyway       = "property-flyway"
    user-flyway           = "user-flyway"
    admin-migration       = "admin-migration"
    ai-migration          = "ai"
    importer-grants       = "ops-bootstrap"
    scheduled-backup      = "backup"
    data-import-reconcile = "backup"
    map-marker-projection = "property-batch"
    runtime-grants        = "ops-bootstrap"
  }

  execution_parameter_sets = merge(
    local.data_enabled ? local.platform_secret_parameters : {},
    local.data_enabled ? local.one_shot_secret_parameters : {},
    local.private_enabled ? {
      for name in keys(local.application_specs) : name => merge(
        local.application_secret_parameters[name],
        local.application_key_parameters[name],
      )
    } : {},
  )

  execution_image_keys = merge(
    { for name in keys(local.platform_secret_parameters) : name => name },
    { for name, spec in local.application_specs : name => spec.image_key },
    local.one_shot_image_keys,
  )
}

resource "aws_iam_role" "task_execution" {
  for_each = local.execution_parameter_sets
  name     = "${local.name}-${each.key}-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  tags = { Service = each.key, RoleKind = "execution" }
}

resource "aws_iam_role_policy" "task_execution" {
  for_each = local.execution_parameter_sets
  name     = "pull-log-and-own-parameters"
  role     = aws_iam_role.task_execution[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "EcrAuthorization"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "PullOwnImage"
        Effect = "Allow"
        Action = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = distinct(concat(
          ["arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/home-search/${local.execution_image_keys[each.key]}"],
          try(length(local.application_key_parameters[each.key]) > 0, false) ? [
            "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/home-search/ops-bootstrap",
          ] : [],
        ))
      },
      {
        Sid      = "WriteOwnLog"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.runtime[each.key].arn}:*"]
      },
      ], length(each.value) > 0 ? [{
        Sid      = "ReadOwnParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters"]
        Resource = [for parameter in distinct(values(each.value)) : aws_ssm_parameter.runtime[parameter].arn]
    }] : [])
  })
}

resource "aws_iam_role" "task_runtime" {
  for_each = local.execution_parameter_sets
  name     = "${local.name}-${each.key}-runtime"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  tags = {
    Service      = each.key
    RoleKind     = "runtime"
    SecretAccess = contains(["secret-bootstrap", "secret-readiness"], each.key) ? "ssm-runtime" : "none"
  }
}

resource "aws_iam_role_policy" "secret_bootstrap" {
  count = local.data_enabled ? 1 : 0
  name  = "initialize-only-unset-budget-parameters"
  role  = aws_iam_role.task_runtime["secret-bootstrap"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter", "ssm:PutParameter"]
      Resource = [for name in local.generated_runtime_parameter_names : aws_ssm_parameter.runtime[name].arn]
    }]
  })
}

resource "aws_iam_role_policy" "secret_readiness" {
  count = local.data_enabled ? 1 : 0
  name  = "read-budget-parameter-readiness"
  role  = aws_iam_role.task_runtime["secret-readiness"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = [for name in local.runtime_parameter_names : aws_ssm_parameter.runtime[name].arn]
    }]
  })
}

locals {
  migration_artifact_bucket = var.migration_artifact_s3_uri == "" ? "" : split("/", trimprefix(var.migration_artifact_s3_uri, "s3://"))[0]
  migration_artifact_prefix = var.migration_artifact_s3_uri == "" ? "" : trimprefix(
    trimprefix(var.migration_artifact_s3_uri, "s3://"),
    "${local.migration_artifact_bucket}/",
  )
}

resource "aws_iam_role_policy" "data_import" {
  count = local.data_enabled ? 1 : 0
  name  = "reviewed-data-only-artifact-and-evidence"
  role  = aws_iam_role.task_runtime["data-import-reconcile"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      var.migration_artifact_s3_uri == "" ? [] : [{
        Sid    = "ReadReviewedArtifact"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:ListBucket"]
        Resource = [
          "arn:aws:s3:::${local.migration_artifact_bucket}",
          "arn:aws:s3:::${local.migration_artifact_bucket}/${local.migration_artifact_prefix}*",
        ]
      }],
      [{
        Sid    = "WriteMigrationEvidenceAndReferenceRaw"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject"]
        Resource = [
          "${aws_s3_bucket.backup[0].arn}/deployment-evidence/*",
          "${aws_s3_bucket.reference_raw[0].arn}/*",
        ]
      }],
    )
  })
}

resource "aws_iam_role_policy" "scheduled_backup" {
  count = local.data_enabled ? 1 : 0
  name  = "write-immutable-logical-backups"
  role  = aws_iam_role.task_runtime["scheduled-backup"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadBackupPrefix"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.backup[0].arn]
        Condition = {
          StringLike = { "s3:prefix" = ["logical", "logical/*"] }
        }
      },
      {
        Sid      = "WriteAndVerifyBackupObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = ["${aws_s3_bucket.backup[0].arn}/logical/*"]
      },
    ]
  })
}

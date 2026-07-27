locals {
  ecs_task_assume_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  workload_names = toset([
    "property-api", "admin-api", "user-api", "user-insight-worker", "public-gateway", "admin-gateway", "ml",
    "secret-bootstrap", "database-bootstrap", "runtime-grants", "property-flyway",
    "admin-migration", "user-flyway", "source-data-migration", "property-batch",
    "property-event-relay", "property-event-maintenance",
    "admin-ops", "backup", "restore-verification",
  ])
  workload_execution_role_names = merge(
    { for name in local.workload_names : name => "${local.name}-${name}-execution" },
    { "property-api" = "${local.name}-task-execution" },
  )
  workload_task_role_names = merge(
    { for name in local.workload_names : name => "${local.name}-${name}-task" },
    {
      "property-api"       = "${local.name}-runtime-task"
      "secret-bootstrap"   = "${local.name}-secret-bootstrap"
      "database-bootstrap" = "${local.name}-database-bootstrap"
      "backup"             = "${local.name}-backup-task"
    },
  )
  database_credential_secret_names = toset([
    "property-runtime-db", "property-ai-reader-db", "admin-runtime-db",
    "user-runtime-db", "coordinate-reader-db", "property-migrator-db",
    "admin-migrator-db", "user-migrator-db", "coordinate-migrator-db",
    "coordinate-importer-db", "backup-db",
  ])
  runtime_grants_secret_names = toset([
    "property-migrator-db", "admin-migrator-db", "user-migrator-db",
  ])
  workload_execution_secret_names = {
    property-api               = ["property-runtime-db", "coordinate-reader-db", "admin-internal-jwt-public", "kakao-local-provider"]
    admin-api                  = ["admin-runtime-db", "admin-internal-jwt"]
    user-api                   = ["user-runtime-db", "oauth-providers", "user-jwt"]
    user-insight-worker        = ["user-runtime-db"]
    public-gateway             = []
    admin-gateway              = []
    ml                         = []
    secret-bootstrap           = []
    database-bootstrap         = []
    runtime-grants             = []
    property-flyway            = ["property-migrator-db"]
    admin-migration            = ["admin-migrator-db"]
    user-flyway                = ["user-migrator-db"]
    source-data-migration      = ["coordinate-migrator-db"]
    property-batch             = ["property-runtime-db", "coordinate-reader-db", "public-data-providers"]
    property-event-relay       = ["property-runtime-db"]
    property-event-maintenance = ["property-runtime-db"]
    admin-ops                  = ["admin-runtime-db"]
    backup                     = ["backup-db"]
    restore-verification       = []
  }
  workload_execution_secret_arns = {
    for workload, secret_names in local.workload_execution_secret_names :
    workload => [for secret_name in secret_names : aws_secretsmanager_secret.container[secret_name].arn]
  }
}

resource "aws_iam_role" "workload_execution" {
  for_each           = local.workload_names
  name               = local.workload_execution_role_names[each.key]
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy_attachment" "workload_execution" {
  for_each   = local.workload_names
  role       = aws_iam_role.workload_execution[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "workload_execution_secrets" {
  for_each = {
    for name, secret_arns in local.workload_execution_secret_arns : name => secret_arns
    if length(secret_arns) > 0
  }
  name = "materialize-${each.key}-secrets"
  role = aws_iam_role.workload_execution[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = each.value
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role" "workload_task" {
  for_each           = local.workload_names
  name               = local.workload_task_role_names[each.key]
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy" "secret_bootstrap_task" {
  name = "write-empty-secret-containers"
  role = aws_iam_role.workload_task["secret-bootstrap"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        Resource = concat(
          [for secret_name in local.database_credential_secret_names : aws_secretsmanager_secret.container[secret_name].arn],
          [
            aws_secretsmanager_secret.container["user-jwt"].arn,
            aws_secretsmanager_secret.container["admin-internal-jwt"].arn,
            aws_secretsmanager_secret.container["admin-internal-jwt-public"].arn,
          ],
        )
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "database_bootstrap_task" {
  name = "read-bootstrap-database-secrets"
  role = aws_iam_role.workload_task["database-bootstrap"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = concat(
          [
            aws_db_instance.primary.master_user_secret[0].secret_arn,
            aws_db_instance.coordinate_source.master_user_secret[0].secret_arn,
          ],
          [for secret_name in local.database_credential_secret_names : aws_secretsmanager_secret.container[secret_name].arn],
        )
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "runtime_grants_task" {
  name = "read-runtime-grant-database-secrets"
  role = aws_iam_role.workload_task["runtime-grants"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          for secret_name in local.runtime_grants_secret_names :
          aws_secretsmanager_secret.container[secret_name].arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

moved {
  from = aws_iam_role.task_execution
  to   = aws_iam_role.workload_execution["property-api"]
}

moved {
  from = aws_iam_role_policy_attachment.task_execution
  to   = aws_iam_role_policy_attachment.workload_execution["property-api"]
}

moved {
  from = aws_iam_role_policy.task_execution_secrets
  to   = aws_iam_role_policy.workload_execution_secrets["property-api"]
}

moved {
  from = aws_iam_role.runtime_task
  to   = aws_iam_role.workload_task["property-api"]
}

moved {
  from = aws_iam_role.secret_bootstrap_task
  to   = aws_iam_role.workload_task["secret-bootstrap"]
}

moved {
  from = aws_iam_role.database_bootstrap_task
  to   = aws_iam_role.workload_task["database-bootstrap"]
}

moved {
  from = aws_iam_role.backup_task
  to   = aws_iam_role.workload_task["backup"]
}

moved {
  from = aws_iam_role_policy.backup_task
  to   = aws_iam_role_policy.backup_task["backup"]
}

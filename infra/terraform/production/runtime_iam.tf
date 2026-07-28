locals {
  ecs_task_assume_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  workload_secret_names = {
    property-api          = concat(["property-runtime-db"], var.enable_coordinate_source_runtime ? ["coordinate-reader-db"] : [], ["admin-internal-jwt-public", "kakao-local-provider"])
    admin-api             = ["admin-runtime-db", "admin-internal-jwt"]
    user-api              = ["user-runtime-db", "oauth-providers", "user-jwt"]
    public-gateway        = []
    admin-gateway         = []
    ml                    = []
    ai                    = ["ai-runtime", "openai-provider", "user-jwt"]
    chat-bff              = ["user-jwt"]
    user-insight-worker   = ["user-runtime-db"]
    secret-bootstrap      = []
    secret-readiness      = []
    database-bootstrap    = []
    property-flyway       = ["property-migrator-db"]
    admin-migration       = ["admin-migrator-db"]
    user-flyway           = ["user-migrator-db"]
    ai-migration          = ["ai-migrator-db"]
    data-import-reconcile = ["property-migrator-db", "ai-importer-db"]
    source-data-migration = ["coordinate-migrator-db"]
    runtime-grants        = ["property-migrator-db", "admin-migrator-db", "user-migrator-db"]
    property-batch        = concat(["property-runtime-db"], var.enable_coordinate_source_runtime ? ["coordinate-reader-db"] : [], ["public-data-providers"])
    map-marker-projection = ["property-runtime-db"]
    admin-ops             = ["admin-runtime-db"]
    backup                = ["backup-db"]
  }
  amp_remote_write_actions = ["aps:RemoteWrite"]
  secret_bootstrap_actions = ["secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
  secret_readiness_actions = ["secretsmanager:GetSecretValue"]
}

resource "aws_iam_role" "workload_execution" {
  for_each           = local.workload_names
  name               = "${local.name}-${each.key}-execution"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy_attachment" "workload_execution" {
  for_each   = local.workload_names
  role       = aws_iam_role.workload_execution[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "workload_execution_secrets" {
  for_each = {
    for workload, names in local.workload_secret_names : workload => names
    if length(names) > 0
  }
  name = "read-${each.key}-secrets"
  role = aws_iam_role.workload_execution[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [for name in each.value : aws_secretsmanager_secret.container[name].arn]
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
  name               = "${local.name}-${each.key}-task"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy" "amp_remote_write" {
  for_each = local.metric_service_specs
  name     = "remote-write-production-amp"
  role     = aws_iam_role.workload_task[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "RemoteWriteOnly"
      Effect   = "Allow"
      Action   = local.amp_remote_write_actions
      Resource = [aws_prometheus_workspace.this.arn]
    }]
  })
}

resource "aws_iam_role_policy" "runtime_grants" {
  name = "read-runtime-grant-secrets"
  role = aws_iam_role.workload_task["runtime-grants"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [for name in local.workload_secret_names["runtime-grants"] : aws_secretsmanager_secret.container[name].arn]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "database_bootstrap" {
  name = "read-production-database-bootstrap-secrets"
  role = aws_iam_role.workload_task["database-bootstrap"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = concat(
          [for database in aws_db_instance.service : database.master_user_secret[0].secret_arn],
          [for name in [
            "property-runtime-db", "property-ai-reader-db", "admin-runtime-db", "user-runtime-db",
            "coordinate-reader-db", "property-migrator-db", "admin-migrator-db", "user-migrator-db",
            "ai-migrator-db", "ai-importer-db", "ai-runtime-db", "coordinate-migrator-db",
            "coordinate-importer-db", "backup-db",
          ] : aws_secretsmanager_secret.container[name].arn],
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

resource "aws_iam_role_policy" "secret_bootstrap" {
  name = "initialize-production-generated-secrets"
  role = aws_iam_role.workload_task["secret-bootstrap"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = local.secret_bootstrap_actions
        Resource = [for name in local.generated_secret_names : aws_secretsmanager_secret.container[name].arn]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "secret_readiness" {
  name = "verify-production-secret-readiness"
  role = aws_iam_role.workload_task["secret-readiness"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = local.secret_readiness_actions
        Resource = [
          for item in local.readiness_secret_environment : item.value
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

resource "aws_iam_role_policy" "user_insight_msk" {
  name = "consume-home-events"
  role = aws_iam_role.workload_task["user-insight-worker"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
        Resource = [aws_msk_serverless_cluster.events.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["kafka-cluster:ReadData", "kafka-cluster:DescribeTopic"]
        Resource = ["${replace(aws_msk_serverless_cluster.events.arn, ":cluster/", ":topic/")}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
        Resource = ["${replace(aws_msk_serverless_cluster.events.arn, ":cluster/", ":group/")}/*"]
      },
    ]
  })
}

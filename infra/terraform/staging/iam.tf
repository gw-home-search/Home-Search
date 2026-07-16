locals {
  ecs_task_assume_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role" "task_execution" {
  name               = "${local.name}-task-execution"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "staging-secret-materialization"
  role = aws_iam_role.task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = concat(
          values(aws_secretsmanager_secret.container)[*].arn,
          [
            aws_db_instance.primary.master_user_secret[0].secret_arn,
            aws_db_instance.coordinate_source.master_user_secret[0].secret_arn,
          ],
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

resource "aws_iam_role" "runtime_task" {
  name               = "${local.name}-runtime-task"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role" "secret_bootstrap_task" {
  name               = "${local.name}-secret-bootstrap"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy" "secret_bootstrap_task" {
  name = "write-empty-secret-containers"
  role = aws_iam_role.secret_bootstrap_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        Resource = [
          aws_secretsmanager_secret.container["database-runtime"].arn,
          aws_secretsmanager_secret.container["database-bootstrap"].arn,
          aws_secretsmanager_secret.container["user-jwt"].arn,
          aws_secretsmanager_secret.container["admin-internal-jwt"].arn,
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.data.arn]
      },
    ]
  })
}

resource "aws_iam_role" "database_bootstrap_task" {
  name               = "${local.name}-database-bootstrap"
  assume_role_policy = local.ecs_task_assume_policy
}

resource "aws_iam_role_policy" "database_bootstrap_task" {
  name = "read-bootstrap-database-secrets"
  role = aws_iam_role.database_bootstrap_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_db_instance.primary.master_user_secret[0].secret_arn,
          aws_db_instance.coordinate_source.master_user_secret[0].secret_arn,
          aws_secretsmanager_secret.container["database-runtime"].arn,
          aws_secretsmanager_secret.container["database-bootstrap"].arn,
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

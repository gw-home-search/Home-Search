resource "aws_kms_key" "data" {
  description             = "Home Search staging data services"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EnableAccountIamPolicies"
        Effect   = "Allow"
        Action   = "kms:*"
        Resource = "*"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
      },
      {
        Sid    = "AllowCloudWatchLogsEncryption"
        Effect = "Allow"
        Action = [
          "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt",
          "kms:GenerateDataKey*", "kms:ReEncrypt*",
        ]
        Resource = "*"
        Principal = {
          Service = "logs.${var.aws_region}.amazonaws.com"
        }
        Condition = {
          ArnLike = {
            "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/${var.project_name}/staging/*"
          }
        }
      },
    ]
  })
}

resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = values(aws_subnet.data)[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-postgres17"
  family = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "primary" {
  identifier                      = "${local.name}-primary"
  engine                          = "postgres"
  engine_version                  = "17.10"
  instance_class                  = var.rds_instance_class
  db_name                         = "home_search"
  username                        = "cluster_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  port                            = 5432
  allocated_storage               = 20
  max_allocated_storage           = 100
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  db_subnet_group_name            = aws_db_subnet_group.this.name
  parameter_group_name            = aws_db_parameter_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.database_primary.id]
  publicly_accessible             = false
  multi_az                        = false
  backup_retention_period         = 7
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-primary-final"
  copy_tags_to_snapshot           = true
  auto_minor_version_upgrade      = true
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
}

resource "aws_db_instance" "coordinate_source" {
  identifier                      = "${local.name}-coordinate-source"
  engine                          = "postgres"
  engine_version                  = "17.10"
  instance_class                  = var.rds_instance_class
  db_name                         = "home_search_coordinate_source"
  username                        = "coordinate_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  port                            = 5432
  allocated_storage               = 20
  max_allocated_storage           = 100
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  db_subnet_group_name            = aws_db_subnet_group.this.name
  parameter_group_name            = aws_db_parameter_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.database_coordinate.id]
  publicly_accessible             = false
  multi_az                        = false
  backup_retention_period         = 7
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-coordinate-final"
  copy_tags_to_snapshot           = true
  auto_minor_version_upgrade      = true
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = values(aws_subnet.data)[*].id
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = local.name
  description                = "Home Search staging map cache"
  engine                     = "valkey"
  engine_version             = "8.1"
  node_type                  = var.redis_node_type
  port                       = 6379
  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  kms_key_id                 = aws_kms_key.data.arn
  snapshot_retention_limit   = 1
}

resource "aws_secretsmanager_secret" "container" {
  for_each                = local.secret_containers
  name                    = "${local.name}/${each.key}"
  description             = "Value is bootstrapped outside Terraform."
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}

resource "aws_efs_file_system" "ml_model" {
  creation_token   = "${local.name}-ml-model"
  encrypted        = true
  kms_key_id       = aws_kms_key.data.arn
  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"
  lifecycle_policy { transition_to_ia = "AFTER_30_DAYS" }
  tags = { Name = "${local.name}-ml-model" }
}

resource "aws_efs_backup_policy" "ml_model" {
  file_system_id = aws_efs_file_system.ml_model.id
  backup_policy { status = "ENABLED" }
}

resource "aws_efs_mount_target" "ml_model" {
  for_each        = aws_subnet.application
  file_system_id  = aws_efs_file_system.ml_model.id
  subnet_id       = each.value.id
  security_groups = [aws_security_group.efs.id]
}

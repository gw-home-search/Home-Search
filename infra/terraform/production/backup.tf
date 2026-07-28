locals {
  database_backup_resources = [for database in aws_db_instance.service : database.arn]
  database_backup_retention = {
    daily   = 35
    monthly = 365
  }
}

resource "aws_kms_key" "backup_copy" {
  provider                = aws.backup
  description             = "Home Search production cross-region backup copy"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "backup_copy" {
  provider      = aws.backup
  name          = "alias/${local.name}-backup-copy"
  target_key_id = aws_kms_key.backup_copy.key_id
}

resource "aws_backup_vault" "primary" {
  name        = "${local.name}-primary"
  kms_key_arn = aws_kms_key.data.arn
}

resource "aws_backup_vault" "copy" {
  provider    = aws.backup
  name        = "${local.name}-copy"
  kms_key_arn = aws_kms_key.backup_copy.arn
}

resource "aws_backup_vault_lock_configuration" "copy" {
  provider           = aws.backup
  backup_vault_name  = aws_backup_vault.copy.name
  min_retention_days = 35
  max_retention_days = 365
}

resource "aws_backup_plan" "database" {
  name = "${local.name}-database"

  rule {
    rule_name                    = "daily-35-days"
    target_vault_name            = aws_backup_vault.primary.name
    schedule                     = "cron(0 18 * * ? *)"
    schedule_expression_timezone = "Asia/Seoul"
    start_window                 = 60
    completion_window            = 360
    enable_continuous_backup     = true
    lifecycle { delete_after = local.database_backup_retention.daily }
    copy_action {
      destination_vault_arn = aws_backup_vault.copy.arn
      lifecycle { delete_after = local.database_backup_retention.daily }
    }
    recovery_point_tags = {
      BackupTier = "daily"
      Retention  = "35-days"
    }
  }

  rule {
    rule_name                    = "monthly-12-months"
    target_vault_name            = aws_backup_vault.primary.name
    schedule                     = "cron(0 19 1 * ? *)"
    schedule_expression_timezone = "Asia/Seoul"
    start_window                 = 60
    completion_window            = 360
    lifecycle { delete_after = local.database_backup_retention.monthly }
    copy_action {
      destination_vault_arn = aws_backup_vault.copy.arn
      lifecycle { delete_after = local.database_backup_retention.monthly }
    }
    recovery_point_tags = {
      BackupTier = "monthly"
      Retention  = "12-months"
    }
  }
}

resource "aws_iam_role" "backup" {
  name = "${local.name}-backup"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "backup.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "backup_backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_iam_role_policy_attachment" "backup_restore" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores"
}

resource "aws_backup_selection" "database" {
  name         = "${local.name}-database"
  iam_role_arn = aws_iam_role.backup.arn
  plan_id      = aws_backup_plan.database.id
  resources    = local.database_backup_resources
}

resource "aws_backup_restore_testing_plan" "monthly" {
  name                         = replace("${local.name}-monthly-restore", "-", "_")
  schedule_expression          = "cron(0 3 2 * ? *)"
  schedule_expression_timezone = "Asia/Seoul"
  start_window_hours           = 12

  recovery_point_selection {
    algorithm             = "LATEST_WITHIN_WINDOW"
    include_vaults        = [aws_backup_vault.primary.arn]
    recovery_point_types  = ["SNAPSHOT"]
    selection_window_days = 35
  }
}

resource "aws_backup_restore_testing_selection" "database" {
  name                      = replace("${local.name}-database", "-", "_")
  restore_testing_plan_name = aws_backup_restore_testing_plan.monthly.name
  iam_role_arn              = aws_iam_role.backup.arn
  protected_resource_type   = "RDS"
  protected_resource_arns   = local.database_backup_resources
  validation_window_hours   = 24
  restore_metadata_overrides = {
    dbInstanceClass     = var.rds_instance_class
    dbSubnetGroupName   = aws_db_subnet_group.this.name
    deletionProtection  = "false"
    multiAz             = "false"
    publiclyAccessible  = "false"
    vpcSecurityGroupIds = jsonencode([aws_security_group.database.id])
  }
}

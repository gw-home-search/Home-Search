resource "aws_msk_serverless_cluster" "events" {
  cluster_name = "${local.name}-events"
  vpc_config {
    subnet_ids         = values(aws_subnet.application)[*].id
    security_group_ids = [aws_security_group.streaming.id]
  }
  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}

resource "aws_efs_file_system" "ml_model" {
  creation_token   = "${local.name}-ml-model"
  encrypted        = true
  kms_key_id       = aws_kms_key.data.arn
  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"
  lifecycle_policy { transition_to_ia = "AFTER_30_DAYS" }
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

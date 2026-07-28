resource "aws_s3_bucket" "reference_raw" {
  bucket        = "${local.name}-reference-raw-${data.aws_caller_identity.current.account_id}"
  force_destroy = false
}

resource "aws_s3_bucket_ownership_controls" "reference_raw" {
  bucket = aws_s3_bucket.reference_raw.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_public_access_block" "reference_raw" {
  bucket                  = aws_s3_bucket.reference_raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "reference_raw" {
  bucket = aws_s3_bucket.reference_raw.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "reference_raw" {
  bucket = aws_s3_bucket.reference_raw.id
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.data.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_policy" "reference_raw" {
  bucket = aws_s3_bucket.reference_raw.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.reference_raw.arn, "${aws_s3_bucket.reference_raw.arn}/*"]
      Principal = "*"
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_iam_role_policy" "data_import_reconcile" {
  name = "property-reference-data-only-import"
  role = aws_iam_role.workload_task["data-import-reconcile"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListReviewedArtifactPrefix"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = ["arn:aws:s3:::${var.migration_artifact_bucket}"]
        Condition = {
          StringLike = { "s3:prefix" = [var.migration_artifact_prefix, "${var.migration_artifact_prefix}/*"] }
        }
      },
      {
        Sid      = "ReadReviewedArtifact"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = ["arn:aws:s3:::${var.migration_artifact_bucket}/${var.migration_artifact_prefix}/*"]
      },
      {
        Sid      = "RestoreAndVerifyReferenceRaw"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"]
        Resource = ["${aws_s3_bucket.reference_raw.arn}/raw/*"]
      },
      {
        Sid      = "WriteReconciliationEvidence"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = ["${aws_s3_bucket.audit.arn}/deployment-evidence/${var.deployment_release_tag}/data-migration-reconciliation.json"]
      },
      {
        Sid      = "UseMigrationKeys"
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [var.migration_artifact_kms_key_arn, aws_kms_key.data.arn, aws_kms_key.audit.arn]
      },
    ]
  })
}

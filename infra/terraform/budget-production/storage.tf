resource "aws_s3_bucket" "backup" {
  count               = local.foundation_enabled ? 1 : 0
  bucket              = "${local.name}-backup-${data.aws_caller_identity.current.account_id}"
  object_lock_enabled = true
  lifecycle { prevent_destroy = true }
  tags = { Service = "backup", DataClass = "restricted" }
}

resource "aws_s3_bucket" "reference_raw" {
  count  = local.foundation_enabled ? 1 : 0
  bucket = "${local.name}-reference-raw-${data.aws_caller_identity.current.account_id}"
  lifecycle { prevent_destroy = true }
  tags = { Service = "reference-raw", DataClass = "restricted" }
}

locals {
  protected_buckets = local.foundation_enabled ? {
    backup        = aws_s3_bucket.backup[0].id
    reference-raw = aws_s3_bucket.reference_raw[0].id
  } : {}
}

resource "aws_s3_bucket_public_access_block" "protected" {
  for_each                = local.protected_buckets
  bucket                  = each.value
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "protected" {
  for_each = local.protected_buckets
  bucket   = each.value
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "protected" {
  for_each = local.protected_buckets
  bucket   = each.value
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "protected" {
  for_each = local.protected_buckets
  bucket   = each.value
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = "alias/aws/s3"
    }
    bucket_key_enabled = true
  }
}

data "aws_iam_policy_document" "protected_bucket" {
  for_each = local.protected_buckets
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      "arn:aws:s3:::${each.value}",
      "arn:aws:s3:::${each.value}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "protected" {
  for_each = local.protected_buckets
  bucket   = each.value
  policy   = data.aws_iam_policy_document.protected_bucket[each.key].json
}

resource "aws_s3_bucket_object_lock_configuration" "backup" {
  count  = local.foundation_enabled ? 1 : 0
  bucket = aws_s3_bucket.backup[0].id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = 35
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "reference_raw" {
  count  = local.foundation_enabled ? 1 : 0
  bucket = aws_s3_bucket.reference_raw[0].id
  rule {
    id     = "retain-current-expire-noncurrent"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = 90 }
  }
  depends_on = [aws_s3_bucket_versioning.protected]
}

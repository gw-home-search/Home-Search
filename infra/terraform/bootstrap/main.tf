resource "aws_kms_key" "terraform_state" {
  description             = "Home Search Terraform remote state"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  lifecycle { prevent_destroy = true }
}

resource "aws_kms_alias" "terraform_state" {
  name          = "alias/home-search-terraform-state"
  target_key_id = aws_kms_key.terraform_state.key_id
}

resource "aws_s3_bucket" "terraform_state" {
  bucket = var.state_bucket_name
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_ownership_controls" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.terraform_state.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket                  = aws_s3_bucket.terraform_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "terraform_state_bucket" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.terraform_state.arn,
      "${aws_s3_bucket.terraform_state.arn}/*",
    ]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "terraform_state" {
  bucket     = aws_s3_bucket.terraform_state.id
  policy     = data.aws_iam_policy_document.terraform_state_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.terraform_state]
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  github_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_environment]
  }
  github_oidc_string_like = {
    "token.actions.githubusercontent.com:ref" = var.allowed_refs
  }
}

data "aws_iam_policy_document" "github_oidc_trust" {
  statement {
    sid     = "GitHubStagingDeployment"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

resource "aws_iam_role" "github_staging" {
  name                 = "home-search-github-staging"
  assume_role_policy   = data.aws_iam_policy_document.github_oidc_trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "terraform_state_access" {
  statement {
    sid       = "ListStatePrefix"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.terraform_state.arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.state_prefix}/*"]
    }
  }
  statement {
    sid       = "ReadWriteState"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${var.state_prefix}/*"]
  }
  statement {
    sid       = "ManageNativeLockfile"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${var.state_prefix}/*.tflock"]
  }
  statement {
    sid = "UseStateKmsKey"
    actions = [
      "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey",
    ]
    resources = [aws_kms_key.terraform_state.arn]
  }
}

resource "aws_iam_role_policy" "terraform_state_access" {
  name   = "terraform-state-access"
  role   = aws_iam_role.github_staging.id
  policy = data.aws_iam_policy_document.terraform_state_access.json
}

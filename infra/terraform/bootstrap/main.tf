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
  staging_ecs_task_role_names = toset([
    "home-search-staging-task-execution",
    "home-search-staging-admin-api-execution",
    "home-search-staging-user-api-execution",
    "home-search-staging-public-gateway-execution",
    "home-search-staging-admin-gateway-execution",
    "home-search-staging-ml-execution",
    "home-search-staging-secret-bootstrap-execution",
    "home-search-staging-database-bootstrap-execution",
    "home-search-staging-runtime-grants-execution",
    "home-search-staging-property-flyway-execution",
    "home-search-staging-admin-migration-execution",
    "home-search-staging-user-flyway-execution",
    "home-search-staging-source-data-migration-execution",
    "home-search-staging-property-batch-execution",
    "home-search-staging-property-event-relay-execution",
    "home-search-staging-property-event-maintenance-execution",
    "home-search-staging-user-insight-worker-execution",
    "home-search-staging-admin-ops-execution",
    "home-search-staging-backup-execution",
    "home-search-staging-restore-verification-execution",
    "home-search-staging-runtime-task",
    "home-search-staging-admin-api-task",
    "home-search-staging-user-api-task",
    "home-search-staging-public-gateway-task",
    "home-search-staging-admin-gateway-task",
    "home-search-staging-ml-task",
    "home-search-staging-secret-bootstrap",
    "home-search-staging-database-bootstrap",
    "home-search-staging-runtime-grants-task",
    "home-search-staging-property-flyway-task",
    "home-search-staging-admin-migration-task",
    "home-search-staging-user-flyway-task",
    "home-search-staging-source-data-migration-task",
    "home-search-staging-property-batch-task",
    "home-search-staging-property-event-relay-task",
    "home-search-staging-property-event-maintenance-task",
    "home-search-staging-user-insight-worker-task",
    "home-search-staging-admin-ops-task",
    "home-search-staging-backup-task",
    "home-search-staging-restore-verification-task",
  ])
  staging_release_alarm_names = toset([
    "home-search-staging-admin-api-running-task",
    "home-search-staging-admin-gateway-running-task",
    "home-search-staging-ml-running-task",
    "home-search-staging-property-api-running-task",
    "home-search-staging-public-gateway-running-task",
    "home-search-staging-user-api-running-task",
    "home-search-staging-user-insight-worker-running-task",
  ])
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

resource "aws_iam_role_policy" "github_staging_deployment" {
  name = "staging-ecs-release-deployment"
  role = aws_iam_role.github_staging.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadStagingPlanState"
        Effect = "Allow"
        Action = [
          "cloudwatch:Describe*", "cloudwatch:Get*", "cloudwatch:List*",
          "ec2:Describe*", "ecr:Describe*", "ecr:GetLifecyclePolicy",
          "ecs:Describe*", "ecs:List*", "elasticache:Describe*",
          "elasticfilesystem:Describe*", "elasticloadbalancing:Describe*",
          "glue:GetRegistry", "glue:GetTags", "glue:ListRegistries",
          "kafka:DescribeClusterV2", "kafka:GetBootstrapBrokers", "kafka:ListTagsForResource",
          "kms:DescribeKey", "kms:ListAliases", "logs:Describe*", "rds:Describe*",
          "scheduler:GetSchedule", "scheduler:GetScheduleGroup", "scheduler:List*",
          "secretsmanager:DescribeSecret", "servicediscovery:Get*", "servicediscovery:List*",
          "tag:GetResources",
        ]
        Resource = "*"
        Condition = {
          StringEquals = { "aws:RequestedRegion" = var.aws_region }
        }
      },
      {
        Sid      = "RegisterStagingTaskDefinitions"
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = "*"
        Condition = {
          StringEquals = { "aws:RequestedRegion" = var.aws_region }
        }
      },
      {
        Sid    = "RunStagingTaskDefinitions"
        Effect = "Allow"
        Action = ["ecs:RunTask", "ecs:TagResource", "ecs:UntagResource"]
        Resource = [
          "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/home-search-staging-*:*",
        ]
      },
      {
        Sid    = "DeployStagingServices"
        Effect = "Allow"
        Action = ["ecs:CreateService", "ecs:TagResource", "ecs:UntagResource", "ecs:UpdateService"]
        Resource = [
          "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/home-search-staging/*",
        ]
      },
      {
        Sid    = "EnableReviewedStagingSchedules"
        Effect = "Allow"
        Action = ["scheduler:UpdateSchedule"]
        Resource = [
          "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/home-search-staging-database-backup/*",
          "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/home-search-staging-market-news/*",
          "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/home-search-staging-property-event-relay/home-search-staging-property-event-relay",
          "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/home-search-staging-property-event-retention/home-search-staging-property-event-retention",
        ]
      },
      {
        Sid    = "ManageStagingReleaseAlarms"
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricAlarm",
          "cloudwatch:TagResource",
          "cloudwatch:UntagResource",
        ]
        Resource = [
          for alarm_name in local.staging_release_alarm_names :
          "arn:aws:cloudwatch:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alarm:${alarm_name}"
        ]
      },
      {
        Sid    = "PassStagingEcsTaskRolesOnly"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          for role_name in local.staging_ecs_task_role_names :
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${role_name}"
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      {
        Sid    = "PassStagingSchedulerRoleOnly"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-staging-backup-scheduler",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-staging-market-news-scheduler",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-staging-property-event-relay-scheduler",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-staging-property-event-retention-scheduler",
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "scheduler.amazonaws.com" }
        }
      },
      {
        Sid      = "ReadStagingTaskRoles"
        Effect   = "Allow"
        Action   = ["iam:GetRole", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListRolePolicies"]
        Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-staging-*"
      },
      {
        Sid      = "ReadStagingBackupBucketConfiguration"
        Effect   = "Allow"
        Action   = ["s3:Get*", "s3:ListBucket"]
        Resource = "arn:aws:s3:::home-search-staging-database-backup-${data.aws_caller_identity.current.account_id}"
      },
    ]
  })
}

locals {
  github_release_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_release_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_release_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_release_environment]
  }
  github_release_oidc_string_like = {
    "token.actions.githubusercontent.com:ref" = ["refs/tags/v*"]
  }
}

data "aws_iam_policy_document" "github_release_oidc_trust" {
  statement {
    sid     = "GitHubReleaseImagePublishing"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_release_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_release_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

resource "aws_iam_role" "github_release" {
  name                 = "home-search-github-release"
  assume_role_policy   = data.aws_iam_policy_document.github_release_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "github_release" {
  name = "publish-home-search-images"
  role = aws_iam_role.github_release.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload",
          "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload",
          "ecr:PutImage", "ecr:UploadLayerPart",
        ]
        Resource = "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/home-search/*"
      },
    ]
  })
}

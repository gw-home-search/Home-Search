locals {
  github_staging_foundation_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_staging_foundation_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_environment]
  }
  github_staging_foundation_oidc_string_like = {
    "token.actions.githubusercontent.com:ref" = var.github_staging_foundation_allowed_refs
  }
  staging_foundation_plan_actions = [
    "cloudwatch:Describe*", "cloudwatch:Get*", "cloudwatch:List*", "ec2:Describe*",
    "ecr:Describe*", "ecr:GetLifecyclePolicy", "ecr:GetRepositoryPolicy", "ecr:ListTagsForResource",
    "ecs:Describe*", "ecs:List*", "elasticache:Describe*", "elasticache:ListTagsForResource",
    "elasticfilesystem:Describe*", "elasticloadbalancing:Describe*", "glue:GetRegistry",
    "glue:GetTags", "glue:ListRegistries", "iam:GetPolicy", "iam:GetPolicyVersion",
    "iam:GetRole", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListRolePolicies",
    "iam:ListRoleTags", "kafka:DescribeClusterV2",
    "kafka:GetBootstrapBrokers", "kafka:ListTagsForResource", "kms:DescribeKey",
    "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListAliases", "kms:ListResourceTags",
    "logs:Describe*", "logs:ListTagsForResource", "rds:Describe*", "rds:ListTagsForResource",
    "s3:GetBucket*", "s3:GetEncryptionConfiguration", "s3:GetLifecycleConfiguration", "s3:ListBucket",
    "scheduler:GetSchedule", "scheduler:GetScheduleGroup", "scheduler:List*",
    "secretsmanager:DescribeSecret", "secretsmanager:GetResourcePolicy", "secretsmanager:ListSecrets",
    "secretsmanager:ListSecretVersionIds", "servicediscovery:Get*", "servicediscovery:List*",
    "sns:Get*", "sns:List*", "sqs:GetQueueAttributes", "sqs:List*", "tag:GetResources",
  ]
  staging_foundation_apply_actions = [
    "acm:DescribeCertificate", "cloudwatch:*", "ec2:*", "ecr:*", "ecs:*", "elasticache:*", "elasticfilesystem:*",
    "elasticloadbalancing:*", "glue:*", "kafka:*", "logs:*", "rds:*", "scheduler:*",
    "route53:CreateHostedZone", "servicediscovery:*", "sns:*", "sqs:*",
    "kms:CreateAlias", "kms:CreateGrant", "kms:CreateKey", "kms:DescribeKey",
    "kms:EnableKeyRotation", "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListAliases",
    "kms:ListResourceTags", "kms:PutKeyPolicy", "kms:RetireGrant", "kms:RevokeGrant",
    "kms:TagResource", "kms:UntagResource", "kms:UpdateAlias", "kms:UpdateKeyDescription",
  ]
  staging_foundation_kms_data_actions = [
    "kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:ReEncrypt*",
  ]
  staging_foundation_explicit_deny_actions = [
    "ec2:DeleteNatGateway", "ec2:DeleteSubnet", "ec2:DeleteVpc", "ec2:DeleteVpcEndpoints",
    "ecr:DeleteRepository", "ecs:DeleteCluster", "ecs:DeleteService",
    "elasticache:DeleteReplicationGroup", "elasticfilesystem:DeleteFileSystem",
    "elasticloadbalancing:DeleteLoadBalancer", "kafka:DeleteCluster", "kms:DisableKey",
    "kms:ScheduleKeyDeletion", "logs:DeleteLogGroup", "rds:DeleteDBInstance",
    "route53:DeleteHostedZone",
    "s3:DeleteBucket", "secretsmanager:DeleteSecret", "servicediscovery:DeleteNamespace",
    "sns:DeleteTopic", "sqs:DeleteQueue",
  ]
  staging_foundation_state_object_actions = {
    plan  = ["s3:GetObject"]
    apply = ["s3:GetObject", "s3:PutObject"]
  }
  staging_foundation_state_role_ids = {
    plan  = aws_iam_role.github_staging_foundation_plan.id
    apply = aws_iam_role.github_staging_foundation_apply.id
  }
  staging_foundation_workload_role_names = setunion(local.staging_ecs_task_role_names, toset([
    "home-search-staging-backup-scheduler",
    "home-search-staging-market-news-scheduler",
    "home-search-staging-property-event-relay-scheduler",
    "home-search-staging-property-event-retention-scheduler",
  ]))
}

data "aws_iam_policy_document" "github_staging_foundation_oidc_trust" {
  statement {
    sid     = "GitHubStagingFoundation"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_staging_foundation_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_staging_foundation_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

resource "aws_iam_role" "github_staging_foundation_plan" {
  name                 = "home-search-github-staging-foundation-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_staging_foundation_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "github_staging_foundation_apply" {
  name                 = "home-search-github-staging-foundation-apply"
  assume_role_policy   = data.aws_iam_policy_document.github_staging_foundation_oidc_trust.json
  max_session_duration = 7200
}

resource "aws_iam_role_policy" "github_staging_foundation_plan_read" {
  name = "staging-foundation-read-only-plan"
  role = aws_iam_role.github_staging_foundation_plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ReadStagingFoundationMetadata", Effect = "Allow"
      Action = local.staging_foundation_plan_actions, Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy" "github_staging_foundation_state" {
  for_each = local.staging_foundation_state_role_ids
  name     = "staging-foundation-terraform-state-${each.key}"
  role     = each.value
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.terraform_state.arn]
        Condition = { StringLike = { "s3:prefix" = ["${var.staging_state_key}*"] } }
      },
      {
        Effect   = "Allow", Action = local.staging_foundation_state_object_actions[each.key]
        Resource = ["${aws_s3_bucket.terraform_state.arn}/${var.staging_state_key}"]
      },
      {
        Effect   = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = ["${aws_s3_bucket.terraform_state.arn}/${var.staging_state_key}.tflock"]
      },
      {
        Effect   = "Allow", Action = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.terraform_state.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "github_staging_foundation_apply" {
  name = "staging-reviewed-foundation-apply"
  role = aws_iam_role.github_staging_foundation_apply.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ManageStagingFoundation", Effect = "Allow"
        Action = local.staging_foundation_apply_actions, Resource = "*"
      },
      {
        Sid    = "UseStagingDataKeys", Effect = "Allow"
        Action = local.staging_foundation_kms_data_actions
        Resource = [
          "arn:aws:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:key/*",
        ]
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Environment" = "staging"
            "aws:ResourceTag/Project"     = "home-search"
          }
        }
      },
      {
        Sid = "ManageStagingBackupBucket", Effect = "Allow", Action = ["s3:*"]
        Resource = [
          "arn:aws:s3:::home-search-staging-database-backup-${data.aws_caller_identity.current.account_id}",
          "arn:aws:s3:::home-search-staging-database-backup-${data.aws_caller_identity.current.account_id}/*",
        ]
      },
      {
        Sid = "ListSecretsForStagingRefresh", Effect = "Allow", Action = ["secretsmanager:ListSecrets"], Resource = "*"
      },
      {
        Sid = "ManageStagingSecretContainers", Effect = "Allow"
        Action = [
          "secretsmanager:CreateSecret", "secretsmanager:DescribeSecret", "secretsmanager:GetResourcePolicy",
          "secretsmanager:ListSecretVersionIds", "secretsmanager:PutResourcePolicy",
          "secretsmanager:TagResource", "secretsmanager:UntagResource", "secretsmanager:UpdateSecret",
        ]
        Resource = [
          "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:home-search-staging/*",
        ]
      },
      {
        Sid    = "ManageStagingWorkloadRoles", Effect = "Allow"
        Action = ["iam:CreateRole", "iam:Get*", "iam:List*", "iam:PassRole", "iam:PutRolePolicy", "iam:TagRole", "iam:UntagRole", "iam:UpdateAssumeRolePolicy"]
        Resource = [
          for role_name in local.staging_foundation_workload_role_names :
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${role_name}"
        ]
      },
      {
        Sid = "AttachApprovedEcsExecutionPolicy", Effect = "Allow", Action = ["iam:AttachRolePolicy"]
        Resource = [
          for role_name in local.staging_ecs_task_role_names :
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${role_name}"
        ]
        Condition = {
          ArnEquals = { "iam:PolicyARN" = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy" }
        }
      },
      {
        Sid       = "CreateRequiredServiceLinkedRoles", Effect = "Allow", Action = ["iam:CreateServiceLinkedRole"], Resource = "*"
        Condition = { StringLike = { "iam:AWSServiceName" = ["*.amazonaws.com"] } }
      },
      {
        Sid    = "DenyStagingFoundationDestruction", Effect = "Deny"
        Action = local.staging_foundation_explicit_deny_actions, Resource = "*"
      },
    ]
  })
}

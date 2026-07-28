locals {
  github_production_plan_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_production_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_production_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_production_environment]
  }
  github_production_deploy_oidc_string_equals = merge(local.github_production_plan_oidc_string_equals, {
    "token.actions.githubusercontent.com:workflow" = var.github_production_deploy_workflow_names
  })
  github_production_oidc_string_like = {
    "token.actions.githubusercontent.com:ref" = var.github_production_allowed_refs
  }
  production_apply_actions = [
    "acm:Describe*", "acm:List*", "application-autoscaling:*", "aps:*", "backup:*",
    "budgets:*", "ce:*", "cloudtrail:*", "config:*", "ec2:*", "ecs:*", "elasticache:*",
    "elasticloadbalancing:*", "events:*", "grafana:*", "guardduty:*", "kafka:*", "kms:*",
    "logs:*", "rds:*", "route53:*", "s3:*", "scheduler:*", "secretsmanager:*",
    "servicediscovery:*", "sns:*", "wafv2:*",
  ]
  production_apply_explicit_deny_actions = [
    "aps:DeleteWorkspace", "backup:DeleteBackupVault", "cloudtrail:DeleteTrail", "config:DeleteConfigurationRecorder",
    "ec2:DeleteNatGateway", "ec2:DeleteSubnet", "ec2:DeleteVpc", "ec2:DeleteVpcEndpoints",
    "ecs:DeleteCluster", "ecs:DeleteService", "elasticache:DeleteReplicationGroup",
    "elasticloadbalancing:DeleteLoadBalancer", "grafana:DeleteWorkspace", "guardduty:DeleteDetector",
    "kafka:DeleteCluster", "kms:DisableKey", "kms:ScheduleKeyDeletion", "logs:DeleteLogGroup",
    "rds:DeleteDBCluster", "rds:DeleteDBInstance", "route53:DeleteHostedZone", "s3:DeleteBucket",
    "secretsmanager:DeleteSecret", "servicediscovery:DeleteNamespace", "wafv2:DeleteWebACL",
  ]
  production_deploy_actions = [
    "cloudwatch:DescribeAlarms", "ecs:DescribeServices", "ecs:DescribeTaskDefinition",
    "ecs:ListServices", "ecs:RegisterTaskDefinition", "ecs:UpdateService",
  ]
  production_state_object_actions = {
    plan  = ["s3:GetObject"]
    apply = ["s3:GetObject", "s3:PutObject"]
  }
  production_state_kms_actions = {
    plan  = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey"]
    apply = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey"]
  }
}

data "aws_iam_policy_document" "github_production_plan_oidc_trust" {
  statement {
    sid     = "GitHubProductionPlan"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_production_plan_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_production_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

data "aws_iam_policy_document" "github_production_deploy_oidc_trust" {
  statement {
    sid     = "GitHubProductionRollback"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_production_deploy_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_production_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

resource "aws_iam_role" "github_production_plan" {
  name                 = "home-search-github-production-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_production_plan_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "github_production_apply" {
  name                 = "home-search-github-production-apply"
  assume_role_policy   = data.aws_iam_policy_document.github_production_plan_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "github_production_deploy" {
  name                 = "home-search-github-production-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_production_deploy_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "github_production_plan_read_only" {
  role       = aws_iam_role.github_production_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

locals {
  production_state_role_ids = {
    plan  = aws_iam_role.github_production_plan.id
    apply = aws_iam_role.github_production_apply.id
  }
}

resource "aws_iam_role_policy" "github_production_state" {
  for_each = local.production_state_role_ids
  name     = "production-terraform-state-${each.key}"
  role     = each.value
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.terraform_state.arn]
        Condition = { StringLike = { "s3:prefix" = ["home-search/production/terraform.tfstate*"] } }
      },
      {
        Effect   = "Allow", Action = local.production_state_object_actions[each.key],
        Resource = ["${aws_s3_bucket.terraform_state.arn}/home-search/production/terraform.tfstate"]
      },
      {
        Effect   = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
        Resource = ["${aws_s3_bucket.terraform_state.arn}/home-search/production/terraform.tfstate.tflock"]
      },
      {
        Effect   = "Allow", Action = local.production_state_kms_actions[each.key],
        Resource = [aws_kms_key.terraform_state.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "github_production_apply" {
  name = "production-reviewed-terraform-apply"
  role = aws_iam_role.github_production_apply.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ManageProductionServices", Effect = "Allow", Action = local.production_apply_actions, Resource = "*" },
      {
        Sid    = "ManageProductionWorkloadRoles", Effect = "Allow",
        Action = ["iam:AttachRolePolicy", "iam:CreatePolicy", "iam:CreateRole", "iam:DeleteRolePolicy", "iam:DetachRolePolicy", "iam:Get*", "iam:List*", "iam:PassRole", "iam:PutRolePolicy", "iam:Tag*", "iam:Untag*", "iam:UpdateAssumeRolePolicy"],
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-production-*",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/home-search-production-*",
        ]
      },
      {
        Sid       = "CreateRequiredServiceLinkedRoles", Effect = "Allow", Action = ["iam:CreateServiceLinkedRole"], Resource = "*",
        Condition = { StringLike = { "iam:AWSServiceName" = ["*.amazonaws.com"] } }
      },
      { Sid = "DenyProductionDestruction", Effect = "Deny", Action = local.production_apply_explicit_deny_actions, Resource = "*" },
    ]
  })
}

resource "aws_iam_role_policy" "github_production_deploy" {
  name = "rollback-existing-production-ecs-services"
  role = aws_iam_role.github_production_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = local.production_deploy_actions
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-production-ai-execution",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-production-ai-task",
        ]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
    ]
  })
}

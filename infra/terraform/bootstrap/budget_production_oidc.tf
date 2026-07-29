locals {
  github_budget_plan_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_budget_plan_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_budget_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_budget_plan_environment]
  }
  github_budget_apply_oidc_string_equals = {
    "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
    "token.actions.githubusercontent.com:sub"         = ["repo:${var.github_repository}:environment:${var.github_budget_apply_environment}"]
    "token.actions.githubusercontent.com:repository"  = [var.github_repository]
    "token.actions.githubusercontent.com:workflow"    = [var.github_budget_workflow_name]
    "token.actions.githubusercontent.com:environment" = [var.github_budget_apply_environment]
  }
  github_budget_oidc_string_like = {
    "token.actions.githubusercontent.com:ref" = var.github_budget_allowed_refs
  }
  budget_state_object_actions = {
    plan  = ["s3:GetObject"]
    apply = ["s3:GetObject", "s3:PutObject"]
  }
  budget_state_lock_actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
  budget_forbidden_state_keys = [
    var.state_key,
    var.staging_state_key,
    "home-search/production/terraform.tfstate",
  ]
  budget_read_actions = [
    "acm:Describe*", "acm:List*", "budgets:Describe*", "ce:Get*", "ce:List*",
    "cloudwatch:Describe*", "cloudwatch:Get*", "cloudwatch:List*", "dlm:Get*", "dlm:List*",
    "ec2:Describe*", "ecr:Describe*", "ecr:GetLifecyclePolicy", "ecr:GetRepositoryPolicy",
    "ecr:ListTagsForResource", "ecs:Describe*", "ecs:List*", "events:Describe*", "events:List*",
    "iam:Get*", "iam:List*", "logs:Describe*", "logs:List*", "route53:Get*", "route53:List*",
    "s3:GetBucket*", "s3:GetEncryptionConfiguration", "s3:GetLifecycleConfiguration", "s3:ListBucket",
    "sns:Get*", "sns:List*", "ssm:Describe*", "ssm:GetDocument", "ssm:List*", "tag:GetResources",
  ]
  budget_apply_actions = [
    "acm:AddTagsToCertificate", "acm:DeleteCertificate", "acm:RemoveTagsFromCertificate", "acm:RequestCertificate",
    "budgets:CreateBudget", "budgets:DeleteBudget", "budgets:ModifyBudget",
    "ce:CreateAnomalyMonitor", "ce:CreateAnomalySubscription", "ce:DeleteAnomalyMonitor", "ce:DeleteAnomalySubscription", "ce:UpdateAnomalyMonitor", "ce:UpdateAnomalySubscription",
    "cloudwatch:DeleteAlarms", "cloudwatch:PutMetricAlarm", "cloudwatch:PutMetricData", "cloudwatch:TagResource", "cloudwatch:UntagResource",
    "dlm:CreateLifecyclePolicy", "dlm:DeleteLifecyclePolicy", "dlm:TagResource", "dlm:UntagResource", "dlm:UpdateLifecyclePolicy",
    "ec2:AllocateAddress", "ec2:AssociateAddress", "ec2:AssociateIamInstanceProfile", "ec2:AttachVolume", "ec2:AuthorizeSecurityGroupIngress",
    "ec2:CreateInternetGateway", "ec2:CreateRoute", "ec2:CreateRouteTable", "ec2:CreateSecurityGroup", "ec2:CreateSubnet", "ec2:CreateTags", "ec2:CreateVolume", "ec2:CreateVpc",
    "ec2:DeleteInternetGateway", "ec2:DeleteRoute", "ec2:DeleteRouteTable", "ec2:DeleteSecurityGroup", "ec2:DeleteSubnet", "ec2:DeleteTags", "ec2:DeleteVolume", "ec2:DeleteVpc",
    "ec2:DetachVolume", "ec2:DisassociateAddress", "ec2:ModifyInstanceAttribute", "ec2:ModifySubnetAttribute", "ec2:ModifyVolume", "ec2:ModifyVpcAttribute", "ec2:ReleaseAddress",
    "ec2:ReplaceIamInstanceProfileAssociation", "ec2:RevokeSecurityGroupIngress", "ec2:RunInstances", "ec2:StartInstances", "ec2:StopInstances", "ec2:TerminateInstances",
    "ecr:CreateRepository", "ecr:DeleteLifecyclePolicy", "ecr:DeleteRepository", "ecr:PutImageScanningConfiguration", "ecr:PutImageTagMutability", "ecr:PutLifecyclePolicy", "ecr:SetRepositoryPolicy", "ecr:TagResource", "ecr:UntagResource",
    "ecs:CreateCluster", "ecs:CreateService", "ecs:DeleteCluster", "ecs:DeleteService", "ecs:DeregisterTaskDefinition", "ecs:RegisterTaskDefinition", "ecs:TagResource", "ecs:UntagResource", "ecs:UpdateCluster", "ecs:UpdateService",
    "events:DeleteRule", "events:PutRule", "events:PutTargets", "events:RemoveTargets", "events:TagResource", "events:UntagResource",
    "logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy", "logs:TagResource", "logs:UntagResource",
    "route53:ChangeResourceRecordSets", "s3:CreateBucket", "s3:DeleteBucket", "s3:DeleteBucketPolicy", "s3:PutBucketLifecycleConfiguration", "s3:PutBucketOwnershipControls", "s3:PutBucketPolicy", "s3:PutBucketPublicAccessBlock", "s3:PutBucketVersioning", "s3:PutEncryptionConfiguration", "s3:PutObject", "s3:DeleteObject", "s3:PutObjectRetention",
    "sns:CreateTopic", "sns:DeleteTopic", "sns:SetTopicAttributes", "sns:Subscribe", "sns:TagResource", "sns:Unsubscribe", "sns:UntagResource",
    "ssm:AddTagsToResource", "ssm:CreateAssociation", "ssm:CreateDocument", "ssm:DeleteAssociation", "ssm:DeleteDocument", "ssm:DeleteParameter", "ssm:PutParameter", "ssm:RemoveTagsFromResource", "ssm:UpdateAssociation", "ssm:UpdateDocument",
  ]
  budget_apply_explicit_deny_actions = [
    "ec2:DeleteVolume", "ec2:DetachVolume", "s3:DeleteBucket", "s3:DeleteObject", "ssm:DeleteParameter",
  ]
  budget_deploy_actions = [
    "cloudwatch:DescribeAlarms", "ec2:AttachVolume", "ec2:CreateTags", "ec2:CreateVolume", "ec2:DescribeImages", "ec2:DescribeInstances", "ec2:DescribeInstanceCreditSpecifications", "ec2:DescribeInstanceStatus",
    "ec2:DescribeSnapshots", "ec2:DescribeSubnets", "ec2:DescribeTags", "ec2:DescribeVolumes", "ec2:ModifyInstanceCreditSpecification", "ec2:RunInstances", "ec2:TerminateInstances",
    "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecs:DescribeServices", "ecs:DescribeTaskDefinition",
    "ecs:ListTasks", "ecs:RegisterTaskDefinition", "ecs:RunTask", "ecs:StopTask", "ecs:UpdateService",
    "iam:PassRole", "s3:GetObject", "s3:PutObject", "ssm:DescribeInstanceInformation", "ssm:GetCommandInvocation",
    "ssm:ListCommandInvocations", "ssm:SendCommand",
  ]
}

data "aws_iam_policy_document" "github_budget_plan_oidc_trust" {
  statement {
    sid     = "GitHubBudgetProductionPlan"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_budget_plan_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_budget_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

data "aws_iam_policy_document" "github_budget_apply_oidc_trust" {
  statement {
    sid     = "GitHubBudgetProductionApply"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    dynamic "condition" {
      for_each = local.github_budget_apply_oidc_string_equals
      content {
        test     = "StringEquals"
        variable = condition.key
        values   = condition.value
      }
    }
    dynamic "condition" {
      for_each = local.github_budget_oidc_string_like
      content {
        test     = "StringLike"
        variable = condition.key
        values   = condition.value
      }
    }
  }
}

resource "aws_iam_role" "github_budget_production_plan" {
  name                 = "home-search-github-budget-production-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_budget_plan_oidc_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "github_budget_production_apply" {
  name                 = "home-search-github-budget-production-apply"
  assume_role_policy   = data.aws_iam_policy_document.github_budget_apply_oidc_trust.json
  max_session_duration = 7200
}

resource "aws_iam_role" "github_budget_production_deploy" {
  name                 = "home-search-github-budget-production-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_budget_apply_oidc_trust.json
  max_session_duration = 43200
}

locals {
  budget_state_role_ids = {
    plan  = aws_iam_role.github_budget_production_plan.id
    apply = aws_iam_role.github_budget_production_apply.id
  }
}

resource "aws_iam_role_policy" "github_budget_state" {
  for_each = local.budget_state_role_ids
  name     = "budget-production-terraform-state-${each.key}"
  role     = each.value
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "ListBudgetState", Effect = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.terraform_state.arn]
        Condition = { StringLike = { "s3:prefix" = ["${var.budget_production_state_key}*"] } }
      },
      {
        Sid      = "BudgetStateObject", Effect = "Allow", Action = local.budget_state_object_actions[each.key]
        Resource = ["${aws_s3_bucket.terraform_state.arn}/${var.budget_production_state_key}"]
      },
      {
        Sid      = "BudgetStateLock", Effect = "Allow", Action = local.budget_state_lock_actions
        Resource = ["${aws_s3_bucket.terraform_state.arn}/${var.budget_production_state_key}.tflock"]
      },
      {
        Sid       = "StateKmsThroughS3Only", Effect = "Allow"
        Action    = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey"]
        Resource  = [aws_kms_key.terraform_state.arn]
        Condition = { StringEquals = { "kms:ViaService" = "s3.${var.aws_region}.amazonaws.com" } }
      },
      {
        Sid = "DenyOtherEnvironmentStates", Effect = "Deny", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = flatten([
          for key in local.budget_forbidden_state_keys : [
            "${aws_s3_bucket.terraform_state.arn}/${key}",
            "${aws_s3_bucket.terraform_state.arn}/${key}.tflock",
          ]
        ])
      },
    ]
  })
}

resource "aws_iam_role_policy" "github_budget_plan" {
  name = "budget-production-read-only-plan"
  role = aws_iam_role.github_budget_production_plan.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Sid = "ReadBudgetMetadata", Effect = "Allow", Action = local.budget_read_actions, Resource = "*" }]
  })
}

resource "aws_iam_role_policy" "github_budget_apply" {
  name = "budget-production-reviewed-apply"
  role = aws_iam_role.github_budget_production_apply.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ReadBudgetMetadata", Effect = "Allow", Action = local.budget_read_actions, Resource = "*" },
      {
        Sid       = "ManageTaggedBudgetResources", Effect = "Allow", Action = local.budget_apply_actions, Resource = "*"
        Condition = { StringEqualsIfExists = { "aws:RequestedRegion" = var.aws_region } }
      },
      {
        Sid    = "ManageBudgetRoles", Effect = "Allow"
        Action = ["iam:AttachRolePolicy", "iam:CreateInstanceProfile", "iam:CreateRole", "iam:DeleteInstanceProfile", "iam:DeleteRole", "iam:DeleteRolePolicy", "iam:DetachRolePolicy", "iam:PassRole", "iam:PutRolePolicy", "iam:RemoveRoleFromInstanceProfile", "iam:AddRoleToInstanceProfile", "iam:TagRole", "iam:UntagRole"]
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/home-search-budget-production-*",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:instance-profile/home-search-budget-production-*",
        ]
      },
      { Sid = "DenyBudgetDataDeletion", Effect = "Deny", Action = local.budget_apply_explicit_deny_actions, Resource = "*" },
    ]
  })
}

resource "aws_iam_role_policy" "github_budget_deploy" {
  name = "budget-production-reviewed-deploy"
  role = aws_iam_role.github_budget_production_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "OperateBudgetRuntime", Effect = "Allow", Action = local.budget_deploy_actions, Resource = "*"
        Condition = { StringEqualsIfExists = { "aws:RequestedRegion" = var.aws_region } }
      },
      {
        Sid      = "DeleteTaggedRecoveryClone"
        Effect   = "Allow"
        Action   = ["ec2:DeleteVolume"]
        Resource = ["arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:volume/*"]
        Condition = {
          StringEquals = { "ec2:ResourceTag/Purpose" = "budget-production-recovery-clone" }
        }
      },
      {
        Sid      = "DenyTerraformState", Effect = "Deny", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = ["${aws_s3_bucket.terraform_state.arn}/${var.budget_production_state_key}*"]
      },
    ]
  })
}

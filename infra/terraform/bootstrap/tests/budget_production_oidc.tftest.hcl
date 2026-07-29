mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

override_resource {
  target          = aws_kms_key.terraform_state
  override_during = plan
  values = {
    arn = "arn:aws:kms:ap-northeast-2:123456789012:key/11111111-1111-1111-1111-111111111111"
  }
}

override_resource {
  target          = aws_s3_bucket.terraform_state
  override_during = plan
  values = {
    arn = "arn:aws:s3:::home-search-state-fixture"
  }
}

run "budget_roles_are_separated_and_state_isolated" {
  command = plan
  variables {
    state_bucket_name                = "home-search-state-fixture"
    github_repository                = "example/home-search"
    budget_production_hosted_zone_id = "Z0123456789ABCDEFG"
  }

  assert {
    condition = (
      aws_iam_role.github_budget_production_plan.name == "home-search-github-budget-production-plan"
      && aws_iam_role.github_budget_production_apply.name == "home-search-github-budget-production-apply"
      && aws_iam_role.github_budget_production_deploy.name == "home-search-github-budget-production-deploy"
    )
    error_message = "Budget plan, apply, and deploy must use distinct OIDC roles."
  }

  assert {
    condition = (
      var.budget_production_state_key == "home-search/budget-production/terraform.tfstate"
      && !contains(local.budget_state_object_actions.plan, "s3:PutObject")
      && contains(local.budget_state_object_actions.apply, "s3:PutObject")
      && contains(local.budget_state_lock_actions, "s3:DeleteObject")
      && contains(local.budget_forbidden_state_keys, "home-search/production/terraform.tfstate")
      && contains(local.budget_forbidden_state_keys, "home-search/staging/terraform.tfstate")
      && contains(local.budget_forbidden_state_keys, "home-search/bootstrap/terraform.tfstate")
      && !contains(local.budget_apply_actions, "s3:PutBucketPolicy")
      && !contains(local.budget_apply_actions, "s3:PutObject")
    )
    error_message = "Budget roles must access only the budget state object and native lockfile."
  }

  assert {
    condition = (
      contains(local.budget_state_lock_actions, "s3:DeleteObject")
      && !contains(local.budget_apply_explicit_deny_actions, "s3:DeleteObject")
      && !contains(local.budget_apply_actions, "s3:DeleteObject")
    )
    error_message = "Budget apply must be able to release its exact native state lock without granting broad state deletion."
  }

  assert {
    condition = (
      one(local.github_budget_plan_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "budget-production-plan"
      && one(local.github_budget_apply_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "budget-production"
      && one(local.github_budget_plan_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == "Deploy budget production"
    )
    error_message = "Budget OIDC trust must bind the exact plan/apply environments and workflow."
  }

  assert {
    condition = (
      !contains(local.budget_read_actions, "ssm:GetParameter")
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_plan.policy).Statement :
        statement.Sid == "ReadPublicEcsOptimizedAmi"
        && statement.Action == ["ssm:GetParameter"]
        && statement.Resource == ["arn:aws:ssm:ap-northeast-2::parameter/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"]
      ])
    )
    error_message = "Budget plan must include provider refresh reads while keeping SSM GetParameter scoped to the exact public AMI parameter."
  }

  assert {
    condition = alltrue([
      for policy in [
        aws_iam_role_policy.github_budget_plan.policy,
        aws_iam_role_policy.github_budget_apply.policy,
        ] : anytrue([
          for statement in jsondecode(policy).Statement :
          statement.Sid == "ReadBudgetBucketProviderMetadata"
          && statement.Action == ["s3:GetAccelerateConfiguration"]
          && statement.Resource == [
            "arn:aws:s3:::home-search-budget-production-backup-123456789012",
            "arn:aws:s3:::home-search-budget-production-reference-raw-123456789012",
          ]
      ])
    ])
    error_message = "Plan/apply must scope the provider-required accelerate metadata read to the two budget-production buckets."
  }

  assert {
    condition = alltrue([
      for policy in [
        aws_iam_role_policy.github_budget_plan.policy,
        aws_iam_role_policy.github_budget_apply.policy,
        ] : anytrue([
          for statement in jsondecode(policy).Statement :
          statement.Sid == "ReadBudgetSecretContainersForProviderRefresh"
          && statement.Action == ["ssm:GetParameter"]
          && statement.Resource == ["arn:aws:ssm:ap-northeast-2:123456789012:parameter/home-search/budget-production/*"]
      ])
    ])
    error_message = "Plan/apply must scope the provider-required SecureString refresh to the budget-production parameter prefix."
  }

  assert {
    condition = (
      alltrue([
        for action in [
          "ec2:AssociateRouteTable",
          "ec2:AttachInternetGateway",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:DetachInternetGateway",
          "ec2:DisassociateRouteTable",
          "ec2:RevokeSecurityGroupEgress",
          "logs:DeleteMetricFilter",
          "logs:PutMetricFilter",
        ] : contains(local.budget_apply_actions, action)
      ])
      && alltrue([
        for action in [
          "budgets:CreateBudget",
          "budgets:DeleteBudget",
          "budgets:ModifyBudget",
          "ce:CreateAnomalyMonitor",
          "ce:CreateAnomalySubscription",
          "ce:DeleteAnomalyMonitor",
          "ce:DeleteAnomalySubscription",
          "ce:UpdateAnomalyMonitor",
          "ce:UpdateAnomalySubscription",
          "route53:ChangeResourceRecordSets",
        ] : !contains(local.budget_apply_actions, action)
      ])
    )
    error_message = "Regional mutations must include provider CRUD actions while global services remain outside the RequestedRegion-gated action set."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetCostControls"
        && contains(statement.Action, "budgets:ModifyBudget")
        && statement.Resource == ["arn:aws:budgets::123456789012:budget/home-search-budget-production-monthly"]
        && !contains(keys(statement), "Condition")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "CreateBudgetCostAnomalyControls"
        && contains(statement.Action, "ce:CreateAnomalyMonitor")
        && statement.Resource == "*"
        && statement.Condition.StringEquals["aws:RequestTag/Environment"] == "budget-production"
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetHostedZoneRecords"
        && statement.Action == ["route53:ChangeResourceRecordSets"]
        && statement.Resource == ["arn:aws:route53:::hostedzone/Z0123456789ABCDEFG"]
        && !contains(keys(statement), "Condition")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "CreateBudgetsServiceLinkedRole"
        && statement.Action == ["iam:CreateServiceLinkedRole"]
        && statement.Resource == ["arn:aws:iam::123456789012:role/aws-service-role/budgets.amazonaws.com/AWSServiceRoleForBudgets"]
        && statement.Condition.StringEquals["iam:AWSServiceName"] == "budgets.amazonaws.com"
      ])
    )
    error_message = "Budgets, its exact service-linked role, Cost Explorer, and Route53 must use explicit global-service statements without a regional condition."
  }

  assert {
    condition = (
      anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetRoles"
        && contains(statement.Action, "iam:TagInstanceProfile")
        && contains(statement.Action, "iam:UntagInstanceProfile")
      ])
      && anytrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid == "ManageBudgetBucketsOnly"
        && contains(statement.Action, "s3:PutBucketObjectLockConfiguration")
        && contains(statement.Action, "s3:PutBucketTagging")
      ])
    )
    error_message = "Foundation apply must include the instance-profile and protected-bucket tag/Object Lock APIs used by the provider."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
      statement.Sid == "DenyCrossEnvironmentEc2Mutation"
      && alltrue([
        for action in [
          "ec2:AssociateRouteTable",
          "ec2:AttachInternetGateway",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:DetachInternetGateway",
          "ec2:DisassociateRouteTable",
          "ec2:RevokeSecurityGroupEgress",
        ] : contains(statement.Action, action)
      ])
    ])
    error_message = "Every added EC2 connection and egress mutation must retain the cross-environment explicit deny boundary."
  }

  assert {
    condition = (
      !contains(keys(local.budget_state_role_ids), "deploy")
      && !contains(local.budget_deploy_actions, "ec2:ModifyInstanceCreditSpecification")
      && !contains(local.budget_deploy_actions, "ec2:CreateVolume")
      && !contains(local.budget_deploy_actions, "ec2:AttachVolume")
      && !contains(local.budget_deploy_actions, "ec2:CreateSnapshot")
      && !contains(local.budget_deploy_actions, "ec2:TerminateInstances")
      && !contains(local.budget_deploy_actions, "ec2:RunInstances")
      && !contains(local.budget_deploy_actions, "iam:PassRole")
      && !contains(local.budget_deploy_actions, "s3:GetObject")
      && !contains(local.budget_deploy_actions, "s3:PutObject")
      && !contains(local.budget_deploy_actions, "ecs:RunTask")
      && !contains(local.budget_deploy_actions, "ecs:StopTask")
      && !contains(local.budget_deploy_actions, "ecs:UpdateService")
      && !contains(local.budget_deploy_actions, "ssm:SendCommand")
      && contains(local.budget_deploy_actions, "ecs:ListContainerInstances")
      && contains(local.budget_deploy_actions, "ecs:DescribeTasks")
    )
    error_message = "Deploy may use backup S3, but must not receive a Terraform state policy and must explicitly deny the budget state key."
  }

  assert {
    condition = (
      length(aws_iam_role_policy.github_budget_apply.policy)
      + length(aws_iam_role_policy.github_budget_state["apply"].policy) <= 10240
      && length(aws_iam_policy.github_budget_apply_regional.policy) <= 6144
      && aws_iam_role_policy_attachment.github_budget_apply_regional.role == aws_iam_role.github_budget_production_apply.name
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Sid == "ManageTaggedBudgetResources"
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Action == local.budget_apply_actions
      && one(jsondecode(aws_iam_policy.github_budget_apply_regional.policy).Statement).Condition.StringEqualsIfExists["aws:RequestedRegion"] == "ap-northeast-2"
      && alltrue([
        for statement in jsondecode(aws_iam_role_policy.github_budget_apply.policy).Statement :
        statement.Sid != "ManageTaggedBudgetResources"
      ])
    )
    error_message = "Budget apply inline aggregate and regional managed policy must stay within AWS policy limits and preserve the regional condition."
  }

  assert {
    condition     = length(aws_iam_role_policy.github_budget_deploy.policy) <= 10240
    error_message = "Budget deploy inline IAM policy exceeds the AWS 10,240-byte role policy limit."
  }
}

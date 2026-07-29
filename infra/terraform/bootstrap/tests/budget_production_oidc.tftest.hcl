mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

run "budget_roles_are_separated_and_state_isolated" {
  command = plan
  variables {
    state_bucket_name = "home-search-state-fixture"
    github_repository = "example/home-search"
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
    error_message = "Budget plan must read only the exact public ECS-optimized AMI parameter."
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
    condition     = length(aws_iam_role_policy.github_budget_apply.policy) <= 10240
    error_message = "Budget apply inline IAM policy exceeds the AWS 10,240-byte role policy limit."
  }

  assert {
    condition     = length(aws_iam_role_policy.github_budget_deploy.policy) <= 10240
    error_message = "Budget deploy inline IAM policy exceeds the AWS 10,240-byte role policy limit."
  }
}

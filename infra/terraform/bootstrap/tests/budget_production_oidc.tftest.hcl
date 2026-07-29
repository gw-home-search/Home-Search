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
      !contains(keys(local.budget_state_role_ids), "deploy")
      && contains(local.budget_deploy_actions, "ec2:ModifyInstanceCreditSpecification")
      && contains(local.budget_deploy_actions, "ssm:SendCommand")
    )
    error_message = "Deploy may use backup S3, but must not receive a Terraform state policy and must explicitly deny the budget state key."
  }
}

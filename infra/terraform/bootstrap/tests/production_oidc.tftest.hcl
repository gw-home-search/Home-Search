mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

run "production_workflow_roles_are_separated" {
  command = plan
  variables {
    state_bucket_name = "home-search-state-fixture"
    github_repository = "example/home-search"
  }

  assert {
    condition = (
      aws_iam_role.github_production_plan.name == "home-search-github-production-plan"
      && aws_iam_role.github_production_apply.name == "home-search-github-production-apply"
      && aws_iam_role.github_production_deploy.name == "home-search-github-production-deploy"
      && aws_iam_role.github_production_plan.name != aws_iam_role.github_production_apply.name
    )
    error_message = "Production plan, apply, and deploy must use distinct OIDC roles."
  }

  assert {
    condition = (
      one(local.github_production_plan_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "production"
      && one(local.github_production_plan_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == "Deploy production"
      && length(local.github_production_deploy_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == 2
      && contains(local.github_production_deploy_oidc_string_equals["token.actions.githubusercontent.com:workflow"], "Roll back production application")
      && contains(local.github_production_deploy_oidc_string_equals["token.actions.githubusercontent.com:workflow"], "Roll back Supervisor Graph")
      && contains(local.github_production_oidc_string_like["token.actions.githubusercontent.com:ref"], "refs/heads/main")
      && contains(local.github_production_oidc_string_like["token.actions.githubusercontent.com:ref"], "refs/tags/v*")
    )
    error_message = "Production trust must bind repository, environment, workflow, and protected refs."
  }

  assert {
    condition = (
      contains(local.production_apply_explicit_deny_actions, "rds:DeleteDBInstance")
      && contains(local.production_apply_explicit_deny_actions, "kms:ScheduleKeyDeletion")
      && contains(local.production_apply_explicit_deny_actions, "s3:DeleteBucket")
      && !contains(local.production_deploy_actions, "ecs:DeleteService")
      && !contains(local.production_state_object_actions.plan, "s3:PutObject")
      && contains(local.production_state_object_actions.apply, "s3:PutObject")
    )
    error_message = "Production apply must deny data-plane destruction and rollback must not delete ECS services."
  }
}

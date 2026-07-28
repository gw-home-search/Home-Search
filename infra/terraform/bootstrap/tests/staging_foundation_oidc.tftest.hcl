mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}

run "staging_foundation_roles_are_separated_and_state_scoped" {
  command = plan
  variables {
    state_bucket_name = "home-search-state-fixture"
    github_repository = "example/home-search"
  }

  assert {
    condition = (
      aws_iam_role.github_staging_foundation_plan.name == "home-search-github-staging-foundation-plan"
      && aws_iam_role.github_staging_foundation_apply.name == "home-search-github-staging-foundation-apply"
      && aws_iam_role.github_staging_foundation_plan.name != aws_iam_role.github_staging_foundation_apply.name
      && aws_iam_role.github_staging_foundation_apply.name != aws_iam_role.github_staging.name
      && aws_iam_role.github_staging_foundation_plan.max_session_duration == 3600
      && aws_iam_role.github_staging_foundation_apply.max_session_duration == 7200
    )
    error_message = "Staging foundation plan, apply, and workload deploy must use distinct OIDC roles."
  }

  assert {
    condition = (
      one(local.github_staging_foundation_oidc_string_equals["token.actions.githubusercontent.com:environment"]) == "staging"
      && one(local.github_staging_foundation_oidc_string_equals["token.actions.githubusercontent.com:workflow"]) == "Staging foundation"
      && one(local.github_staging_foundation_oidc_string_like["token.actions.githubusercontent.com:ref"]) == "refs/heads/main"
    )
    error_message = "Staging foundation trust must bind the staging environment, exact workflow, and main ref."
  }

  assert {
    condition = (
      !contains(local.staging_foundation_state_object_actions.plan, "s3:PutObject")
      && contains(local.staging_foundation_state_object_actions.apply, "s3:PutObject")
      && !contains(local.staging_foundation_plan_actions, "s3:GetObject")
      && !contains(local.staging_foundation_plan_actions, "secretsmanager:GetSecretValue")
      && contains(local.staging_foundation_plan_actions, "s3:GetEncryptionConfiguration")
      && contains(local.staging_foundation_plan_actions, "iam:ListRoleTags")
      && contains(local.staging_foundation_plan_actions, "secretsmanager:GetResourcePolicy")
      && !contains(local.staging_foundation_apply_actions, "s3:*")
      && !contains(local.staging_foundation_apply_actions, "secretsmanager:*")
      && !contains(local.staging_foundation_apply_actions, "kms:*")
      && !contains(local.staging_foundation_apply_actions, "kms:Decrypt")
      && toset(local.staging_foundation_kms_data_actions) == toset([
        "kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:ReEncrypt*",
      ])
      && contains(local.staging_foundation_apply_actions, "acm:DescribeCertificate")
      && contains(local.staging_foundation_apply_actions, "route53:CreateHostedZone")
      && contains(local.staging_foundation_explicit_deny_actions, "rds:DeleteDBInstance")
      && contains(local.staging_foundation_explicit_deny_actions, "kms:ScheduleKeyDeletion")
      && contains(local.staging_foundation_explicit_deny_actions, "route53:DeleteHostedZone")
      && contains(local.staging_foundation_explicit_deny_actions, "s3:DeleteBucket")
      && contains(local.staging_foundation_explicit_deny_actions, "ecr:DeleteRepository")
    )
    error_message = "Staging plan must be state-read-only and apply must deny foundation destruction."
  }

  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.github_staging_foundation_apply.policy).Statement : statement
      if statement.Sid == "UseStagingDataKeys"
      ]).Condition.StringEquals == {
      "aws:ResourceTag/Environment" = "staging"
      "aws:ResourceTag/Project"     = "home-search"
    }
    error_message = "KMS data operations must be restricted to tagged staging keys."
  }

  assert {
    condition = (
      var.staging_state_key == "home-search/staging/terraform.tfstate"
      && !startswith(var.staging_state_key, "home-search/production/")
      && !startswith(var.staging_state_key, "home-search/bootstrap/")
    )
    error_message = "Staging foundation roles must be scoped only to the staging state object and lockfile."
  }

  assert {
    condition = alltrue([
      length(local.staging_foundation_workload_role_names) == 50,
      alltrue([
        for resource in one([
          for statement in jsondecode(aws_iam_role_policy.github_staging_foundation_apply.policy).Statement : statement.Resource
          if statement.Sid == "ManageStagingWorkloadRoles"
        ]) : startswith(resource, "arn:aws:iam::123456789012:role/home-search-staging-") && !strcontains(resource, "*")
      ]),
      one([
        for statement in jsondecode(aws_iam_role_policy.github_staging_foundation_apply.policy).Statement : statement.Condition.ArnEquals["iam:PolicyARN"]
        if statement.Sid == "AttachApprovedEcsExecutionPolicy"
      ]) == "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy",
      length(one([
        for statement in jsondecode(aws_iam_role_policy.github_staging_foundation_apply.policy).Statement : statement.Resource
        if statement.Sid == "AttachApprovedEcsExecutionPolicy"
      ])) == 46,
    ])
    error_message = "Staging foundation apply may manage only exact workload roles and attach only the approved ECS execution policy."
  }
}

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
      user_id    = "AIDATEST"
    }
  }
}

run "secure_remote_state_and_exact_oidc_trust" {
  command = plan
  variables {
    state_bucket_name    = "home-search-terraform-state-test"
    github_repository    = "home-search-org/home-search"
    github_environment   = "staging"
    github_workflow_name = "Deploy staging"
  }
  assert {
    condition     = aws_s3_bucket_versioning.terraform_state.versioning_configuration[0].status == "Enabled"
    error_message = "State bucket versioning must be enabled."
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.terraform_state.rule).apply_server_side_encryption_by_default).sse_algorithm == "aws:kms"
    error_message = "State bucket must use KMS encryption."
  }
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.terraform_state.block_public_acls,
      aws_s3_bucket_public_access_block.terraform_state.block_public_policy,
      aws_s3_bucket_public_access_block.terraform_state.ignore_public_acls,
      aws_s3_bucket_public_access_block.terraform_state.restrict_public_buckets,
    ])
    error_message = "Every S3 public access control must be enabled."
  }
  assert {
    condition     = aws_kms_key.terraform_state.enable_key_rotation
    error_message = "State KMS key rotation must be enabled."
  }
  assert {
    condition = local.github_oidc_string_equals == {
      "token.actions.githubusercontent.com:aud"         = ["sts.amazonaws.com"]
      "token.actions.githubusercontent.com:sub"         = ["repo:home-search-org/home-search:environment:staging"]
      "token.actions.githubusercontent.com:repository"  = ["home-search-org/home-search"]
      "token.actions.githubusercontent.com:workflow"    = ["Deploy staging"]
      "token.actions.githubusercontent.com:environment" = ["staging"]
    }
    error_message = "OIDC trust must restrict repository, environment, refs, and workflow."
  }
  assert {
    condition = (
      length(keys(local.github_oidc_string_like)) == 1 &&
      toset(local.github_oidc_string_like["token.actions.githubusercontent.com:ref"]) == toset(["refs/heads/main", "refs/tags/v*"])
    )
    error_message = "OIDC ref scope must be exact without requiring reusable-workflow-only claims."
  }
  assert {
    condition = alltrue([
      local.github_release_oidc_string_equals["token.actions.githubusercontent.com:sub"] == ["repo:home-search-org/home-search:environment:release"],
      local.github_release_oidc_string_equals["token.actions.githubusercontent.com:workflow"] == ["Publish release images"],
      length(keys(local.github_release_oidc_string_like)) == 1,
      local.github_release_oidc_string_like["token.actions.githubusercontent.com:ref"] == ["refs/tags/v*"],
      aws_iam_role.github_release.name != aws_iam_role.github_staging.name,
    ])
    error_message = "Release publishing requires a distinct tag-only role bound to the exact release workflow."
  }
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.github_staging_deployment.policy).Statement : statement.Resource
      if statement.Sid == "DeployStagingServices"
      ]) == [
      "arn:aws:ecs:ap-northeast-2:123456789012:service/home-search-staging/*",
    ]
    error_message = "The staging deployment role must not update ECS services outside the Home Search staging cluster."
  }
}

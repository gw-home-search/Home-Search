output "state_bucket_name" {
  description = "S3 bucket used by Terraform backends."
  value       = aws_s3_bucket.terraform_state.id
}

output "state_kms_key_arn" {
  description = "KMS key ARN used to encrypt state and lock objects."
  value       = aws_kms_key.terraform_state.arn
}

output "github_staging_role_arn" {
  description = "OIDC role assumed only by the protected staging workflow."
  value       = aws_iam_role.github_staging.arn
}

output "github_staging_foundation_plan_role_arn" {
  description = "Staging OIDC role that can read infrastructure and lock, but not write, staging state."
  value       = aws_iam_role.github_staging_foundation_plan.arn
}

output "github_staging_foundation_apply_role_arn" {
  description = "Staging OIDC role for reviewed zero-destroy foundation apply and first bootstrap tasks."
  value       = aws_iam_role.github_staging_foundation_apply.arn
}

output "github_release_role_arn" {
  description = "Tag-only OIDC role that can publish and inspect Home Search ECR images."
  value       = aws_iam_role.github_release.arn
}

output "github_production_plan_role_arn" {
  description = "Production OIDC role that can read infrastructure and lock, but not write, production state."
  value       = aws_iam_role.github_production_plan.arn
}
output "github_production_apply_role_arn" {
  description = "Production OIDC role for reviewed zero-destroy Terraform apply and migration orchestration."
  value       = aws_iam_role.github_production_apply.arn
}
output "github_production_deploy_role_arn" {
  description = "Production OIDC role limited to existing ECS application and Graph rollback."
  value       = aws_iam_role.github_production_deploy.arn
}

output "github_budget_production_plan_role_arn" {
  value       = aws_iam_role.github_budget_production_plan.arn
  description = "Read-only budget-production Terraform plan role."
}

output "github_budget_production_apply_role_arn" {
  value       = aws_iam_role.github_budget_production_apply.arn
  description = "Protected budget-production Terraform apply role."
}

output "github_budget_production_deploy_role_arn" {
  value       = aws_iam_role.github_budget_production_deploy.arn
  description = "Protected budget-production runtime deploy role without state access."
}

output "backend_config" {
  description = "Non-secret backend values used during the explicit state migration."
  value = {
    bucket       = aws_s3_bucket.terraform_state.id
    key          = var.state_key
    region       = var.aws_region
    encrypt      = true
    kms_key_id   = aws_kms_key.terraform_state.arn
    use_lockfile = true
  }
}

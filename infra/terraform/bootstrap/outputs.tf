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

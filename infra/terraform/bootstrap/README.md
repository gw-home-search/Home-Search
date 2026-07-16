# Terraform bootstrap

This stack creates only the encrypted/versioned S3 state bucket, its KMS key,
the GitHub OIDC provider, and the exact staging workflow trust role. It does not
create a DynamoDB lock table; all backends use S3 native `use_lockfile`.

Initial creation intentionally starts with local state:

```bash
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap apply \
  -var='state_bucket_name=REPLACE_WITH_GLOBALLY_UNIQUE_NAME' \
  -var='github_repository=OWNER/REPOSITORY'
```

After recording the outputs, copy `backend.s3.tf.example` to an untracked
`backend.tf`, replace the two placeholders, and migrate explicitly:

```bash
terraform -chdir=infra/terraform/bootstrap init -migrate-state
```

Keep the local pre-migration state as a protected recovery artifact until the
remote state and `.tflock` behavior have been verified. Backend credentials and
secret values must come from the AWS credential chain, never `-backend-config`.

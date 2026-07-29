# Terraform bootstrap

This stack creates only the encrypted/versioned S3 state bucket, its KMS key,
the GitHub OIDC provider, the exact staging workload deploy role, and separate
staging foundation plan/apply roles. The plan role has metadata-only reads plus
the exact staging state object and lock. The apply role consumes a reviewed plan
artifact and cannot delete protected staging foundation resources. Neither role
can read bootstrap or production state objects. It does not
create a DynamoDB lock table; all backends use S3 native `use_lockfile`.

The staging foundation apply role keeps foundation/IAM controls in an inline
policy and attaches a separate customer-managed policy for backup buckets,
secret containers, and tag-scoped KMS data operations. The budget-production
apply role keeps global controls and explicit denies inline, except for exact
service-linked role creation permissions, and attaches separate managed policies
for those permissions and region-gated resource mutations. Terraform tests
enforce safe headroom below the AWS 10,240-character aggregate inline limit and
the 6,144-character managed policy limit. Each attachment is created before the
corresponding inline policy is reduced so an update does not introduce a
temporary permission gap.

Initial creation intentionally starts with local state:

```bash
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap apply \
  -var='state_bucket_name=REPLACE_WITH_GLOBALLY_UNIQUE_NAME' \
  -var='github_repository=OWNER/REPOSITORY' \
  -var='budget_production_hosted_zone_id=REPLACE_WITH_EXACT_HOSTED_ZONE_ID'
```

After recording the outputs, copy `backend.s3.tf.example` to an untracked
`backend.tf`, replace the two placeholders, and migrate explicitly:

```bash
terraform -chdir=infra/terraform/bootstrap init -migrate-state
```

Keep the local pre-migration state as a protected recovery artifact until the
remote state and `.tflock` behavior have been verified. Backend credentials and
secret values must come from the AWS credential chain, never `-backend-config`.

# Production Terraform

This root owns the fail-closed production foundation in `ap-northeast-2`.
Copy `backend.s3.tf.example` outside automation review, set the dedicated
production state bucket/KMS key, and run only through the production OIDC role.
No secret value is accepted by Terraform; operators populate the created
Secrets Manager containers in the deployment workflow.

Validation:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
terraform test
```

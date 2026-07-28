# Production Terraform

This root owns the fail-closed production foundation in `ap-northeast-2`.
Copy `backend.s3.tf.example` outside automation review, set the dedicated
production state bucket/KMS key, and run only through the production OIDC role.
No secret value is accepted by Terraform; operators populate the created
Secrets Manager containers in the deployment workflow.

The root also creates an encrypted, versioned, public-access-blocked audit
bucket. Multi-region CloudTrail with log validation, continuous AWS Config,
GuardDuty, and `ALL` VPC Flow Logs publish production audit evidence through
that boundary. The customer-managed Grafana role is limited to read-only AMP,
CloudWatch, and CloudWatch Logs queries; the workspace network boundary remains
the VPN-only `grafana-workspace` endpoint.

AWS Backup selects all five service databases. The primary Region keeps daily
PITR recovery points for 35 days and monthly snapshots for 12 months; both
tiers create KMS-encrypted copies in `ap-northeast-1`. A monthly restore test
restores the latest RDS recovery point into the private data subnet and leaves
a 24-hour validation window. Application checksum reconciliation remains a
deployment evidence step after AWS Backup reports the restore job complete.

Validation:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
terraform test
```

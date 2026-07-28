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

Monthly cost budgets notify at 50/80/100 percent. Cost Anomaly Detection also
monitors spend by AWS service and sends a daily notification when total anomaly
impact reaches the greater of USD 10 or one percent of the approved monthly
budget.

The workload layer accepts exactly the 17 `uri` values from the approved
release manifest through `image_uris`; tags and repository-only references are
rejected. It creates nine private ECS services, production service discovery,
MSK Serverless, the ML model EFS, a WAF-protected public ALB, and a VPN-only
internal Admin ALB. Services start at desired count zero by default. The
deployment workflow must run schema migrations, runtime grants, data import,
and dark validation before applying `enable_services=true`; the enabled desired
count cannot be lower than two and ECS availability-zone rebalancing remains on.

Required non-secret inputs added by the workload layer are
`admin_certificate_arn`, `public_origin`, and `image_uris`. Secret values are
never Terraform variables: operators inject values into the KMS-encrypted
Secrets Manager containers after foundation apply.

Validation:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
terraform test
```

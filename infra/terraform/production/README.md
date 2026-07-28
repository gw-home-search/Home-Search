# Production Terraform

This root owns the fail-closed production foundation in `ap-northeast-2`.
Copy `backend.s3.tf.example` outside automation review, set the dedicated
production state bucket/KMS key, and run only through the production OIDC role.
No secret value is accepted by Terraform. The first approved job applies the
reviewed foundation plan with every service stopped. The finite
`secret-bootstrap` task then creates database credentials and JWT keys only
when their containers have no current value. Operators inject OAuth, Kakao,
OpenAI, and public-data provider values before approving the second production
job; `secret-readiness` fails closed before any service activation.

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
deployment workflow runs database/JWT bootstrap, fresh schema migrations,
Property+Reference data-only import/reconciliation, runtime grants, and marker
projection before advancing through `service_activation_phase=consumers`,
`private`, and finally `all`. Each phase plan may change only desired counts and
their corresponding alarms. The `public-gateway` remains stopped through
private dark validation; enabled desired counts cannot be lower than two and
ECS availability-zone rebalancing remains on.

The migration artifact input is an immutable, KMS-encrypted S3 prefix containing
exactly one allowlisted manifest and all referenced chunks/raw objects. Import
runs as a 100 GiB finite task, resumes from durable chunk checkpoints, restores
reference raw objects into the versioned production bucket, and uploads the
reconciliation report under the release-specific audit prefix. User, Admin,
session, token, schema history, role, and secret data remain excluded.

Property, Admin, User, ML, AI, and chat-bff run a digest-pinned ADOT sidecar
that scrapes only the loopback metrics endpoint and remote-writes to the
production AMP workspace with a workload-specific `aps:RemoteWrite` task
policy. AMP alert rules enforce the map p95/error and AI terminal-contract
gates, and route through an explicitly scoped role to the approved SNS topic.
CloudWatch alarms and the code-managed production dashboard cover public ALB,
ECS task count, all five RDS databases, Valkey pressure, and public certificate
expiry.

Required non-secret inputs added by the workload layer are
`admin_certificate_arn`, `public_origin`, `image_uris`, the immutable
`adot_collector_image_uri`, `deployment_release_tag`,
`migration_artifact_bucket`, `migration_artifact_prefix`, and
`migration_artifact_kms_key_arn`, plus the reviewed
`migration_manifest_sha256`. Secret values are never Terraform variables.
Database credential containers use a `password` key; Production bootstrap also
materializes the exact AI DSNs without logging them. The `database-bootstrap`
task alone reads the five RDS master secrets and never passes them to migration
or runtime tasks.

Before migration, the workflow captures current alarm states and creates an
encrypted manual snapshot for each of the five RDS instances. Full application
rollback restores prior task definition ARNs producer-to-consumer and never
runs down migrations or deletes schema, topics, volumes, or databases.

`READY_TO_DEPLOY` is never inferred from local tests. Gather the twelve actual
environment evidence JSON files required by
`infra/deploy/build-ready-to-deploy-evidence.sh`; the command emits the bundle
only when performance, migration/restore, VPN boundary, AMP/SNS, AI golden,
rollback, review, cost, and both approved release exceptions all pass. Until
then, actual production readiness remains `not run`.

Validation:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
terraform test
```

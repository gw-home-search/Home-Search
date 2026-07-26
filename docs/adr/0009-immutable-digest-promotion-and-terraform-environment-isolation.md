# ADR 0009: Immutable digest promotion and Terraform environment isolation

- Status: Accepted
- Date: 2026-07-25

## Context

Staging Terraform and immutable ECR release code exist, but staging and
production need independent state, roles, approval, and evidence. Rebuilding
for production would make rollback and provenance ambiguous.

## Decision

- Build each image once and promote the same digest from staging to production.
- Record commit, image digests, architecture, SBOM/vulnerability evidence,
  schema/topic/migration hashes, and workflow run id in a release manifest.
- Use separate staging and production Terraform roots, S3 state keys, KMS
  boundaries, OIDC roles, VPCs, and Secrets Manager paths. Do not use Terraform
  workspaces.
- Separate read-only foundation plan, protected foundation apply, contract
  promotion, and workload deploy roles/pipelines.
- Require staging E2E and seven days of stable evidence before production
  approval.

## Consequences

Environment-specific configuration changes without changing the application
artifact. Rollback selects a previous task definition/digest. Database down
migration and schema/topic deletion remain forbidden.

## Migration

Existing staging resources move into modules only with explicit address
mapping, `moved` blocks, and a reviewed plan showing `0 to destroy`.

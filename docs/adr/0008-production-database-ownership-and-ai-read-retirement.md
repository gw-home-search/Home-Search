# ADR 0008: Production database ownership and ai_read retirement

- Status: Accepted
- Date: 2026-07-25

## Context

Staging can share an RDS instance while separating databases and credentials,
but production ownership, migration, restore, and failure isolation require
physical separation. AI currently depends on property `ai_read` views as a
migration bridge.

## Decision

- Production uses dedicated property, admin, user, AI, and coordinate RDS
  instances.
- Each database separates migrator, runtime, importer, backup, and optional
  reader roles. Only bootstrap can read RDS master secrets.
- Cross-service joins and foreign keys are forbidden. `complex_id` remains an
  opaque cross-service identifier and the operational property trade relation.
- Coordinate PNU read-only lookup remains the documented narrow exception.
- Retire AI `ai_read` only after filtered bootstrap plus Kafka dual-read parity
  proves count, checksum, and query equivalence.

## Consequences

The design has higher fixed cost and more restore/migration work, but narrows
data ownership and failure blast radius. AI projection is eventually
independent of the property database.

## Stop conditions

Do not remove `ai_read`, reinterpret identifiers, or cut over a database when
projection parity, reconciliation, restore, or rollback evidence is incomplete.

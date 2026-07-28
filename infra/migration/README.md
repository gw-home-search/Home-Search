# Property + Reference data-only migration

`data_only_migration.py` moves only the checked-in
`data-only-allowlist.json` datasets. User/Admin/session/token data, schema,
roles, secrets, Flyway history, Spring Batch metadata, marker projections,
nationwide coordinate rows, and historical building-register collection/raw/
analysis/publication rows are not exported. Apply fresh Property and AI
migrations before import. Rebuild `mapMarkerProjectionJob` after reconciliation.

The initial target keeps every `building_register*` and
`complex_building_register*` table created by fresh Flyway empty. This avoids
uploading the previously collected nationwide recap-title/title-line corpus.
Public detail remains contract-compatible because `buildingProfile` is
nullable. Existing compact fields already stored on `complex` and the separate
`building_ratio_*` evidence remain in scope. Recollection/publication requires
a later reviewed operator run; this tool rejects adding building-register
history back to the catalog accidentally.

The exporter holds one `REPEATABLE READ READ ONLY` exported snapshot per
logical database. Large tables use stable key ranges; every zstd chunk records
the compressed and canonical CSV SHA-256, row count, min/max key, and source WAL
watermark. The importer rejects catalog/schema drift and unexpected datasets,
validates checksums before writes, and records each completed chunk in the
target-only `home_migration.import_progress` table in the same transaction as
the data write. A rerun validates the durable checkpoint metadata and skips the
completed chunk, while a missing or mismatched checkpoint fails closed.
Property evidence permits an update only when a final snapshot contains a
corrected row (including trade cancellation); unchanged conflicts perform no
write. Reference history rejects conflicting rows, while only
`dataset_active_snapshot` permits pointer updates. It never drops a source
database or Docker volume.

Reference rows backed by S3 are transferred with their exact source version.
The exporter verifies each content-addressed object's SHA-256 and byte length
before atomically publishing the local artifact. Import uploads or reuses the
same immutable key in the target bucket, then replaces only the staged
`object_version_id` with the target version before inserting the fresh database
row. Reconciliation maps that target version back to the source version for CSV
parity while independently checking the target DB version with S3 `HEAD`.
Standard AWS S3 targets require SSE-KMS; endpoint overrides are accepted only
for local MinIO tests.

## Environment

Set each connection outside Git. Passwords are passed to `psql` only through
`PGPASSWORD`, not command arguments.

```text
HOME_MIGRATION_PROPERTY_SOURCE_HOST
HOME_MIGRATION_PROPERTY_SOURCE_PORT=5432
HOME_MIGRATION_PROPERTY_SOURCE_DATABASE
HOME_MIGRATION_PROPERTY_SOURCE_USER
HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD

HOME_MIGRATION_REFERENCE_SOURCE_HOST
HOME_MIGRATION_REFERENCE_SOURCE_PORT=5432
HOME_MIGRATION_REFERENCE_SOURCE_DATABASE
HOME_MIGRATION_REFERENCE_SOURCE_USER
HOME_MIGRATION_REFERENCE_SOURCE_PASSWORD

HOME_MIGRATION_RAW_SOURCE_BUCKET
HOME_MIGRATION_RAW_SOURCE_REGION=ap-northeast-2
# local test only: HOME_MIGRATION_RAW_SOURCE_ENDPOINT=http://127.0.0.1:19000
```

For import/reconciliation, replace `SOURCE` with `TARGET` and set
`HOME_MIGRATION_RAW_TARGET_KMS_KEY_ID` for an AWS S3 target. `psql`, `zstd`,
AWS CLI, and Python 3.11+ are required. Optional migration-artifact publication
requires both `--s3-uri` and `--kms-key-id`; the tool forces `aws:kms`
server-side encryption.
Compression uses one zstd worker by default to keep one-shot task memory
bounded. `HOME_MIGRATION_ZSTD_THREADS=1..8` may be set from measured task
capacity. A chunk is written as `.partial` and atomically renamed only after
both PostgreSQL export and compression succeed. Local evidence directories are
forced to mode `0700`, and chunks, raw objects, manifests, and reconciliation
reports to `0600`.
The immutable `backup` release image contains the same tool, catalog, `zstd`,
PostgreSQL 17 clients, and PostGIS restore libraries. Its entrypoint exposes
`--data-export`, `--data-import`, `--data-reconcile`, and
`--data-validate-catalog` for one-shot ECS execution without a second build.

## Runbook

1. Disable source ingest/reference schedulers for the final delta window.
2. Export the initial snapshot, then export a final reconciliation snapshot
   after scheduler pause. Artifacts are immutable; an output directory must be
   empty. The final import upserts corrected Property rows and the Reference
   active pointer without duplicating unchanged rows.
3. Apply fresh target schemas. Do not import schema/Flyway history/roles.
4. Import in catalog order. Re-run the same command after interruption.
5. Reconcile before enabling target schedulers.
6. Run `mapMarkerProjectionJob`, verify marker parity/public golden responses,
   then activate target schedulers.

The source databases, transfer artifacts, and local Docker volumes are retained.
Exclusion from this initial transfer is not authorization to delete them.

```bash
python3 infra/migration/data_only_migration.py validate-catalog

python3 infra/migration/data_only_migration.py export \
  --output /evidence/property-reference-20260728 \
  --s3-uri s3://migration-bucket/release-tag \
  --kms-key-id alias/home-search-production-migration

python3 infra/migration/data_only_migration.py import \
  --manifest /evidence/property-reference-20260728/data-only-manifest.json

python3 infra/migration/data_only_migration.py reconcile \
  --manifest /evidence/property-reference-20260728/data-only-manifest.json \
  --report /evidence/property-reference-20260728/reconciliation.json
```

The reconciliation report fails on chunk hash or row-count differences,
duplicate normalized source keys, normalized rows without raw evidence,
invalid coordinate range/SRID, or a `MATCH_FAILED` raw row without queryable
match evidence. Marker parity and public API golden responses remain separate
post-import gates because projections are deliberately rebuilt, not copied.

## Verification

```bash
cd infra/migration
python3 -m unittest -v test_data_only_migration.py
./test-data-only-migration-integration.sh
```

The integration check creates and removes only uniquely named temporary test
databases in the local PostgreSQL container. It does not modify source rows.

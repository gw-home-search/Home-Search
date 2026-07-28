# Property + Reference data-only migration

`data_only_migration.py` moves only the checked-in
`data-only-allowlist.json` datasets. User/Admin/session/token data, schema,
roles, secrets, Flyway history, Spring Batch metadata, marker projections, and
sequences are not exported. Apply fresh Property and AI migrations before
import. Rebuild `mapMarkerProjectionJob` after reconciliation.

The exporter holds one `REPEATABLE READ READ ONLY` exported snapshot per
logical database. Large tables use stable key ranges; every zstd chunk records
the compressed and canonical CSV SHA-256, row count, min/max key, and source WAL
watermark. The importer rejects catalog/schema drift and unexpected datasets,
validates checksums before writes, and uses primary/unique keys plus full-row
equality to make a repeated chunk import idempotent. Property evidence permits
an update only when a final snapshot contains a corrected row (including trade
cancellation); unchanged conflicts perform no write. Reference history rejects
conflicting rows, while only `dataset_active_snapshot` permits pointer updates.
It never drops a source
database or Docker volume.

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
```

For import/reconciliation, replace `SOURCE` with `TARGET`. `psql`, `zstd`, and
Python 3.11+ are required. Optional S3 publication requires both `--s3-uri` and
`--kms-key-id`; the tool forces `aws:kms` server-side encryption.
Compression uses one zstd worker by default to keep one-shot task memory
bounded. `HOME_MIGRATION_ZSTD_THREADS=1..8` may be set from measured task
capacity. A chunk is written as `.partial` and atomically renamed only after
both PostgreSQL export and compression succeed.
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

# Database Backup and Restore Verification

`home-search-db-backup.sh` supports the five production databases—`property`,
`admin`, `user`, `ai`, and `coordinate`—in PostgreSQL custom format. The default
set is `property,admin,user,ai`. The nationwide coordinate source is deferred
and must be added explicitly only after an operator import, reconciliation, and
runtime activation approval. The checked-in staging task remains narrower at
`property,admin,user`.

Create local artifacts:

```bash
HOME_BACKUP_PGHOST=127.0.0.1 \
HOME_BACKUP_PGPORT=15432 \
HOME_BACKUP_PGUSER=backup_role \
HOME_BACKUP_PGPASSWORD='set-outside-shell-history' \
HOME_BACKUP_LOGICAL_DATABASES=property,admin,user,ai \
infra/backup/home-search-db-backup.sh --backup-all /tmp/home-search-backups
```

Set `HOME_BACKUP_S3_URI=s3://bucket/prefix` and `HOME_BACKUP_KMS_KEY_ID` to
upload each dump followed by its
manifest. Each manifest records the logical/database name, UTC timestamp,
PostgreSQL version, dump checksum, migration checksum, Flyway success count,
and a core-table row count. Passwords are passed through a temporary mode-0600
`PGPASSFILE`, not command arguments.

Verify a restore:

```bash
infra/backup/home-search-db-backup.sh \
  --verify-restore /tmp/home-search-backups/property-YYYYmmddTHHMMSSZ.manifest.tsv
```

Verify the newest selected artifacts from S3 in one non-destructive
rehearsal:

```bash
infra/backup/home-search-db-backup.sh \
  --verify-latest-s3 s3://bucket/staging
```

Verification checks the dump and migration checksums, initializes a PostgreSQL
cluster under task-local temporary storage, restores into that cluster, and
compares Flyway/core row-count invariants. It never connects to, drops, or
overwrites an existing database.

When the five databases use separate RDS endpoints or roles, set
`HOME_BACKUP_<LOGICAL>_PGHOST`, `PGPORT`, `PGUSER`, and `PGPASSWORD` for each
logical name. These override the shared defaults without placing passwords in
process arguments. The backup image includes PostGIS restore support for the
Property and Coordinate schemas.

Do not add `coordinate` to `HOME_BACKUP_LOGICAL_DATABASES` merely because the
empty coordinate RDS exists. The operator activation evidence must first prove
the imported snapshot checksum/row count and the read-only runtime boundary.

Run the deterministic fixture and real PostgreSQL integration checks:

```bash
infra/backup/test-home-search-db-backup.sh
infra/backup/test-home-search-db-backup-integration.sh
```

# Database Backup and Restore Verification

`home-search-db-backup.sh` backs up only the operational `property`, `admin`,
and `user` databases in PostgreSQL custom format. The coordinate-source
database is intentionally excluded.

Create local artifacts:

```bash
HOME_BACKUP_PGHOST=127.0.0.1 \
HOME_BACKUP_PGPORT=15432 \
HOME_BACKUP_PGUSER=backup_role \
HOME_BACKUP_PGPASSWORD='set-outside-shell-history' \
infra/backup/home-search-db-backup.sh --backup-all /tmp/home-search-backups
```

Set `HOME_BACKUP_S3_URI=s3://bucket/prefix` to upload each dump followed by its
manifest. Each manifest records the logical/database name, UTC timestamp,
PostgreSQL version, dump checksum, migration checksum, Flyway success count,
and a core-table row count. Passwords are passed through a temporary mode-0600
`PGPASSFILE`, not command arguments.

Verify a restore:

```bash
infra/backup/home-search-db-backup.sh \
  --verify-restore /tmp/home-search-backups/property-YYYYmmddTHHMMSSZ.manifest.tsv
```

Verify the newest property/admin/user artifacts from S3 in one non-destructive
rehearsal:

```bash
infra/backup/home-search-db-backup.sh \
  --verify-latest-s3 s3://bucket/staging
```

Verification checks the dump and migration checksums, initializes a PostgreSQL
cluster under task-local temporary storage, restores into that cluster, and
compares Flyway/core row-count invariants. It never connects to, drops, or
overwrites an existing database.

Run the deterministic fixture and real PostgreSQL integration checks:

```bash
infra/backup/test-home-search-db-backup.sh
infra/backup/test-home-search-db-backup-integration.sh
```

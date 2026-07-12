# Home Search Source Data

`apps/source-data` owns the `home_search_coordinate_source` database migration and coordinate snapshot importer. It is not a long-running HTTP service.

## Migration boundary

- Runtime Flyway auto-run is always disabled.
- Flyway location: `classpath:db/migration/coordinate-source`.
- History table: `reference.flyway_schema_history`.
- V1 is the fresh database schema.
- A legacy database may be baselined at V1 only after the exact read-only fingerprint passes.
- V2 adds missing checkpoint/stage/publish relations without deleting or rewriting snapshot rows.
- V3 owns `geo_enrichment`.
- V4 keeps importer read access to Flyway history while revoking history DML.

Build the run-and-exit artifact:

```bash
../property-data/gradlew -p . bootJar
```

Supported operations:

```text
--operation=info
--operation=validate
--operation=preflight-baseline
--operation=baseline-existing --confirm-database=home_search_coordinate_source
--operation=migrate --target=2 --confirm=2
--operation=migrate --target=3 --confirm=3
--operation=migrate --target=4 --confirm=4
```

`preflight-baseline` is read-only. `baseline-existing` changes only Flyway history after the same fingerprint passes. Do not run baseline or migrate until schema/role backups and the operator checkpoint are complete.

For a controlled legacy adoption, run `ops/init-coordinate-source-roles.sh`
after the backups and before `baseline-existing`. The script creates the
dedicated migrator/importer/reader roles, transfers only coordinate-source
schema/table/sequence ownership to the migrator, and grants importer/reader
runtime privileges. It does not update snapshot rows. Passwords are accepted
only through its required environment variables.

`migrate` validates all already-applied migrations before execution while
allowing only migrations that are pending for the confirmed target. It runs a
strict target validation again after migration. Once a migration has been applied
to a durable database, never edit that SQL file; use a new forward migration.

The importer never creates schema. It requires the expected database, `reference.flyway_schema_history`, migration version 2 or newer, and all required snapshot/checkpoint relations before data loading starts.

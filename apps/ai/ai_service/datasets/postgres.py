from __future__ import annotations

import hashlib
import re
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .models import (
    AcquisitionRecord,
    ActiveSnapshot,
    ActiveSnapshotState,
    DatasetSourceContract,
    LifecycleResult,
    RejectedRow,
    ValidationOutcome,
)
from .normalized_spool import iter_spooled_rows
from .service import PublicationStoreError
from .raw_store import StoredRawObject


@dataclass(frozen=True)
class Migration:
    version: int
    description: str
    path: Path
    checksum: str


def discover_migrations(directory: Path) -> tuple[Migration, ...]:
    migrations: list[Migration] = []
    versions: set[int] = set()
    for path in sorted(directory.glob("[0-9][0-9][0-9][0-9]_*.sql")):
        match = re.fullmatch(r"(\d{4})_([a-z0-9_]+)\.sql", path.name)
        if match is None:
            continue
        version = int(match.group(1))
        if version in versions:
            raise RuntimeError("duplicate AI migration version")
        versions.add(version)
        sql = path.read_bytes()
        migrations.append(
            Migration(
                version=version,
                description=match.group(2).replace("_", " "),
                path=path,
                checksum=hashlib.sha256(sql).hexdigest(),
            )
        )
    if not migrations:
        raise RuntimeError("no AI migrations found")
    return tuple(sorted(migrations, key=lambda migration: migration.version))


class PostgresDatasetRepository:
    def __init__(
        self,
        dsn: str,
        *,
        migration_directory: Path | None = None,
        expected_database: str | None = None,
        expected_username: str | None = None,
    ) -> None:
        self._dsn = dsn
        self._migration_directory = migration_directory or Path(__file__).with_name("migrations")
        self._expected_database = expected_database
        self._expected_username = expected_username

    def close(self) -> None:
        return None

    def start_refresh_run(
        self,
        *,
        source_id: str,
        provider: str,
        profile: str,
        trigger_type: str,
        started_at: datetime,
    ) -> UUID:
        if trigger_type not in {"MANUAL", "SCHEDULED"}:
            raise ValueError("invalid refresh trigger type")
        if not source_id.strip() or not provider.strip() or not profile.strip():
            raise ValueError("refresh run metadata must not be blank")
        refresh_run_id = uuid4()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO dataset_source(source_id, provider, created_at)
                VALUES (%s, %s, %s)
                ON CONFLICT (source_id) DO NOTHING
                """,
                (source_id, provider, started_at),
            )
            connection.execute(
                """
                INSERT INTO dataset_refresh_run(
                    refresh_run_id, profile, trigger_type, started_at, status
                ) VALUES (%s, %s, %s, %s, 'RUNNING')
                """,
                (refresh_run_id, profile, trigger_type, started_at),
            )
            connection.execute(
                """
                INSERT INTO dataset_refresh_run_item(
                    refresh_run_id, source_id, started_at, status
                ) VALUES (%s, %s, %s, 'RUNNING')
                """,
                (refresh_run_id, source_id, started_at),
            )
        return refresh_run_id

    def finish_refresh_run(
        self,
        *,
        refresh_run_id: UUID,
        source_id: str,
        acquisition_id: UUID | None,
        status: str,
        reason_codes: tuple[str, ...],
        finished_at: datetime,
    ) -> None:
        if status not in {"PASS", "NO_CHANGE", "FAIL"}:
            raise ValueError("invalid refresh item status")
        run_status = "PASS" if status in {"PASS", "NO_CHANGE"} else "FAIL"
        with self._connect() as connection:
            item = connection.execute(
                """
                UPDATE dataset_refresh_run_item
                SET acquisition_id = %s, finished_at = %s, status = %s,
                    reason_codes = %s
                WHERE refresh_run_id = %s AND source_id = %s AND status = 'RUNNING'
                """,
                (
                    acquisition_id,
                    finished_at,
                    status,
                    list(dict.fromkeys(reason_codes)),
                    refresh_run_id,
                    source_id,
                ),
            )
            if item.rowcount != 1:
                raise RuntimeError("refresh run item is not running")
            run = connection.execute(
                """
                UPDATE dataset_refresh_run
                SET finished_at = %s, status = %s
                WHERE refresh_run_id = %s AND status = 'RUNNING'
                """,
                (finished_at, run_status, refresh_run_id),
            )
            if run.rowcount != 1:
                raise RuntimeError("refresh run is not running")

    @contextmanager
    def source_lock(self, source_id: str) -> Iterator[None]:
        connection = self._connect()
        try:
            acquired = connection.execute(
                "SELECT pg_try_advisory_lock(hashtextextended(%s, 0)) AS acquired",
                (source_id,),
            ).fetchone()["acquired"]
            if not acquired:
                raise SourceRefreshAlreadyRunning("SOURCE_REFRESH_ALREADY_RUNNING")
            yield
        finally:
            connection.close()

    def migrate(self) -> None:
        migrations = discover_migrations(self._migration_directory)
        with self._connect() as connection:
            connection.execute("SELECT pg_advisory_xact_lock(721002)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS ai_schema_history (
                    version integer PRIMARY KEY,
                    description text NOT NULL,
                    checksum char(64) NOT NULL,
                    installed_at timestamptz NOT NULL DEFAULT now()
                )
                """
            )
            applied = {
                int(row["version"]): str(row["checksum"])
                for row in connection.execute(
                    "SELECT version, checksum FROM ai_schema_history ORDER BY version"
                ).fetchall()
            }
            known_versions = {migration.version for migration in migrations}
            if set(applied) - known_versions:
                raise RuntimeError("applied AI migration file is missing")
            for migration in migrations:
                existing_checksum = applied.get(migration.version)
                if existing_checksum is not None:
                    if existing_checksum != migration.checksum:
                        raise RuntimeError("applied AI migration checksum mismatch")
                    continue
                sql = migration.path.read_text(encoding="utf-8")
                connection.execute(sql)
                connection.execute(
                    "INSERT INTO ai_schema_history(version, description, checksum) VALUES (%s, %s, %s)",
                    (migration.version, migration.description, migration.checksum),
                )

    def register_source_contract(
        self, contract: DatasetSourceContract, registered_at: datetime
    ) -> UUID:
        contract_id = uuid4()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO dataset_source(source_id, provider, created_at)
                VALUES (%s, %s, %s)
                ON CONFLICT (source_id) DO NOTHING
                """,
                (contract.source_id, contract.provider, registered_at),
            )
            connection.execute(
                """
                INSERT INTO dataset_source_contract(
                    contract_id, source_id, contract_fingerprint, contract, registered_at
                ) VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (source_id, contract_fingerprint) DO NOTHING
                """,
                (
                    contract_id,
                    contract.source_id,
                    contract.fingerprint(),
                    Jsonb(contract.as_json()),
                    registered_at,
                ),
            )
            row = connection.execute(
                """
                SELECT contract_id FROM dataset_source_contract
                WHERE source_id = %s AND contract_fingerprint = %s
                """,
                (contract.source_id, contract.fingerprint()),
            ).fetchone()
            return row["contract_id"]

    def acquire_raw(
        self,
        contract: DatasetSourceContract,
        contract_id: UUID,
        checksum: str,
        raw_bytes: bytes,
        source_date: date | None,
        observed_at: datetime | None,
        collected_at: datetime,
    ) -> AcquisitionRecord:
        acquisition_id = uuid4()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO dataset_raw_object(
                    checksum, content, byte_length, collected_at,
                    storage_backend, content_type
                )
                VALUES (%s, %s, %s, %s, 'INLINE_DB', 'application/octet-stream')
                ON CONFLICT (checksum) DO NOTHING
                """,
                (checksum, raw_bytes, len(raw_bytes), collected_at),
            )
            inserted = connection.execute(
                """
                INSERT INTO dataset_acquisition(
                    acquisition_id, source_id, contract_id, checksum, temporal_basis,
                    source_date, observed_at, collected_at, status
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'ACQUIRED')
                ON CONFLICT (source_id, checksum) DO NOTHING
                RETURNING acquisition_id
                """,
                (
                    acquisition_id,
                    contract.source_id,
                    contract_id,
                    checksum,
                    contract.temporal_basis,
                    source_date,
                    observed_at,
                    collected_at,
                ),
            ).fetchone()
            if inserted:
                return AcquisitionRecord(acquisition_id=inserted["acquisition_id"], created=True)
            existing = connection.execute(
                """
                SELECT acquisition_id FROM dataset_acquisition
                WHERE source_id = %s AND checksum = %s
                """,
                (contract.source_id, checksum),
            ).fetchone()
            return AcquisitionRecord(acquisition_id=existing["acquisition_id"], created=False)

    def acquire_stored_raw(
        self,
        contract: DatasetSourceContract,
        contract_id: UUID,
        raw_object: StoredRawObject,
        source_date: date | None,
        observed_at: datetime | None,
        collected_at: datetime,
    ) -> AcquisitionRecord:
        acquisition_id = uuid4()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO dataset_raw_object(
                    checksum, content, byte_length, collected_at, storage_backend,
                    object_key, object_version_id, content_type
                ) VALUES (%s, NULL, %s, %s, 'S3', %s, %s, %s)
                ON CONFLICT (checksum) DO NOTHING
                """,
                (
                    raw_object.checksum,
                    raw_object.byte_length,
                    collected_at,
                    raw_object.object_key,
                    raw_object.object_version_id,
                    raw_object.content_type,
                ),
            )
            inserted = connection.execute(
                """
                INSERT INTO dataset_acquisition(
                    acquisition_id, source_id, contract_id, checksum, temporal_basis,
                    source_date, observed_at, collected_at, status
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'ACQUIRED')
                ON CONFLICT (source_id, checksum) DO NOTHING
                RETURNING acquisition_id
                """,
                (
                    acquisition_id,
                    contract.source_id,
                    contract_id,
                    raw_object.checksum,
                    contract.temporal_basis,
                    source_date,
                    observed_at,
                    collected_at,
                ),
            ).fetchone()
            if inserted:
                return AcquisitionRecord(acquisition_id=inserted["acquisition_id"], created=True)
            existing = connection.execute(
                """
                SELECT acquisition_id FROM dataset_acquisition
                WHERE source_id = %s AND checksum = %s
                """,
                (contract.source_id, raw_object.checksum),
            ).fetchone()
            return AcquisitionRecord(acquisition_id=existing["acquisition_id"], created=False)

    def record_validation(
        self,
        acquisition_id: UUID,
        outcome: ValidationOutcome,
        validated_at: datetime,
        *,
        normalized_checksum: str,
        normalization_schema_version: str,
        no_change: bool,
    ) -> None:
        status = (
            "QUALITY_FAILED"
            if outcome.has_blocking_issues
            else "NO_CHANGE"
            if no_change
            else "VALIDATED"
        )
        with self._connect() as connection:
            if status == "VALIDATED":
                with connection.cursor().copy(
                    """
                    COPY dataset_staging_row(
                        acquisition_id, row_number, row_data, source_key, accepted
                    ) FROM STDIN
                    """
                ) as copy:
                    for row in _outcome_rows(outcome):
                        if row.accepted:
                            copy.write_row(
                                (
                                    acquisition_id,
                                    row.row_number,
                                    Jsonb(row.row_data),
                                    row.source_key,
                                    True,
                                )
                            )
            for row in _outcome_rows(outcome):
                for reason_code in row.rejection_codes:
                    connection.execute(
                        """
                        INSERT INTO dataset_rejected_row(
                            acquisition_id, row_number, reason_code, row_data,
                            source_key, field_name, evidence, recorded_at
                        ) VALUES (%s, %s, %s, NULL, %s, %s, '{}'::jsonb, %s)
                        """,
                        (
                            acquisition_id,
                            row.row_number,
                            reason_code,
                            row.source_key,
                            _rejected_field_name(reason_code),
                            validated_at,
                        ),
                    )
            for issue in outcome.issues:
                self._insert_issue(
                    connection,
                    acquisition_id,
                    issue.reason_code,
                    issue.severity,
                    issue.row_number,
                    issue.details,
                    validated_at,
                )
            connection.execute(
                """
                UPDATE dataset_acquisition
                SET status = %s, raw_row_count = %s, accepted_row_count = %s,
                    rejected_row_count = %s, validated_at = %s,
                    normalized_checksum = %s, normalization_schema_version = %s
                WHERE acquisition_id = %s AND status = 'ACQUIRED'
                """,
                (
                    status,
                    outcome.raw_row_count,
                    outcome.accepted_row_count,
                    outcome.rejected_row_count,
                    validated_at,
                    normalized_checksum,
                    normalization_schema_version,
                    acquisition_id,
                ),
            )

    def record_parse_failure(
        self, acquisition_id: UUID, reason_code: str, recorded_at: datetime
    ) -> None:
        with self._connect() as connection:
            self._insert_issue(
                connection,
                acquisition_id,
                reason_code,
                "BLOCKING",
                None,
                {},
                recorded_at,
            )
            connection.execute(
                """
                UPDATE dataset_acquisition
                SET status = 'PARSE_FAILED', validated_at = %s
                WHERE acquisition_id = %s AND status = 'ACQUIRED'
                """,
                (recorded_at, acquisition_id),
            )

    def record_incomplete(
        self, acquisition_id: UUID, reason_codes: tuple[str, ...], recorded_at: datetime
    ) -> None:
        with self._connect() as connection:
            for reason_code in dict.fromkeys(reason_codes):
                self._insert_issue(
                    connection,
                    acquisition_id,
                    reason_code,
                    "BLOCKING",
                    None,
                    {},
                    recorded_at,
                )
            connection.execute(
                """
                UPDATE dataset_acquisition
                SET status = 'INCOMPLETE', validated_at = %s
                WHERE acquisition_id = %s AND status = 'ACQUIRED'
                """,
                (recorded_at, acquisition_id),
            )

    def publish(self, acquisition_id: UUID, published_at: datetime) -> UUID:
        publication_id = uuid4()
        try:
            with self._connect() as connection:
                acquisition = connection.execute(
                    """
                    SELECT source_id, source_date, observed_at, temporal_basis, checksum,
                           normalized_checksum, normalization_schema_version
                    FROM dataset_acquisition
                    WHERE acquisition_id = %s AND status = 'VALIDATED'
                    FOR UPDATE
                    """,
                    (acquisition_id,),
                ).fetchone()
                if not acquisition:
                    raise ValueError("only validated acquisitions can be published")
                connection.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                    (acquisition["source_id"],),
                )
                temporal_value = acquisition["source_date"] or acquisition["observed_at"]
                if acquisition["temporal_basis"] == "SOURCE_DATE":
                    version_prefix = temporal_value.isoformat()
                else:
                    version_prefix = temporal_value.strftime("%Y%m%d")
                dataset_version = (
                    f"{version_prefix}-{str(acquisition['normalized_checksum'])[:12]}"
                )
                connection.execute(
                    """
                    INSERT INTO dataset_publication(
                        publication_id, source_id, acquisition_id, dataset_version,
                        source_date, observed_at, temporal_basis, raw_checksum,
                        normalized_checksum, normalization_schema_version, published_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        publication_id,
                        acquisition["source_id"],
                        acquisition_id,
                        dataset_version,
                        acquisition["source_date"],
                        acquisition["observed_at"],
                        acquisition["temporal_basis"],
                        acquisition["checksum"],
                        acquisition["normalized_checksum"],
                        acquisition["normalization_schema_version"],
                        published_at,
                    ),
                )
                connection.execute(
                    """
                    INSERT INTO dataset_snapshot_row(publication_id, source_key, row_data)
                    SELECT %s, source_key, row_data FROM dataset_staging_row
                    WHERE acquisition_id = %s AND accepted = true
                    """,
                    (publication_id, acquisition_id),
                )
                if acquisition["source_id"] == "edu.school-location":
                    connection.execute(
                        """
                        INSERT INTO reference_projection.facility_point(
                            publication_id, source_id, fact_id, category, subcategory,
                            name, status, road_address, lot_address, position,
                            original_crs, original_x, original_y, row_reference_date,
                            attributes
                        )
                        SELECT %s, %s, row_data ->> 'school_id', 'SCHOOL',
                               row_data ->> 'school_level', row_data ->> 'school_name',
                               CASE row_data ->> 'operating_status'
                                   WHEN '운영' THEN 'OPEN'
                                   WHEN '폐교' THEN 'CLOSED'
                                   WHEN '휴교' THEN 'SUSPENDED'
                                   ELSE 'UNKNOWN'
                               END,
                               row_data ->> 'road_address', row_data ->> 'lot_address',
                               ST_SetSRID(ST_MakePoint(
                                   (row_data ->> 'longitude')::double precision,
                                   (row_data ->> 'latitude')::double precision
                               ), 4326)::geography,
                               'EPSG:4326',
                               (row_data ->> 'longitude')::double precision,
                               (row_data ->> 'latitude')::double precision,
                               (row_data ->> 'reference_date')::date,
                               jsonb_build_object(
                                   'educationOfficeCode', row_data ->> 'education_office_code',
                                   'educationOfficeName', row_data ->> 'education_office_name'
                               )
                        FROM dataset_staging_row
                        WHERE acquisition_id = %s AND accepted = true
                        """,
                        (publication_id, acquisition["source_id"], acquisition_id),
                    )
                    connection.execute(
                        """
                        INSERT INTO reference_projection.source_coverage(
                            publication_id, source_id, region_code, total_count,
                            spatial_count, non_spatial_count, open_count,
                            stale_row_count, unknown_region_count
                        )
                        SELECT %s, %s, '__UNKNOWN__', count(*), count(*), 0,
                               count(*) FILTER (WHERE row_data ->> 'operating_status' = '운영'),
                               0, count(*)
                        FROM dataset_staging_row
                        WHERE acquisition_id = %s AND accepted = true
                        """,
                        (publication_id, acquisition["source_id"], acquisition_id),
                    )
                elif acquisition["source_id"] == "retail.large-store":
                    connection.execute(
                        """
                        INSERT INTO reference_projection.facility_point(
                            publication_id, source_id, fact_id, category, subcategory,
                            name, status, road_address, lot_address, region_code,
                            region_name, position, original_crs, original_x, original_y,
                            row_reference_date, attributes
                        )
                        SELECT %s, %s, row_data ->> 'facility_id',
                               row_data ->> 'category', row_data ->> 'subcategory',
                               row_data ->> 'name', row_data ->> 'status',
                               row_data ->> 'road_address', row_data ->> 'lot_address',
                               row_data ->> 'region_code', row_data ->> 'region_name',
                               ST_SetSRID(ST_MakePoint(
                                   (row_data ->> 'longitude')::double precision,
                                   (row_data ->> 'latitude')::double precision
                               ), 4326)::geography,
                               row_data ->> 'original_crs',
                               (row_data ->> 'original_x')::double precision,
                               (row_data ->> 'original_y')::double precision,
                               (row_data ->> 'reference_date')::date,
                               '{}'::jsonb
                        FROM dataset_staging_row
                        WHERE acquisition_id = %s AND accepted = true
                          AND row_data ->> 'fact_kind' = 'POINT'
                        """,
                        (publication_id, acquisition["source_id"], acquisition_id),
                    )
                    connection.execute(
                        """
                        INSERT INTO reference_projection.registry_fact(
                            publication_id, source_id, fact_id, category, subcategory,
                            name, status, road_address, lot_address, region_code,
                            region_name, row_reference_date, attributes
                        )
                        SELECT %s, %s, row_data ->> 'facility_id',
                               row_data ->> 'category', row_data ->> 'subcategory',
                               row_data ->> 'name', row_data ->> 'status',
                               row_data ->> 'road_address', row_data ->> 'lot_address',
                               row_data ->> 'region_code', row_data ->> 'region_name',
                               (row_data ->> 'reference_date')::date, '{}'::jsonb
                        FROM dataset_staging_row
                        WHERE acquisition_id = %s AND accepted = true
                          AND row_data ->> 'fact_kind' = 'REGISTRY'
                        """,
                        (publication_id, acquisition["source_id"], acquisition_id),
                    )
                    connection.execute(
                        """
                        INSERT INTO reference_projection.source_coverage(
                            publication_id, source_id, region_code, total_count,
                            spatial_count, non_spatial_count, open_count,
                            stale_row_count, unknown_region_count
                        )
                        SELECT %s, %s,
                               COALESCE(NULLIF(row_data ->> 'region_code', ''), '__UNKNOWN__'),
                               count(*),
                               count(*) FILTER (WHERE row_data ->> 'fact_kind' = 'POINT'),
                               count(*) FILTER (WHERE row_data ->> 'fact_kind' = 'REGISTRY'),
                               count(*) FILTER (WHERE row_data ->> 'status' = 'OPEN'),
                               0,
                               count(*) FILTER (
                                   WHERE NULLIF(row_data ->> 'region_code', '') IS NULL
                               )
                        FROM dataset_staging_row
                        WHERE acquisition_id = %s AND accepted = true
                        GROUP BY COALESCE(
                            NULLIF(row_data ->> 'region_code', ''), '__UNKNOWN__'
                        )
                        """,
                        (publication_id, acquisition["source_id"], acquisition_id),
                    )
                connection.execute(
                    """
                    INSERT INTO dataset_active_snapshot(source_id, publication_id, activated_at)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (source_id) DO UPDATE
                    SET publication_id = EXCLUDED.publication_id, activated_at = EXCLUDED.activated_at
                    """,
                    (acquisition["source_id"], publication_id, published_at),
                )
                connection.execute(
                    "UPDATE dataset_acquisition SET status = 'PUBLISHED' WHERE acquisition_id = %s",
                    (acquisition_id,),
                )
                connection.execute(
                    """
                    INSERT INTO dataset_activation_event(
                        source_id, publication_id, action, activated_at
                    ) VALUES (%s, %s, 'PUBLISH', %s)
                    """,
                    (acquisition["source_id"], publication_id, published_at),
                )
                return publication_id
        except psycopg.Error as exception:
            raise PublicationStoreError("dataset publication transaction failed") from exception

    def record_publication_failure(self, acquisition_id: UUID, recorded_at: datetime) -> None:
        with self._connect() as connection:
            self._insert_issue(
                connection,
                acquisition_id,
                "PUBLICATION_FAILED",
                "BLOCKING",
                None,
                {},
                recorded_at,
            )
            connection.execute(
                """
                UPDATE dataset_acquisition SET status = 'PUBLICATION_FAILED'
                WHERE acquisition_id = %s AND status = 'VALIDATED'
                """,
                (acquisition_id,),
            )

    def result(self, acquisition_id: UUID, *, idempotent: bool) -> LifecycleResult:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT a.source_id, a.acquisition_id, a.checksum, a.normalized_checksum,
                       a.temporal_basis, a.source_date, a.observed_at, a.collected_at,
                       a.status, a.raw_row_count, a.accepted_row_count, a.rejected_row_count,
                       p.publication_id, p.dataset_version,
                       COALESCE(array_agg(DISTINCT q.reason_code) FILTER (WHERE q.reason_code IS NOT NULL), '{}') AS issue_codes
                FROM dataset_acquisition a
                LEFT JOIN dataset_publication p ON p.acquisition_id = a.acquisition_id
                LEFT JOIN dataset_quality_issue q ON q.acquisition_id = a.acquisition_id
                WHERE a.acquisition_id = %s
                GROUP BY a.acquisition_id, p.publication_id
                """,
                (acquisition_id,),
            ).fetchone()
        status = (
            "Pass"
            if row["status"] == "PUBLISHED"
            else "NoChange"
            if row["status"] == "NO_CHANGE"
            else "Fail"
        )
        return LifecycleResult(
            status=status,
            source_id=row["source_id"],
            acquisition_id=row["acquisition_id"],
            publication_id=row["publication_id"],
            dataset_version=row["dataset_version"],
            checksum=str(row["checksum"]),
            source_date=row["source_date"],
            collected_at=row["collected_at"],
            raw_row_count=row["raw_row_count"],
            accepted_row_count=row["accepted_row_count"],
            rejected_row_count=row["rejected_row_count"],
            issue_codes=tuple(sorted(row["issue_codes"])),
            idempotent=idempotent,
            normalized_checksum=row["normalized_checksum"],
            temporal_basis=row["temporal_basis"],
            observed_at=row["observed_at"],
        )

    def active_snapshot(self, source_id: str) -> ActiveSnapshot | None:
        with self._connect() as connection:
            publication = connection.execute(
                """
                SELECT p.publication_id, p.acquisition_id, p.dataset_version,
                       p.source_date, p.observed_at, p.normalized_checksum, p.published_at
                FROM dataset_active_snapshot active
                JOIN dataset_publication p ON p.publication_id = active.publication_id
                WHERE active.source_id = %s
                """,
                (source_id,),
            ).fetchone()
            if not publication:
                return None
            rows = connection.execute(
                """
                SELECT row_data FROM dataset_snapshot_row
                WHERE publication_id = %s ORDER BY source_key
                """,
                (publication["publication_id"],),
            ).fetchall()
        return ActiveSnapshot(
            source_id=source_id,
            publication_id=publication["publication_id"],
            acquisition_id=publication["acquisition_id"],
            dataset_version=publication["dataset_version"],
            source_date=publication["source_date"],
            published_at=publication["published_at"],
            rows=tuple(row["row_data"] for row in rows),
            normalized_checksum=publication["normalized_checksum"],
            observed_at=publication["observed_at"],
        )

    def active_snapshot_state(self, source_id: str) -> ActiveSnapshotState | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT publication.normalized_checksum, count(snapshot.source_key) AS row_count
                FROM dataset_active_snapshot active
                JOIN dataset_publication publication
                  ON publication.publication_id = active.publication_id
                LEFT JOIN dataset_snapshot_row snapshot
                  ON snapshot.publication_id = publication.publication_id
                WHERE active.source_id = %s
                GROUP BY publication.publication_id
                """,
                (source_id,),
            ).fetchone()
        if row is None:
            return None
        return ActiveSnapshotState(
            normalized_checksum=row["normalized_checksum"],
            row_count=row["row_count"],
        )

    def rollback(self, source_id: str, publication_id: UUID, activated_at: datetime) -> None:
        with self._connect() as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (source_id,)
            )
            target = connection.execute(
                "SELECT 1 FROM dataset_publication WHERE source_id = %s AND publication_id = %s",
                (source_id, publication_id),
            ).fetchone()
            if not target:
                raise ValueError("rollback publication does not belong to source")
            connection.execute(
                """
                INSERT INTO dataset_active_snapshot(source_id, publication_id, activated_at)
                VALUES (%s, %s, %s)
                ON CONFLICT (source_id) DO UPDATE
                SET publication_id = EXCLUDED.publication_id, activated_at = EXCLUDED.activated_at
                """,
                (source_id, publication_id, activated_at),
            )
            connection.execute(
                """
                INSERT INTO dataset_activation_event(
                    source_id, publication_id, action, activated_at
                ) VALUES (%s, %s, 'ROLLBACK', %s)
                """,
                (source_id, publication_id, activated_at),
            )

    def rejected_rows(self, acquisition_id: UUID) -> tuple[RejectedRow, ...]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT row_number, reason_code, row_data FROM dataset_rejected_row
                WHERE acquisition_id = %s ORDER BY row_number, reason_code
                """,
                (acquisition_id,),
            ).fetchall()
        return tuple(RejectedRow(**row) for row in rows)

    def raw_bytes(self, checksum: str) -> bytes | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT content FROM dataset_raw_object WHERE checksum = %s", (checksum,)
            ).fetchone()
        return bytes(row["content"]) if row else None

    def table_counts(self) -> dict[str, int]:
        with self._connect() as connection:
            return {
                "raw_objects": connection.execute("SELECT count(*) AS count FROM dataset_raw_object").fetchone()["count"],
                "acquisitions": connection.execute("SELECT count(*) AS count FROM dataset_acquisition").fetchone()["count"],
                "publications": connection.execute("SELECT count(*) AS count FROM dataset_publication").fetchone()["count"],
            }

    def publication_count(self, source_id: str) -> int:
        with self._connect() as connection:
            return connection.execute(
                "SELECT count(*) AS count FROM dataset_publication WHERE source_id = %s", (source_id,)
            ).fetchone()["count"]

    def activation_actions(self, source_id: str) -> tuple[str, ...]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT action FROM dataset_activation_event
                WHERE source_id = %s ORDER BY activation_event_id
                """,
                (source_id,),
            ).fetchall()
        return tuple(row["action"] for row in rows)

    def _connect(self) -> psycopg.Connection:
        connection = psycopg.connect(self._dsn, row_factory=dict_row)
        try:
            if (
                self._expected_database is not None
                and connection.info.dbname != self._expected_database
            ):
                raise ValueError("dataset DSN must target the expected database")
            if (
                self._expected_username is not None
                and connection.info.user != self._expected_username
            ):
                raise ValueError("dataset DSN must use the expected role")
        except Exception:
            connection.close()
            raise
        return connection

    @staticmethod
    def _insert_issue(
        connection: psycopg.Connection,
        acquisition_id: UUID,
        reason_code: str,
        severity: str,
        row_number: int | None,
        details: dict[str, object],
        recorded_at: datetime,
    ) -> None:
        connection.execute(
            """
            INSERT INTO dataset_quality_issue(
                acquisition_id, reason_code, severity, row_number, details, recorded_at
            ) VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (acquisition_id, reason_code, severity, row_number, Jsonb(details), recorded_at),
        )


def _rejected_field_name(reason_code: str) -> str | None:
    return {
        "REQUIRED_FIELD_MISSING": "required_fields",
        "INVALID_COORDINATE": "position",
        "DUPLICATE_UNIQUE_KEY": "source_key",
    }.get(reason_code)


def _outcome_rows(outcome: ValidationOutcome) -> Iterator:
    if outcome.spool_path is not None:
        return iter_spooled_rows(outcome.spool_path)
    return iter(outcome.staged_rows)


class SourceRefreshAlreadyRunning(RuntimeError):
    pass

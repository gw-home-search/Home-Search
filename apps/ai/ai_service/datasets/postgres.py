from __future__ import annotations

import hashlib
from datetime import date, datetime
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .models import (
    AcquisitionRecord,
    ActiveSnapshot,
    DatasetSourceContract,
    LifecycleResult,
    RejectedRow,
    ValidationOutcome,
)
from .service import PublicationStoreError


class PostgresDatasetRepository:
    def __init__(self, dsn: str) -> None:
        self._dsn = dsn

    def close(self) -> None:
        return None

    def migrate(self) -> None:
        migration = Path(__file__).with_name("migrations") / "0001_dataset_lifecycle.sql"
        sql = migration.read_text(encoding="utf-8")
        checksum = hashlib.sha256(sql.encode()).hexdigest()
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
            existing = connection.execute(
                "SELECT checksum FROM ai_schema_history WHERE version = %s", (1,)
            ).fetchone()
            if existing:
                if existing["checksum"] != checksum:
                    raise RuntimeError("applied AI migration checksum mismatch")
                return
            connection.execute(sql)
            connection.execute(
                "INSERT INTO ai_schema_history(version, description, checksum) VALUES (%s, %s, %s)",
                (1, "dataset lifecycle", checksum),
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
        source_date: date,
        collected_at: datetime,
    ) -> AcquisitionRecord:
        acquisition_id = uuid4()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO dataset_raw_object(checksum, content, byte_length, collected_at)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (checksum) DO NOTHING
                """,
                (checksum, raw_bytes, len(raw_bytes), collected_at),
            )
            inserted = connection.execute(
                """
                INSERT INTO dataset_acquisition(
                    acquisition_id, source_id, contract_id, checksum, source_date, collected_at, status
                ) VALUES (%s, %s, %s, %s, %s, %s, 'ACQUIRED')
                ON CONFLICT (source_id, checksum, contract_id) DO NOTHING
                RETURNING acquisition_id
                """,
                (acquisition_id, contract.source_id, contract_id, checksum, source_date, collected_at),
            ).fetchone()
            if inserted:
                return AcquisitionRecord(acquisition_id=inserted["acquisition_id"], created=True)
            existing = connection.execute(
                """
                SELECT acquisition_id FROM dataset_acquisition
                WHERE source_id = %s AND checksum = %s AND contract_id = %s
                """,
                (contract.source_id, checksum, contract_id),
            ).fetchone()
            return AcquisitionRecord(acquisition_id=existing["acquisition_id"], created=False)

    def record_validation(
        self, acquisition_id: UUID, outcome: ValidationOutcome, validated_at: datetime
    ) -> None:
        status = "QUALITY_FAILED" if outcome.has_blocking_issues else "VALIDATED"
        with self._connect() as connection:
            for row in outcome.staged_rows:
                connection.execute(
                    """
                    INSERT INTO dataset_staging_row(
                        acquisition_id, row_number, row_data, source_key, accepted
                    ) VALUES (%s, %s, %s, %s, %s)
                    """,
                    (acquisition_id, row.row_number, Jsonb(row.row_data), row.source_key, row.accepted),
                )
                for reason_code in row.rejection_codes:
                    connection.execute(
                        """
                        INSERT INTO dataset_rejected_row(
                            acquisition_id, row_number, reason_code, row_data, recorded_at
                        ) VALUES (%s, %s, %s, %s, %s)
                        """,
                        (acquisition_id, row.row_number, reason_code, Jsonb(row.row_data), validated_at),
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
                    rejected_row_count = %s, validated_at = %s
                WHERE acquisition_id = %s AND status = 'ACQUIRED'
                """,
                (
                    status,
                    outcome.raw_row_count,
                    outcome.accepted_row_count,
                    outcome.rejected_row_count,
                    validated_at,
                    acquisition_id,
                ),
            )

    def record_parse_failure(self, acquisition_id: UUID, recorded_at: datetime) -> None:
        with self._connect() as connection:
            self._insert_issue(
                connection,
                acquisition_id,
                "RAW_PARSE_FAILED",
                "BLOCKING",
                None,
                {},
                recorded_at,
            )
            connection.execute(
                """
                UPDATE dataset_acquisition
                SET status = 'QUALITY_FAILED', validated_at = %s
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
                    SELECT source_id, source_date, checksum FROM dataset_acquisition
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
                version_prefix = acquisition["source_date"].isoformat()
                dataset_version = f"{version_prefix}-{str(acquisition['checksum'])[:12]}"
                connection.execute(
                    """
                    INSERT INTO dataset_publication(
                        publication_id, source_id, acquisition_id, dataset_version, source_date, published_at
                    ) VALUES (%s, %s, %s, %s, %s, %s)
                    """,
                    (
                        publication_id,
                        acquisition["source_id"],
                        acquisition_id,
                        dataset_version,
                        acquisition["source_date"],
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
                SELECT a.source_id, a.acquisition_id, a.checksum, a.source_date, a.collected_at,
                       a.status, a.raw_row_count, a.accepted_row_count, a.rejected_row_count,
                       p.publication_id,
                       COALESCE(array_agg(DISTINCT q.reason_code) FILTER (WHERE q.reason_code IS NOT NULL), '{}') AS issue_codes
                FROM dataset_acquisition a
                LEFT JOIN dataset_publication p ON p.acquisition_id = a.acquisition_id
                LEFT JOIN dataset_quality_issue q ON q.acquisition_id = a.acquisition_id
                WHERE a.acquisition_id = %s
                GROUP BY a.acquisition_id, p.publication_id
                """,
                (acquisition_id,),
            ).fetchone()
        status = "Pass" if row["status"] == "PUBLISHED" else "Fail"
        return LifecycleResult(
            status=status,
            source_id=row["source_id"],
            acquisition_id=row["acquisition_id"],
            publication_id=row["publication_id"],
            checksum=str(row["checksum"]),
            source_date=row["source_date"],
            collected_at=row["collected_at"],
            raw_row_count=row["raw_row_count"],
            accepted_row_count=row["accepted_row_count"],
            rejected_row_count=row["rejected_row_count"],
            issue_codes=tuple(sorted(row["issue_codes"])),
            idempotent=idempotent,
        )

    def active_snapshot(self, source_id: str) -> ActiveSnapshot | None:
        with self._connect() as connection:
            publication = connection.execute(
                """
                SELECT p.publication_id, p.acquisition_id, p.dataset_version,
                       p.source_date, p.published_at
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
        return psycopg.connect(self._dsn, row_factory=dict_row)

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

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@dataclass(frozen=True)
class AcademyRegistrySummary:
    education_office_code: str
    education_office_name: str
    district_name: str
    total_count: int
    open_count: int
    dataset_version: str
    observed_at: datetime
    freshness_days: int


class PostgresAcademyRegistryRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search_ai",
        expected_username: str = "home_search_ai_runtime",
        min_pool_size: int = 1,
        max_pool_size: int = 5,
    ) -> None:
        if (
            not dsn.strip()
            or not expected_database.strip()
            or not expected_username.strip()
            or not 1 <= min_pool_size <= max_pool_size <= 20
        ):
            raise ValueError("academy registry database configuration is invalid")
        self._pool = ConnectionPool(
            conninfo=dsn,
            min_size=min_pool_size,
            max_size=max_pool_size,
            kwargs={
                "row_factory": dict_row,
                "options": "-c default_transaction_read_only=on -c statement_timeout=3000",
            },
            open=True,
        )
        try:
            with self._pool.connection() as connection:
                if connection.info.dbname != expected_database:
                    raise ValueError("academy registry DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("academy registry DSN must use the AI runtime role")
        except Exception:
            self._pool.close()
            raise

    def close(self) -> None:
        self._pool.close()

    def summary(
        self, *, education_office_name: str, district_name: str
    ) -> AcademyRegistrySummary | None:
        if (
            not education_office_name.strip()
            or len(education_office_name) > 100
            or education_office_name != education_office_name.strip()
            or not district_name.strip()
            or len(district_name) > 100
            or district_name != district_name.strip()
        ):
            raise ValueError("academy registry region query is invalid")
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT education_office_code, education_office_name, district_name,
                       sum(registry_count)::bigint AS total_count,
                       sum(registry_count) FILTER (WHERE status = 'OPEN')::bigint AS open_count,
                       dataset_version, observed_at, freshness_days
                FROM reference_read.academy_registry_summary
                WHERE education_office_name = %s
                  AND district_name = %s
                GROUP BY education_office_code, education_office_name, district_name,
                         dataset_version, observed_at, freshness_days
                """,
                (education_office_name, district_name),
            ).fetchall()
        if not rows:
            return None
        if len(rows) != 1:
            raise RuntimeError("academy registry region identity is ambiguous")
        row = rows[0]
        return AcademyRegistrySummary(
            education_office_code=str(row["education_office_code"]),
            education_office_name=str(row["education_office_name"]),
            district_name=str(row["district_name"]),
            total_count=int(row["total_count"]),
            open_count=int(row["open_count"] or 0),
            dataset_version=str(row["dataset_version"]),
            observed_at=row["observed_at"],
            freshness_days=int(row["freshness_days"]),
        )

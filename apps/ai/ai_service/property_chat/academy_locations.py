from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import math

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@dataclass(frozen=True)
class RegistryExactMatch:
    registry_fact_id: str
    academy_name: str
    status: str
    dataset_version: str
    observed_at: datetime


@dataclass(frozen=True)
class AcademyLocation:
    store_id: str
    name: str
    small_category_code: str
    status: str
    address: str | None
    distance_meters: int
    dataset_version: str
    observed_at: datetime
    registry_match: RegistryExactMatch | None


@dataclass(frozen=True)
class AcademyLocationSearchResult:
    locations: tuple[AcademyLocation, ...]
    matched_count: int
    coordinate_coverage: float
    dataset_version: str
    observed_at: datetime
    verified_zero: bool
    freshness_days: int = 45

    @property
    def returned_count(self) -> int:
        return len(self.locations)

    @property
    def has_more(self) -> bool:
        return self.matched_count > self.returned_count


class PostgresAcademyLocationRepository:
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
            raise ValueError("academy location database configuration is invalid")
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
                    raise ValueError("academy location DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("academy location DSN must use the AI runtime role")
        except Exception:
            self._pool.close()
            raise

    def close(self) -> None:
        self._pool.close()

    def nearby(
        self,
        *,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
    ) -> AcademyLocationSearchResult:
        _validate_query(latitude, longitude, radius_meters, limit)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH origin AS (
                    SELECT ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography AS point
                ), within_radius AS (
                    SELECT fact.*,
                           COALESCE(fact.road_address, fact.lot_address) AS address,
                           ST_Distance(fact.position, origin.point) AS distance_meters,
                           count(*) OVER () AS matched_count
                    FROM reference_read.sbiz_academy_fact fact
                    CROSS JOIN origin
                    WHERE fact.status = 'OPEN'
                      AND ST_DWithin(fact.position, origin.point, %s + 0.001)
                )
                SELECT * FROM within_radius
                ORDER BY distance_meters, sbiz_fact_id
                LIMIT %s
                """,
                (longitude, latitude, radius_meters, limit),
            ).fetchall()
            coverage = connection.execute(
                """
                SELECT sum(coverage.total_count)::bigint AS total_count,
                       sum(coverage.spatial_count)::bigint AS spatial_count,
                       metadata.dataset_version, metadata.observed_at,
                       metadata.freshness_days
                FROM reference_read.active_source_metadata metadata
                LEFT JOIN reference_read.source_coverage coverage
                  ON coverage.publication_id = metadata.publication_id
                WHERE metadata.source_id = 'place.sbiz-academy'
                GROUP BY metadata.dataset_version, metadata.observed_at,
                         metadata.freshness_days
                """
            ).fetchone()
        if (
            coverage is None
            or coverage["total_count"] is None
            or int(coverage["total_count"]) <= 0
            or coverage["observed_at"] is None
        ):
            raise RuntimeError("active Sbiz academy coverage is missing")
        total_count = int(coverage["total_count"])
        coordinate_coverage = int(coverage["spatial_count"] or 0) / total_count
        return AcademyLocationSearchResult(
            locations=tuple(_location(row) for row in rows),
            matched_count=int(rows[0]["matched_count"]) if rows else 0,
            coordinate_coverage=coordinate_coverage,
            dataset_version=str(coverage["dataset_version"]),
            observed_at=coverage["observed_at"],
            verified_zero=False,
            freshness_days=int(coverage["freshness_days"]),
        )


def _location(row: dict[str, object]) -> AcademyLocation:
    match = None
    if row["registry_match"] == "EXACT":
        required = (
            row["registry_fact_id"],
            row["registry_academy_name"],
            row["registry_status"],
            row["registry_dataset_version"],
            row["registry_observed_at"],
        )
        if any(value is None for value in required):
            raise RuntimeError("exact academy registry evidence is incomplete")
        match = RegistryExactMatch(
            registry_fact_id=str(row["registry_fact_id"]),
            academy_name=str(row["registry_academy_name"]),
            status=str(row["registry_status"]),
            dataset_version=str(row["registry_dataset_version"]),
            observed_at=row["registry_observed_at"],  # type: ignore[arg-type]
        )
    elif row["registry_match"] != "UNMATCHED":
        raise RuntimeError("academy registry match type is invalid")
    return AcademyLocation(
        store_id=str(row["sbiz_fact_id"]),
        name=str(row["name"]),
        small_category_code=str(row["small_category_code"]),
        status=str(row["status"]),
        address=str(row["address"]) if row["address"] is not None else None,
        distance_meters=round(float(row["distance_meters"])),
        dataset_version=str(row["dataset_version"]),
        observed_at=row["observed_at"],  # type: ignore[arg-type]
        registry_match=match,
    )


def _validate_query(
    latitude: float, longitude: float, radius_meters: int, limit: int
) -> None:
    if (
        not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or not 33 <= latitude <= 39
        or not 124 <= longitude <= 132
        or not 100 <= radius_meters <= 2000
        or not 1 <= limit <= 5
    ):
        raise ValueError("academy location query is invalid")

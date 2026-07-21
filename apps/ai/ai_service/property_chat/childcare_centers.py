from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@dataclass(frozen=True)
class ChildcareCenter:
    center_id: str
    center_name: str
    center_type: str
    capacity: int
    distance_meters: int
    reference_date: date
    dataset_version: str


@dataclass(frozen=True)
class ChildcareSearchResult:
    centers: tuple[ChildcareCenter, ...]
    matched_count: int
    returned_count: int
    has_more: bool
    verified_zero: bool
    coordinate_coverage: float | None
    dataset_version: str
    observed_at: datetime
    freshness_days: int


class PostgresChildcareRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search_ai",
        expected_username: str = "home_search_ai_runtime",
        min_pool_size: int = 1,
        max_pool_size: int = 5,
    ) -> None:
        if not dsn.strip():
            raise ValueError("childcare reference DSN is required")
        if not expected_database.strip() or not expected_username.strip():
            raise ValueError("childcare database boundary is required")
        if not 1 <= min_pool_size <= max_pool_size <= 20:
            raise ValueError("childcare pool size is invalid")
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
                    raise ValueError("childcare DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("childcare DSN must use the AI runtime role")
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
        region_code: str | None,
    ) -> ChildcareSearchResult | None:
        _validate_query(latitude, longitude, radius_meters, limit, region_code)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH origin AS (
                    SELECT ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography AS point
                ), within_radius AS (
                    SELECT fact.fact_id AS center_id,
                           fact.name AS center_name,
                           fact.subcategory AS center_type,
                           (fact.attributes ->> 'capacity')::integer AS capacity,
                           ST_Distance(fact.position, origin.point) AS distance_meters,
                           fact.row_reference_date AS reference_date,
                           fact.dataset_version,
                           count(*) OVER () AS matched_count
                    FROM reference_read.facility_point_fact fact
                    CROSS JOIN origin
                    WHERE fact.source_id = 'childcare.center'
                      AND fact.category = 'CHILDCARE'
                      AND fact.status = 'OPEN'
                      AND ST_DWithin(fact.position, origin.point, %s + 0.001)
                )
                SELECT * FROM within_radius
                ORDER BY distance_meters, center_id
                LIMIT %s
                """,
                (longitude, latitude, radius_meters, limit),
            ).fetchall()
            coverage = connection.execute(
                """
                SELECT target.total_count, target.spatial_count,
                       target.non_spatial_count, target.stale_row_count,
                       COALESCE(all_regions.unknown_region_count, 0) AS unknown_region_count,
                       metadata.observed_at, metadata.freshness_days,
                       metadata.dataset_version
                FROM reference_read.active_source_metadata metadata
                LEFT JOIN reference_read.source_coverage target
                  ON target.publication_id = metadata.publication_id
                 AND target.region_code = %s
                LEFT JOIN (
                    SELECT publication_id, sum(unknown_region_count) AS unknown_region_count
                    FROM reference_read.source_coverage GROUP BY publication_id
                ) all_regions ON all_regions.publication_id = metadata.publication_id
                WHERE metadata.source_id = 'childcare.center'
                """,
                (region_code,),
            ).fetchone()
        if coverage is None:
            return None
        observed_at = coverage["observed_at"]
        freshness_days = coverage["freshness_days"]
        if not isinstance(observed_at, datetime) or freshness_days is None:
            return None
        centers = tuple(_center(row) for row in rows)
        matched_count = int(rows[0]["matched_count"]) if rows else 0
        coordinate_coverage = _coverage_ratio(coverage)
        return ChildcareSearchResult(
            centers=centers,
            matched_count=matched_count,
            returned_count=len(centers),
            has_more=matched_count > len(centers),
            verified_zero=(
                matched_count == 0 and _complete_region_coverage(coverage)
            ),
            coordinate_coverage=coordinate_coverage,
            dataset_version=str(coverage["dataset_version"]),
            observed_at=observed_at,
            freshness_days=int(freshness_days),
        )


def _center(row: dict[str, object]) -> ChildcareCenter:
    reference_date = row["reference_date"]
    if not isinstance(reference_date, date):
        raise RuntimeError("childcare reference date is invalid")
    return ChildcareCenter(
        center_id=str(row["center_id"]),
        center_name=str(row["center_name"]),
        center_type=str(row["center_type"]),
        capacity=int(row["capacity"]),
        distance_meters=round(float(row["distance_meters"])),
        reference_date=reference_date,
        dataset_version=str(row["dataset_version"]),
    )


def _coverage_ratio(row: dict[str, object]) -> float | None:
    total_count = row["total_count"]
    spatial_count = row["spatial_count"]
    if total_count is None or spatial_count is None or int(total_count) == 0:
        return None
    return int(spatial_count) / int(total_count)


def _complete_region_coverage(row: dict[str, object]) -> bool:
    ratio = _coverage_ratio(row)
    return (
        ratio is not None
        and ratio >= 0.9
        and int(row["non_spatial_count"] or 0) == 0
        and int(row["stale_row_count"] or 0) == 0
        and int(row["unknown_region_count"] or 0) == 0
    )


def _validate_query(
    latitude: float,
    longitude: float,
    radius_meters: int,
    limit: int,
    region_code: str | None,
) -> None:
    if (
        not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
        or not 100 <= radius_meters <= 2000
        or not 1 <= limit <= 5
        or (
            region_code is not None
            and (
                len(region_code) != 5
                or not region_code.isascii()
                or not region_code.isdigit()
            )
        )
    ):
        raise ValueError("childcare query is invalid")

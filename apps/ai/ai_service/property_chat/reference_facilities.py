from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .comparison import CandidatePoint


RETAIL_MIN_COORDINATE_COVERAGE = 0.88


@dataclass(frozen=True)
class FacilityFact:
    fact_id: str
    name: str
    category: str
    subcategory: str | None
    status: str
    address: str | None
    distance_meters: int
    dataset_version: str
    data_as_of: date | datetime


@dataclass(frozen=True)
class FacilitySearchResult:
    facilities: tuple[FacilityFact, ...]
    matched_count: int
    returned_count: int
    has_more: bool
    verified_zero: bool
    coordinate_coverage: float | None
    dataset_version: str
    data_as_of: date | datetime


class PostgresPointFacilityRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search_ai",
        expected_username: str = "home_search_ai_runtime",
        min_pool_size: int = 1,
        max_pool_size: int = 5,
    ) -> None:
        if not dsn.strip() or not 1 <= min_pool_size <= max_pool_size <= 20:
            raise ValueError("reference facility database configuration is invalid")
        self._pool = ConnectionPool(
            conninfo=dsn,
            min_size=min_pool_size,
            max_size=max_pool_size,
            kwargs={
                "row_factory": dict_row,
                "options": "-c default_transaction_read_only=on -c statement_timeout=20000",
            },
            open=True,
        )
        try:
            with self._pool.connection() as connection:
                if connection.info.dbname != expected_database:
                    raise ValueError("reference DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("reference DSN must use the AI runtime role")
        except Exception:
            self._pool.close()
            raise

    def close(self) -> None:
        self._pool.close()

    def nearby(
        self,
        *,
        source_id: str,
        category: str,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
        region_code: str,
        subcategories: tuple[str, ...] = (),
    ) -> FacilitySearchResult:
        _validate_query(latitude, longitude, radius_meters, limit, region_code, subcategories)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH origin AS (
                    SELECT ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography AS point
                ), within_radius AS (
                    SELECT fact.fact_id, fact.name, fact.category, fact.subcategory,
                           fact.status, COALESCE(fact.road_address, fact.lot_address) AS address,
                           ST_Distance(fact.position, origin.point) AS distance_meters,
                           fact.dataset_version, fact.temporal_basis, fact.source_date,
                           fact.dataset_observed_at, count(*) OVER () AS matched_count
                    FROM reference_read.facility_point_fact fact
                    CROSS JOIN origin
                    WHERE fact.source_id = %s
                      AND fact.category = %s
                      AND fact.status = 'OPEN'
                      AND (%s = '{}'::text[] OR fact.subcategory = ANY(%s))
                      AND ST_DWithin(fact.position, origin.point, %s + 0.001)
                )
                SELECT * FROM within_radius
                ORDER BY distance_meters, fact_id
                LIMIT %s
                """,
                (
                    longitude,
                    latitude,
                    source_id,
                    category,
                    list(subcategories),
                    list(subcategories),
                    radius_meters,
                    limit,
                ),
            ).fetchall()
            coverage = connection.execute(
                """
                SELECT sum(coverage.total_count)::bigint AS total_count,
                       sum(coverage.spatial_count)::bigint AS spatial_count,
                       sum(coverage.non_spatial_count)::bigint AS non_spatial_count,
                       sum(coverage.stale_row_count)::bigint AS stale_row_count,
                       sum(coverage.unknown_region_count)::bigint AS unknown_region_count,
                       metadata.temporal_basis, metadata.source_date,
                       metadata.observed_at, metadata.freshness_days,
                       metadata.dataset_version
                FROM reference_read.active_source_metadata metadata
                LEFT JOIN reference_read.source_coverage coverage
                  ON coverage.publication_id = metadata.publication_id
                WHERE metadata.source_id = %s
                GROUP BY metadata.temporal_basis, metadata.source_date,
                         metadata.observed_at, metadata.freshness_days,
                         metadata.dataset_version
                """,
                (source_id,),
            ).fetchone()
        facilities = tuple(_facility(row) for row in rows)
        if coverage is None:
            raise RuntimeError("active source metadata is missing")
        matched_count = int(rows[0]["matched_count"]) if rows else 0
        coordinate_coverage = _coverage_ratio(coverage)
        verified_zero = (
            matched_count == 0
            and source_id != "retail.large-store"
            and _verified_zero(coverage)
        )
        data_as_of = coverage["source_date"] or coverage["observed_at"]
        if data_as_of is None:
            raise RuntimeError("active source temporal value is missing")
        return FacilitySearchResult(
            facilities=facilities,
            matched_count=matched_count,
            returned_count=len(facilities),
            has_more=matched_count > len(facilities),
            verified_zero=verified_zero,
            coordinate_coverage=coordinate_coverage,
            dataset_version=str(coverage["dataset_version"]),
            data_as_of=data_as_of,  # type: ignore[arg-type]
        )

    def nearest_batch(
        self,
        *,
        source_id: str,
        category: str,
        points: tuple[CandidatePoint, ...],
        radius_meters: int,
    ) -> dict[int, FacilitySearchResult] | None:
        if (
            source_id != "retail.large-store"
            or category != "LARGE_STORE"
            or not 1 <= len(points) <= 100
            or len({point.complex_id for point in points}) != len(points)
            or not 100 <= radius_meters <= 3000
        ):
            raise ValueError("reference facility batch query is invalid")
        for point in points:
            _validate_query(
                point.latitude, point.longitude, radius_meters, 1,
                point.region_code or "00000", (),
            )
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH candidates AS (
                    SELECT complex_id, latitude, longitude
                    FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                         AS value(complex_id, latitude, longitude)
                )
                SELECT candidate.complex_id, nearest.*
                FROM candidates candidate
                JOIN LATERAL (
                    SELECT fact.fact_id, fact.name, fact.category, fact.subcategory,
                           fact.status, COALESCE(fact.road_address, fact.lot_address) AS address,
                           ST_Distance(
                               fact.position,
                               ST_SetSRID(ST_MakePoint(
                                   candidate.longitude, candidate.latitude
                               ), 4326)::geography
                           ) AS distance_meters,
                           fact.dataset_version, fact.source_date,
                           fact.dataset_observed_at
                    FROM reference_read.facility_point_fact fact
                    WHERE fact.source_id = %s
                      AND fact.category = %s
                      AND fact.status = 'OPEN'
                      AND ST_DWithin(
                          fact.position,
                          ST_SetSRID(ST_MakePoint(
                              candidate.longitude, candidate.latitude
                          ), 4326)::geography,
                          %s + 0.001
                      )
                    ORDER BY distance_meters, fact.fact_id
                    LIMIT 1
                ) nearest ON true
                ORDER BY candidate.complex_id
                """,
                (
                    [point.complex_id for point in points],
                    [point.latitude for point in points],
                    [point.longitude for point in points],
                    source_id,
                    "RETAIL",
                    radius_meters,
                ),
            ).fetchall()
            metadata = connection.execute(
                """
                SELECT metadata.dataset_version, metadata.source_date,
                       metadata.observed_at,
                       sum(coverage.total_count)::bigint AS total_count,
                       sum(coverage.spatial_count)::bigint AS spatial_count
                FROM reference_read.active_source_metadata metadata
                LEFT JOIN reference_read.source_coverage coverage
                  ON coverage.publication_id = metadata.publication_id
                WHERE metadata.source_id = %s
                GROUP BY metadata.dataset_version, metadata.source_date,
                         metadata.observed_at
                """,
                (source_id,),
            ).fetchone()
        if (
            metadata is None
            or metadata["total_count"] is None
            or int(metadata["total_count"]) <= 0
        ):
            return None
        row_by_complex = {int(row["complex_id"]): row for row in rows}
        data_as_of = metadata["source_date"] or metadata["observed_at"]
        if data_as_of is None:
            return None
        coverage = int(metadata["spatial_count"] or 0) / int(metadata["total_count"])
        result: dict[int, FacilitySearchResult] = {}
        for point in points:
            row = row_by_complex.get(point.complex_id)
            facilities = () if row is None else (_facility(row),)
            result[point.complex_id] = FacilitySearchResult(
                facilities=facilities,
                matched_count=len(facilities),
                returned_count=len(facilities),
                has_more=False,
                verified_zero=False,
                coordinate_coverage=coverage,
                dataset_version=str(metadata["dataset_version"]),
                data_as_of=data_as_of,  # type: ignore[arg-type]
            )
        return result


def retail_coordinate_ready(result: FacilitySearchResult) -> bool:
    return (
        result.coordinate_coverage is not None
        and result.coordinate_coverage >= RETAIL_MIN_COORDINATE_COVERAGE
    )


def _facility(row: dict[str, object]) -> FacilityFact:
    data_as_of = row["source_date"] or row["dataset_observed_at"]
    return FacilityFact(
        fact_id=str(row["fact_id"]),
        name=str(row["name"]),
        category=str(row["category"]),
        subcategory=str(row["subcategory"]) if row["subcategory"] is not None else None,
        status=str(row["status"]),
        address=str(row["address"]) if row["address"] is not None else None,
        distance_meters=round(float(row["distance_meters"])),
        dataset_version=str(row["dataset_version"]),
        data_as_of=data_as_of,  # type: ignore[arg-type]
    )


def _coverage_ratio(row: dict[str, object] | None) -> float | None:
    if row is None or row["total_count"] is None or int(row["total_count"]) == 0:
        return None
    return int(row["spatial_count"]) / int(row["total_count"])


def _verified_zero(row: dict[str, object] | None) -> bool:
    if row is None or row["total_count"] is None:
        return False
    data_as_of = row["source_date"] or row["observed_at"]
    if data_as_of is None or row["freshness_days"] is None:
        return False
    as_of_date = data_as_of if isinstance(data_as_of, date) and not isinstance(data_as_of, datetime) else data_as_of.date()
    age_days = (date.today() - as_of_date).days
    fresh = 0 <= age_days <= int(row["freshness_days"])
    return (
        fresh
        and int(row["non_spatial_count"]) == 0
        and int(row["stale_row_count"]) == 0
        and int(row["unknown_region_count"]) == 0
    )


def _validate_query(
    latitude: float,
    longitude: float,
    radius_meters: int,
    limit: int,
    region_code: str,
    subcategories: tuple[str, ...],
) -> None:
    if (
        not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
        or not 100 <= radius_meters <= 3000
        or not 1 <= limit <= 5
        or not region_code.strip()
        or len(subcategories) != len(set(subcategories))
    ):
        raise ValueError("facility query is invalid")

from __future__ import annotations

import math

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .comparison import CandidatePoint
from .models import SchoolRecord, SchoolSearchResult, SchoolSnapshot


_EARTH_RADIUS_METERS = 6_371_000.0


class PostgresSchoolFactRepository:
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
            raise ValueError("reference DSN is required")
        if not expected_database.strip() or not expected_username.strip():
            raise ValueError("reference database boundary is required")
        if not 1 <= min_pool_size <= max_pool_size <= 20:
            raise ValueError("reference pool size is outside the supported range")
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

    def active_snapshot(self) -> SchoolSnapshot | None:
        with self._pool.connection() as connection:
            row = connection.execute(
                """
                SELECT dataset_version, reference_date, published_at
                FROM reference_read.school_location_fact
                LIMIT 1
                """
            ).fetchone()
        if row is None:
            return None
        return SchoolSnapshot(
            dataset_version=str(row["dataset_version"]),
            source_date=row["reference_date"],  # type: ignore[arg-type]
            published_at=row["published_at"],  # type: ignore[arg-type]
        )

    def nearby_schools(
        self,
        *,
        latitude: float,
        longitude: float,
        school_levels: tuple[str, ...],
        radius_meters: int,
        limit: int,
    ) -> SchoolSearchResult:
        _validate_query(latitude, longitude, school_levels, radius_meters, limit)
        angular_delta_degrees = math.degrees(radius_meters / _EARTH_RADIUS_METERS)
        latitude_delta = angular_delta_degrees
        longitude_delta = angular_delta_degrees / max(math.cos(math.radians(latitude)), 0.01)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH bounded AS (
                    SELECT school_id, school_name, school_level, operating_status,
                           road_address, lot_address, latitude, longitude,
                           %s * 2 * asin(sqrt(
                               power(sin(radians(latitude - %s) / 2), 2)
                               + cos(radians(%s)) * cos(radians(latitude))
                               * power(sin(radians(longitude - %s) / 2), 2)
                           )) AS distance_meters
                    FROM reference_read.school_location_fact
                    WHERE operating_status = '운영'
                      AND school_level = ANY(%s)
                      AND latitude BETWEEN %s AND %s
                      AND longitude BETWEEN %s AND %s
                ), within_radius AS (
                    SELECT *, count(*) OVER () AS matched_count
                    FROM bounded
                    WHERE distance_meters <= %s
                )
                SELECT * FROM within_radius
                ORDER BY distance_meters, school_id
                LIMIT %s
                """,
                (
                    _EARTH_RADIUS_METERS,
                    latitude,
                    latitude,
                    longitude,
                    list(school_levels),
                    latitude - latitude_delta,
                    latitude + latitude_delta,
                    longitude - longitude_delta,
                    longitude + longitude_delta,
                    radius_meters,
                    limit,
                ),
            ).fetchall()
        schools = tuple(
            SchoolRecord(
                school_id=str(row["school_id"]),
                school_name=str(row["school_name"]),
                school_level=str(row["school_level"]),  # type: ignore[arg-type]
                operating_status=str(row["operating_status"]),
                road_address=row["road_address"],  # type: ignore[arg-type]
                lot_address=row["lot_address"],  # type: ignore[arg-type]
                latitude=float(row["latitude"]),
                longitude=float(row["longitude"]),
                distance_meters=round(float(row["distance_meters"])),
            )
            for row in rows
        )
        matched_count = int(rows[0]["matched_count"]) if rows else 0
        return SchoolSearchResult(schools=schools, matched_count=matched_count)

    def nearest_by_level_batch(
        self,
        *,
        points: tuple[CandidatePoint, ...],
        school_levels: tuple[str, ...],
        radius_meters: int,
    ) -> tuple[SchoolSnapshot, dict[int, SchoolSearchResult]] | None:
        if (
            not 1 <= len(points) <= 100
            or len({point.complex_id for point in points}) != len(points)
        ):
            raise ValueError("school batch points are invalid")
        for point in points:
            _validate_query(
                point.latitude, point.longitude, school_levels, radius_meters, 5,
            )
        with self._pool.connection() as connection:
            metadata = connection.execute(
                """
                SELECT dataset_version, reference_date, published_at
                FROM reference_read.school_location_fact LIMIT 1
                """
            ).fetchone()
            rows = connection.execute(
                """
                WITH candidates AS (
                    SELECT complex_id, latitude, longitude
                    FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                         AS value(complex_id, latitude, longitude)
                ), measured AS (
                    SELECT candidate.complex_id, school.*,
                           %s * 2 * asin(sqrt(
                               power(sin(radians(school.latitude - candidate.latitude) / 2), 2)
                               + cos(radians(candidate.latitude)) * cos(radians(school.latitude))
                               * power(sin(radians(school.longitude - candidate.longitude) / 2), 2)
                           )) AS distance_meters
                    FROM candidates candidate
                    JOIN reference_read.school_location_fact school
                      ON school.operating_status = '운영'
                     AND school.school_level = ANY(%s)
                     AND school.latitude BETWEEN
                         candidate.latitude - degrees(%s / %s)
                         AND candidate.latitude + degrees(%s / %s)
                     AND school.longitude BETWEEN
                         candidate.longitude - degrees(%s / %s)
                           / greatest(cos(radians(candidate.latitude)), 0.01)
                         AND candidate.longitude + degrees(%s / %s)
                           / greatest(cos(radians(candidate.latitude)), 0.01)
                ), ranked AS (
                    SELECT *, row_number() OVER (
                        PARTITION BY complex_id, school_level
                        ORDER BY distance_meters, school_id
                    ) AS distance_rank
                    FROM measured WHERE distance_meters <= %s
                )
                SELECT * FROM ranked WHERE distance_rank = 1
                ORDER BY complex_id, school_level
                """,
                (
                    [point.complex_id for point in points],
                    [point.latitude for point in points],
                    [point.longitude for point in points],
                    _EARTH_RADIUS_METERS, list(school_levels),
                    radius_meters, _EARTH_RADIUS_METERS,
                    radius_meters, _EARTH_RADIUS_METERS,
                    radius_meters, _EARTH_RADIUS_METERS,
                    radius_meters, _EARTH_RADIUS_METERS,
                    radius_meters,
                ),
            ).fetchall()
        if metadata is None:
            return None
        snapshot = SchoolSnapshot(
            str(metadata["dataset_version"]), metadata["reference_date"],
            metadata["published_at"],
        )
        by_complex = {point.complex_id: [] for point in points}
        for row in rows:
            by_complex[int(row["complex_id"])].append(SchoolRecord(
                school_id=str(row["school_id"]),
                school_name=str(row["school_name"]),
                school_level=str(row["school_level"]),  # type: ignore[arg-type]
                operating_status=str(row["operating_status"]),
                road_address=row["road_address"],  # type: ignore[arg-type]
                lot_address=row["lot_address"],  # type: ignore[arg-type]
                latitude=float(row["latitude"]), longitude=float(row["longitude"]),
                distance_meters=round(float(row["distance_meters"])),
            ))
        return snapshot, {
            complex_id: SchoolSearchResult(tuple(schools), len(schools))
            for complex_id, schools in by_complex.items()
        }


def _validate_query(
    latitude: float,
    longitude: float,
    school_levels: tuple[str, ...],
    radius_meters: int,
    limit: int,
) -> None:
    if (
        not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
    ):
        raise ValueError("reference coordinate is invalid")
    if (
        not school_levels
        or len(school_levels) != len(set(school_levels))
        or any(level not in {"ELEMENTARY", "MIDDLE", "HIGH"} for level in school_levels)
    ):
        raise ValueError("school levels are invalid")
    if not 100 <= radius_meters <= 2000:
        raise ValueError("school radius is outside the supported range")
    if not 1 <= limit <= 5:
        raise ValueError("school limit is outside the supported range")

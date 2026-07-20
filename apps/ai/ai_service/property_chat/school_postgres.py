from __future__ import annotations

import math

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

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
                "options": "-c default_transaction_read_only=on -c statement_timeout=3000",
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

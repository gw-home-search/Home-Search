from __future__ import annotations

import math
import unicodedata
from dataclasses import dataclass
from datetime import date

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@dataclass(frozen=True)
class RailOccurrence:
    occurrence_id: str
    station_name: str
    line_name: str
    transfer_lines: tuple[str, ...]
    latitude: float
    longitude: float
    distance_meters: int


@dataclass(frozen=True)
class RailStation:
    station_name: str
    lines: tuple[str, ...]
    occurrence_ids: tuple[str, ...]
    distance_meters: int


@dataclass(frozen=True)
class RailStationSearchResult:
    stations: tuple[RailStation, ...]
    occurrence_count: int
    dataset_version: str
    source_date: date
    coordinate_coverage: float = 1.0
    freshness_days: int = 410


class PostgresRailStationRepository:
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
            raise ValueError("rail station database configuration is invalid")
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
                    raise ValueError("rail station DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("rail station DSN must use the AI runtime role")
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
    ) -> RailStationSearchResult:
        _validate_query(latitude, longitude, radius_meters, limit)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH origin AS (
                    SELECT ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography AS point
                )
                SELECT occurrence.occurrence_id, occurrence.station_name,
                       occurrence.line_name, occurrence.transfer_lines,
                       occurrence.latitude, occurrence.longitude,
                       ST_Distance(occurrence.position, origin.point) AS distance_meters
                FROM reference_read.rail_station_occurrence occurrence
                CROSS JOIN origin
                WHERE ST_DWithin(occurrence.position, origin.point, %s + 0.001)
                ORDER BY distance_meters, occurrence.occurrence_id
                LIMIT 25
                """,
                (longitude, latitude, radius_meters),
            ).fetchall()
            metadata = connection.execute(
                """
                SELECT metadata.dataset_version, metadata.source_date,
                       metadata.freshness_days,
                       sum(coverage.total_count)::bigint AS total_count,
                       sum(coverage.spatial_count)::bigint AS spatial_count
                FROM reference_read.active_source_metadata metadata
                LEFT JOIN reference_read.source_coverage coverage
                  ON coverage.publication_id = metadata.publication_id
                WHERE metadata.source_id = 'transport.rail-station'
                GROUP BY metadata.dataset_version, metadata.source_date,
                         metadata.freshness_days
                """
            ).fetchone()
        if (
            metadata is None
            or metadata["source_date"] is None
            or metadata["total_count"] is None
            or int(metadata["total_count"]) <= 0
        ):
            raise RuntimeError("active rail station metadata is missing")
        occurrences = tuple(
            RailOccurrence(
                occurrence_id=str(row["occurrence_id"]),
                station_name=str(row["station_name"]),
                line_name=str(row["line_name"]),
                transfer_lines=tuple(str(value) for value in row["transfer_lines"] or ()),
                latitude=float(row["latitude"]),
                longitude=float(row["longitude"]),
                distance_meters=round(float(row["distance_meters"])),
            )
            for row in rows
        )
        return RailStationSearchResult(
            stations=merge_station_occurrences(occurrences, limit=limit),
            occurrence_count=len(occurrences),
            dataset_version=str(metadata["dataset_version"]),
            source_date=metadata["source_date"],
            coordinate_coverage=(
                int(metadata["spatial_count"] or 0) / int(metadata["total_count"])
            ),
            freshness_days=int(metadata["freshness_days"]),
        )


def merge_station_occurrences(
    occurrences: tuple[RailOccurrence, ...], *, limit: int = 5
) -> tuple[RailStation, ...]:
    if not 1 <= limit <= 5 or len(occurrences) > 25:
        raise ValueError("rail occurrence merge bounds are invalid")
    groups: list[list[RailOccurrence]] = []
    for occurrence in sorted(occurrences, key=lambda item: (item.distance_meters, item.occurrence_id)):
        if (
            not occurrence.occurrence_id.strip()
            or not _name(occurrence.station_name)
            or not math.isfinite(occurrence.latitude)
            or not math.isfinite(occurrence.longitude)
        ):
            raise ValueError("rail occurrence is invalid")
        matching = next(
            (
                group
                for group in groups
                if _name(group[0].station_name) == _name(occurrence.station_name)
                and _distance_meters(group[0], occurrence) <= 250
            ),
            None,
        )
        if matching is None:
            groups.append([occurrence])
        else:
            matching.append(occurrence)
    stations = []
    for group in groups[:limit]:
        lines = tuple(
            dict.fromkeys(
                line
                for occurrence in group
                for line in (occurrence.line_name, *occurrence.transfer_lines)
                if line.strip()
            )
        )
        stations.append(
            RailStation(
                station_name=_name(group[0].station_name),
                lines=lines,
                occurrence_ids=tuple(item.occurrence_id for item in group),
                distance_meters=min(item.distance_meters for item in group),
            )
        )
    return tuple(stations)


def _name(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split())


def _distance_meters(first: RailOccurrence, second: RailOccurrence) -> float:
    latitude_scale = 111_320
    longitude_scale = latitude_scale * math.cos(math.radians((first.latitude + second.latitude) / 2))
    return math.hypot(
        (first.latitude - second.latitude) * latitude_scale,
        (first.longitude - second.longitude) * longitude_scale,
    )


def _validate_query(
    latitude: float, longitude: float, radius_meters: int, limit: int
) -> None:
    if (
        not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or not 33 <= latitude <= 39
        or not 124 <= longitude <= 132
        or not 100 <= radius_meters <= 3000
        or not 1 <= limit <= 5
    ):
        raise ValueError("rail station query is invalid")

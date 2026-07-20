from __future__ import annotations

import math
import unicodedata
from dataclasses import dataclass


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

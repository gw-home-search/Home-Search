from __future__ import annotations

from ai_service.property_chat.rail_stations import RailOccurrence, merge_station_occurrences


def _occurrence(identity: str, name: str, longitude: float, line: str, distance: int):
    return RailOccurrence(identity, name, line, (), 37.5665, longitude, distance)


def test_exact_nfkc_name_within_250m_merges_occurrences_and_preserves_lines() -> None:
    stations = merge_station_occurrences(
        (
            _occurrence("operator-a|01|101", "서울역", 126.9780, "1호선", 120),
            _occurrence("operator-b|04|401", "서울역", 126.9790, "4호선", 180),
        )
    )

    assert stations[0].station_name == "서울역"
    assert stations[0].lines == ("1호선", "4호선")
    assert stations[0].occurrence_ids == ("operator-a|01|101", "operator-b|04|401")


def test_fuzzy_name_or_more_than_250m_never_merges() -> None:
    stations = merge_station_occurrences(
        (
            _occurrence("a", "서울역", 126.9780, "1호선", 100),
            _occurrence("b", "서울 역", 126.9781, "4호선", 110),
            _occurrence("c", "서울역", 126.9820, "경의선", 120),
        )
    )

    assert [station.occurrence_ids for station in stations] == [("a",), ("b",), ("c",)]


def test_runtime_bounds_occurrences_and_station_limit() -> None:
    occurrences = tuple(
        _occurrence(str(index), f"역-{index}", 126.9 + index / 10000, "1호선", index)
        for index in range(6)
    )
    assert len(merge_station_occurrences(occurrences)) == 5

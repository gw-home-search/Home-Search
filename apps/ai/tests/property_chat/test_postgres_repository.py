from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, date, datetime
from types import SimpleNamespace
from time import perf_counter
from time import sleep

import pytest

from ai_service.property_chat.postgres import (
    PostgresPropertyFactRepository,
    _complex_search_tokens,
)
from ai_service.property_chat.models import ComplexRecord


@pytest.mark.parametrize(
    ("query", "expected"),
    (
        ("신동 래미안아파트", ("신동", "래미안")),
        ("반포자이 아파트 어떄", ("반포자이",)),
        ("A_타워", ("a", "타워")),
    ),
)
def test_complex_search_tokens_remove_common_suffix_and_question_fillers(
    query: str, expected: tuple[str, ...]
) -> None:
    assert _complex_search_tokens(query) == expected


def test_property_pool_checks_connection_health_before_checkout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class FakePool:
        @staticmethod
        def check_connection(_connection: object) -> None:
            return None

        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)

        @contextmanager
        def connection(self):
            yield SimpleNamespace(info=SimpleNamespace(dbname="test", user="test"))

        def close(self) -> None:
            return None

    monkeypatch.setattr("ai_service.property_chat.postgres.ConnectionPool", FakePool)
    repository = PostgresPropertyFactRepository(
        "postgresql://test:test@localhost/test",
        expected_database="test",
        expected_username="test",
    )
    repository.close()

    assert captured["check"] is FakePool.check_connection


def test_complex_lookup_uses_direct_exact_fact_path_before_contains_projection() -> None:
    executed_queries: list[str] = []
    executed_parameters: list[object] = []
    row = {
        "complex_id": 7774,
        "display_name": "아현동 마포래미안푸르지오 1단지",
        "region_code": "11440101",
        "region_name": "아현동",
        "address": "서울 마포구 아현동",
        "latitude": 37.55,
        "longitude": 126.95,
        "marker_safe": True,
        "data_updated_at": "2026-07-31T00:00:00+00:00",
        "unit_count": 3885,
        "use_date": date(2014, 9, 26),
    }

    class FakeResult:
        def fetchall(self) -> list[dict[str, object]]:
            return [row]

    class FakeConnection:
        def execute(self, query: str, parameters: object) -> FakeResult:
            executed_queries.append(query)
            executed_parameters.append(parameters)
            return FakeResult()

    class FakePool:
        @contextmanager
        def connection(self):
            yield FakeConnection()

    repository = object.__new__(PostgresPropertyFactRepository)
    repository._pool = FakePool()  # type: ignore[attr-defined]

    records = repository.find_complexes("마포래미안푸르지오 1단지", None, 6)

    assert [record.complex_id for record in records] == [7774]
    assert len(executed_queries) == 1
    assert "FROM ai_read.complex_fact" in executed_queries[0]
    assert "lower(display_name) = lower(%s)" in executed_queries[0]
    assert "ILIKE" not in executed_queries[0]
    assert "regexp_replace(display_name" not in executed_queries[0]
    assert "complex_search_fact" not in executed_queries[0]
    assert "%마포래미안푸르지오1단지%" not in executed_parameters[0]


def test_complex_lookup_uses_exact_alias_after_direct_exact_miss() -> None:
    executed_queries: list[str] = []
    row = {
        "complex_id": 9001, "parcel_id": 91, "display_name": "임의동 푸른마을",
        "region_code": "11110101", "region_name": "임의동",
        "address": "서울 임의구 임의동", "latitude": 37.5, "longitude": 127.0,
        "marker_safe": True, "data_updated_at": "2026-08-01T00:00:00+00:00",
        "unit_count": 100, "use_date": date(2020, 1, 1), "match_tier": 2,
    }

    class FakeResult:
        def __init__(self, rows):
            self._rows = rows

        def fetchall(self):
            return self._rows

    class FakeConnection:
        def execute(self, query: str, _parameters: object) -> FakeResult:
            executed_queries.append(query)
            return FakeResult([row] if "complex_search_fact" in query else [])

    class FakePool:
        @contextmanager
        def connection(self):
            yield FakeConnection()

    repository = object.__new__(PostgresPropertyFactRepository)
    repository._pool = FakePool()  # type: ignore[attr-defined]
    repository._candidate_discovery = None  # type: ignore[attr-defined]

    records = repository.find_complexes("푸른별칭", None, 6)

    assert [record.complex_id for record in records] == [9001]
    assert len(executed_queries) == 2
    assert "complex_search_fact" not in executed_queries[0]
    assert "complex_search_fact" in executed_queries[1]
    assert "ILIKE" not in executed_queries[1]


def test_slow_ai_read_uses_property_search_ids_then_revalidates_facts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    external_payload = {"complexName": "신뢰하면 안 되는 외부 이름"}

    class Discovery:
        def find_complex_ids(self, name: str) -> tuple[int, ...]:
            assert name == "임의단지"
            assert external_payload["complexName"] == "신뢰하면 안 되는 외부 이름"
            return (9001,)

    verified = ComplexRecord(
        complex_id=9001, parcel_id=19001, display_name="검증된 임의단지",
        region_code="11110101", region_name="임의동", address="서울 임의구 임의동",
        latitude=37.5, longitude=127.0, marker_safe=True,
        data_updated_at=datetime(2026, 8, 1, tzinfo=UTC),
    )
    repository = object.__new__(PostgresPropertyFactRepository)
    repository._candidate_discovery = Discovery()  # type: ignore[attr-defined]
    repository._find_complexes_by_ids = lambda complex_ids: (  # type: ignore[method-assign]
        [verified] if complex_ids == (9001,) else []
    )
    repository._find_complexes_from_ai_read = lambda *_args: (  # type: ignore[method-assign]
        sleep(0.05) or []
    )
    monkeypatch.setattr(
        "ai_service.property_chat.postgres._PROPERTY_SEARCH_SOFT_LATENCY_SECONDS",
        0.01,
    )

    records = repository.find_complexes("임의단지", None, 6)

    assert records == [verified]
    assert records[0].display_name != external_payload["complexName"]


def test_property_search_ids_are_revalidated_in_one_bounded_batch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    executed_queries: list[str] = []
    rows = [
        {
            "complex_id": complex_id,
            "parcel_id": complex_id + 10_000,
            "display_name": f"검증단지{complex_id}",
            "region_code": "11110101",
            "region_name": "임의동",
            "address": f"서울 임의구 임의동 {complex_id}",
            "latitude": 37.5,
            "longitude": 127.0,
            "marker_safe": True,
            "data_updated_at": "2026-08-01T00:00:00+00:00",
            "unit_count": 100,
            "use_date": date(2020, 1, 1),
        }
        for complex_id in (9003, 9001, 9002)
    ]

    class Discovery:
        def find_complex_ids(self, _name: str) -> tuple[int, ...]:
            return (9003, 9001, 9002)

    class FakeResult:
        def __init__(self, selected_rows):
            self._rows = selected_rows

        def fetchall(self):
            return self._rows

        def fetchone(self):
            return self._rows[0] if self._rows else None

    class FakeConnection:
        def execute(self, query: str, parameters: object) -> FakeResult:
            executed_queries.append(query)
            if "complex_id = ANY" in query:
                return FakeResult(rows)
            complex_id = parameters[0]  # type: ignore[index]
            return FakeResult([
                row for row in rows if row["complex_id"] == complex_id
            ])

    class FakePool:
        @contextmanager
        def connection(self):
            yield FakeConnection()

    repository = object.__new__(PostgresPropertyFactRepository)
    repository._pool = FakePool()  # type: ignore[attr-defined]
    repository._candidate_discovery = Discovery()  # type: ignore[attr-defined]
    repository._find_complexes_from_ai_read = lambda *_args: (  # type: ignore[method-assign]
        sleep(0.05) or []
    )
    monkeypatch.setattr(
        "ai_service.property_chat.postgres._PROPERTY_SEARCH_SOFT_LATENCY_SECONDS",
        0.01,
    )

    records = repository.find_complexes("임의단지", None, 6)

    assert [record.complex_id for record in records] == [9003, 9001, 9002]
    assert len(executed_queries) == 1
    assert "complex_id = ANY" in executed_queries[0]


def test_property_search_fallback_respects_the_overall_hard_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Discovery:
        def find_complex_ids(self, _name: str) -> tuple[int, ...]:
            return (9001,)

    repository = object.__new__(PostgresPropertyFactRepository)
    repository._candidate_discovery = Discovery()  # type: ignore[attr-defined]
    repository._find_complexes_from_ai_read = lambda *_args: (  # type: ignore[method-assign]
        sleep(0.08) or []
    )
    repository._find_complexes_by_ids = lambda *_args: (  # type: ignore[method-assign]
        sleep(0.08) or []
    )
    monkeypatch.setattr(
        "ai_service.property_chat.postgres._PROPERTY_SEARCH_SOFT_LATENCY_SECONDS",
        0.005,
    )
    monkeypatch.setattr(
        "ai_service.property_chat.postgres._PROPERTY_SEARCH_HARD_TIMEOUT_SECONDS",
        0.02,
    )

    started_at = perf_counter()
    with pytest.raises(TimeoutError):
        repository.find_complexes("임의단지", None, 6)

    assert perf_counter() - started_at < 0.06


def test_complex_lookup_escapes_like_wildcards_and_applies_region(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        literal_wildcard = repository.find_complexes("A_", "강남동", 6)
        exact = repository.find_complexes("잠실엘스", "잠실동", 6)
    finally:
        repository.close()

    assert [record.complex_id for record in literal_wildcard] == [2]
    assert [record.complex_id for record in exact] == [1]
    assert exact[0].parcel_id == 101
    assert exact[0].match_tier == 0


def test_property_readiness_requires_a_readable_complex_fact(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        repository.readiness_probe()
    finally:
        repository.close()


def test_region_context_resolves_exact_province_and_district_ancestors(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        context = repository.resolve_region_context("11710101")
        missing = repository.resolve_region_context("99999999")
    finally:
        repository.close()

    assert context is not None
    assert context.province_name == "서울특별시"
    assert context.district_name == "송파구"
    assert context.education_office_name == "서울특별시교육청"
    assert missing is None


def test_region_context_rejects_noncanonical_code(property_postgres_dsn: str) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        with pytest.raises(ValueError, match="region code"):
            repository.resolve_region_context("11710%")
    finally:
        repository.close()


def test_recent_trade_query_keeps_date_boundaries_area_tolerance_and_latest_order(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        records = repository.recent_trades(
            1,
            date(2026, 1, 1),
            date(2026, 2, 15),
            84.0,
            5,
        )
    finally:
        repository.close()

    assert [record.trade_id for record in records] == [14, 12, 11]
    assert records[-1].deal_date == date(2026, 1, 1)


def test_candidate_observation_summary_is_one_bounded_exact_complex_query(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        summaries = repository.candidate_observation_summaries(
            (1, 2), date(2026, 1, 1), date(2026, 2, 15), 84.0,
            "recent_trade_lookup",
        )
    finally:
        repository.close()

    assert [summary.complex_id for summary in summaries] == [1, 2]
    assert summaries[0].exact_observation_count == 3
    assert summaries[0].latest_observation_date == date(2026, 2, 15)
    assert summaries[1].exact_observation_count == 0


def test_comparison_lookup_and_trades_use_two_bounded_batch_queries(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        complexes = repository.find_complexes_batch(
            ("잠실엘스", "A_타워"), None, 6
        )
        district_complexes = repository.find_complexes_batch(
            ("잠실엘스", "A_타워"), "송파구", 6
        )
        trades = repository.recent_trades_batch(
            (1, 2), date(2025, 7, 21), date(2026, 7, 20), 84.0, 3
        )
        latest = repository.latest_trades_for_candidates(
            (1, 2), date(2025, 7, 21), date(2026, 7, 20), None
        )
    finally:
        repository.close()

    assert [record.complex_id for record in complexes["잠실엘스"]] == [1]
    assert [record.complex_id for record in complexes["A_타워"]] == [2]
    assert complexes["잠실엘스"][0].unit_count == 5678
    assert [record.complex_id for record in district_complexes["잠실엘스"]] == [1]
    assert [record.trade_id for record in trades[1]] == [14, 12, 11]
    assert trades[2] == ()
    assert latest[1] is not None and latest[1].trade_id == 14
    assert latest[2] is None


def test_recommendation_candidates_resolve_descendants_and_return_latest_three(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        candidates = repository.recommendation_candidates(
            "송파구", date(2025, 2, 16), date(2026, 2, 15), 84.0, 5_000
        )
        missing_region = repository.recommendation_candidates(
            "없는 지역", date(2025, 2, 16), date(2026, 2, 15), 84.0, 5_000
        )
    finally:
        repository.close()

    assert candidates is not None
    assert list(candidates) == [1]
    complex_record, trades = candidates[1]
    assert complex_record.display_name == "잠실동 잠실엘스"
    assert complex_record.marker_safe is True
    assert [trade.trade_id for trade in trades] == [14, 12, 11]
    assert missing_region is None


def test_criteria_candidates_resolve_unique_suffix_and_keep_unit_count(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        exact = repository.criteria_candidates("송파구", 101)
        suffix_omitted = repository.criteria_candidates("송파", 101)
        full_name = repository.criteria_candidates("서울특별시 송파구", 101)
        wrong_parent = repository.criteria_candidates("부산광역시 송파구", 101)
        missing = repository.criteria_candidates("없는 지역", 101)
    finally:
        repository.close()

    assert exact is not None
    assert suffix_omitted is not None
    assert full_name is not None
    assert exact.scope_label == suffix_omitted.scope_label == full_name.scope_label == "송파구"
    assert [item.complex_id for item in exact.candidates] == [1]
    assert exact.candidates[0].unit_count == 5678
    assert wrong_parent is None
    assert missing is None


def test_station_scope_candidates_use_coordinates_without_cross_database_join(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        candidates = repository.criteria_candidates_near_point(
            37.513, 127.082, 800, 101
        )
    finally:
        repository.close()

    assert [candidate.complex_id for candidate in candidates] == [1]


def test_recommendation_candidate_observation_p95_is_bounded(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        repository.recommendation_candidates(
            "송파구", date(2025, 2, 16), date(2026, 2, 15), 84.0, 5_000
        )
        durations = []
        for _ in range(20):
            started = perf_counter()
            repository.recommendation_candidates(
                "송파구", date(2025, 2, 16), date(2026, 2, 15), 84.0, 5_000
            )
            durations.append(perf_counter() - started)
    finally:
        repository.close()

    assert sorted(durations)[18] < 0.2


def test_monthly_trend_uses_the_same_period_and_area_filter(
    property_postgres_dsn: str,
) -> None:
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        records = repository.monthly_trends(
            1,
            date(2026, 1, 1),
            date(2026, 2, 28),
            84.0,
        )
    finally:
        repository.close()

    assert [record.month for record in records] == [date(2026, 1, 1), date(2026, 2, 1)]
    assert records[0].average_amount_ten_thousand_krw == 245000
    assert records[0].trade_count == 2
    assert records[1].average_amount_ten_thousand_krw == 270000
    assert records[1].trade_count == 1


def test_repository_rejects_a_non_ai_reader_role(property_postgres_dsn: str) -> None:
    with pytest.raises(ValueError, match="AI reader role"):
        PostgresPropertyFactRepository(
            property_postgres_dsn,
            expected_database="test",
            expected_username="home_search_ai_reader",
        )


def test_latest_trade_date_loads_on_a_fresh_process_before_caching(
    property_postgres_dsn: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("ai_service.property_chat.postgres.monotonic", lambda: 10.0)
    repository = PostgresPropertyFactRepository(
        property_postgres_dsn, expected_database="test", expected_username="test"
    )
    try:
        latest = repository.latest_trade_date()
        cached = repository.latest_trade_date()
    finally:
        repository.close()

    assert latest == date(2026, 2, 15)
    assert cached == latest


@pytest.mark.parametrize(
    ("dsn", "options", "message"),
    [
        (" ", {}, "property DSN"),
        ("postgresql://unused", {"expected_database": " "}, "expected database"),
        ("postgresql://unused", {"expected_username": " "}, "expected username"),
        (
            "postgresql://unused",
            {"min_pool_size": 0, "max_pool_size": 5},
            "pool size",
        ),
    ],
)
def test_repository_rejects_unsafe_configuration_before_connecting(
    dsn: str, options: dict[str, object], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        PostgresPropertyFactRepository(dsn, **options)  # type: ignore[arg-type]

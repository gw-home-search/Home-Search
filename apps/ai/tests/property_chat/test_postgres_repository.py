from __future__ import annotations

from datetime import date

import pytest

from ai_service.property_chat.postgres import PostgresPropertyFactRepository


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

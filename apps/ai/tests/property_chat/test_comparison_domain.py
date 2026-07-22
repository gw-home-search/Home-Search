from datetime import date

import pytest

from ai_service.property_chat.comparison import (
    CandidatePoint,
    ComparisonCell,
    ComparisonColumn,
    ComparisonRow,
    ComparisonTableArtifact,
    RecentThreeTradeBasis,
)
from ai_service.property_chat.models import QueryPlan, TradeRecord


def _trade(trade_id: int, deal_date: date, amount: int, area: float = 84.0) -> TradeRecord:
    return TradeRecord(
        trade_id=trade_id,
        complex_id=501,
        deal_date=deal_date,
        deal_amount_ten_thousand_krw=amount,
        exclusive_area_square_meters=area,
        floor=10,
    )


def test_recent_three_basis_uses_same_cutoff_window_area_and_latest_order() -> None:
    basis = RecentThreeTradeBasis.from_trades(
        complex_id=501,
        cutoff=date(2026, 7, 20),
        exclusive_area_square_meters=84.0,
        trades=(
            _trade(3, date(2026, 7, 18), 210_000),
            _trade(1, date(2026, 7, 20), 205_000),
            _trade(2, date(2026, 7, 19), 195_000),
        ),
    )

    assert basis.sample_count == 3
    assert basis.latest_trade.trade_id == 1
    assert basis.median_amount_ten_thousand_krw == 205_000
    assert basis.start_date == date(2025, 7, 21)


def test_recent_three_basis_marks_price_unavailable_below_three_samples() -> None:
    basis = RecentThreeTradeBasis.from_trades(
        complex_id=501,
        cutoff=date(2026, 7, 20),
        exclusive_area_square_meters=84.0,
        trades=(
            _trade(1, date(2026, 7, 20), 205_000),
            _trade(2, date(2026, 7, 19), 195_000),
        ),
    )

    assert basis.sample_count == 2
    assert basis.latest_trade is None
    assert basis.median_amount_ten_thousand_krw is None


@pytest.mark.parametrize(
    "trades",
    [
        (_trade(1, date(2025, 7, 20), 205_000),),
        (_trade(1, date(2026, 7, 20), 205_000, 85.1),),
        (
            _trade(1, date(2026, 7, 20), 205_000),
            _trade(1, date(2026, 7, 19), 195_000),
        ),
    ],
)
def test_recent_three_basis_rejects_mixed_or_duplicate_observations(
    trades: tuple[TradeRecord, ...],
) -> None:
    with pytest.raises(ValueError):
        RecentThreeTradeBasis.from_trades(
            complex_id=501,
            cutoff=date(2026, 7, 20),
            exclusive_area_square_meters=84.0,
            trades=trades,
        )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"complex_names": ("잠실엘스",)},
        {"complex_names": ("잠실엘스", "잠실엘스")},
        {"complex_names": ("A", "B", "C", "D", "E")},
    ],
)
def test_comparison_plan_requires_two_to_four_unique_names(
    kwargs: dict[str, object],
) -> None:
    values: dict[str, object] = {
        "capability": "comparison",
        "complex_name": "잠실엘스",
        "complex_names": ("잠실엘스", "헬리오시티"),
        "exclusive_area_square_meters": 84.0,
    }
    values.update(kwargs)
    with pytest.raises(ValueError):
        QueryPlan(**values)  # type: ignore[arg-type]


def test_comparison_plan_accepts_missing_area_for_non_price_comparison() -> None:
    plan = QueryPlan(
        capability="comparison",
        complex_name="잠실엘스",
        complex_names=("잠실엘스", "헬리오시티"),
        exclusive_area_square_meters=None,
    )

    assert plan.exclusive_area_square_meters is None


def test_comparison_public_value_objects_reject_invalid_contract_states() -> None:
    with pytest.raises(ValueError):
        CandidatePoint(0, 37.5, 127.0, None)
    with pytest.raises(ValueError):
        ComparisonCell("available", None, "COUNT", None, ())
    with pytest.raises(ValueError):
        ComparisonCell("unavailable", "값", "COUNT", "이유", ())
    with pytest.raises(ValueError):
        ComparisonCell("unknown", None, "COUNT", "이유", ())
    with pytest.raises(ValueError):
        ComparisonCell("unavailable", None, "", "이유", ())
    with pytest.raises(ValueError):
        ComparisonCell("available", "x" * 2_001, "COUNT", None, ("fact-1",))
    with pytest.raises(ValueError):
        ComparisonColumn("", "", ()).to_public_dict()
    with pytest.raises(ValueError):
        ComparisonRow("", "", ()).to_public_dict()
    with pytest.raises(ValueError):
        ComparisonTableArtifact(
            artifact_id="invalid",
            columns=(),
            rows=(),
            cutoff=date(2026, 7, 20),
            start_date=date(2025, 7, 21),
            exclusive_area_square_meters=84.0,
        ).to_public_dict()

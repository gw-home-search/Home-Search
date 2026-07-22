from datetime import date

import pytest

from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.answer_quality import AnswerQualityError, AnswerQualityGate
from ai_service.property_chat.answer_report import (
    _append_bounded_artifacts,
    _artifact_id,
    _fact_ids,
    _krw,
    _metric_value,
    _report_kind,
    build_answer_report,
)
from ai_service.property_chat.deterministic_answer import DeterministicAnswerPresenter
from ai_service.property_chat.deterministic_router import DeterministicQueryRouter
from ai_service.property_chat.models import (
    DraftAnswer,
    DraftSentence,
    EvidenceFact,
    FactClaim,
    QueryPlan,
    QueryPlanBundle,
)
from ai_service.property_chat.presentation import _criteria_candidate_interpretation


def _fact(
    fact_id: str,
    payload: dict[str, object],
    claims: tuple[FactClaim, ...],
) -> EvidenceFact:
    return EvidenceFact(
        fact_id=fact_id,
        payload=payload,
        claims=claims,
        data_as_of=date(2026, 7, 20),
    )


@pytest.mark.parametrize(
    ("question", "capability"),
    [
        ("잠실엘스 전용 84㎡ 가격 흐름", "price_trend"),
        ("잠실엘스 최근 실거래 3건", "recent_trade_lookup"),
        ("잠실엘스 가까운 철도역", "rail_station_lookup"),
        ("잠실엘스 주변 학원", "academy_lookup"),
        ("잠실엘스 주변 학교", "school_location"),
        ("잠실엘스 주변 백화점", "retail_location"),
        ("잠실엘스 주변 어린이집", "childcare_lookup"),
        ("잠실엘스 주소", "complex_identity"),
    ],
)
def test_deterministic_router_handles_clear_property_intents(
    question: str, capability: str,
) -> None:
    plan = DeterministicQueryRouter(today=date(2026, 7, 22)).plan(
        ChatbotQueryRequest(question=question)
    )

    assert isinstance(plan, QueryPlan)
    assert plan.capability == capability
    if capability == "recent_trade_lookup":
        assert plan.limit == 3
        assert plan.exclusive_area_square_meters is None
    if capability == "price_trend":
        assert plan.exclusive_area_square_meters == 84


def test_deterministic_router_builds_overview_and_rejects_unclear_scope() -> None:
    router = DeterministicQueryRouter(today=date(2026, 7, 22))

    overview = router.plan(ChatbotQueryRequest(question="잠실엘스 전체적으로 어때?"))
    assert isinstance(overview, QueryPlanBundle)
    assert [plan.capability for plan in overview.fragments] == [
        "complex_identity", "recent_trade_lookup", "price_trend", "rail_station_lookup",
    ]
    assert router.plan(ChatbotQueryRequest(question="여기 가격 알려줘")) is None
    assert router.plan(ChatbotQueryRequest(question="잠실엘스 궁금해")) is None
    assert router.overview("잠실엘스", "잠실동").fragments[0].region_name == "잠실동"


def test_deterministic_presenter_writes_property_values_into_answer() -> None:
    presenter = DeterministicAnswerPresenter()
    trade = _fact(
        "property-trade-1",
        {
            "dealDate": "2026-07-20",
            "exclusiveAreaSquareMeters": 84.8,
            "floor": 12,
        },
        (
            FactClaim("2026-07-20", "DATE"),
            FactClaim("25억원", "KOREAN_KRW_DISPLAY"),
        ),
    )
    trend = _fact(
        "property-trend-1",
        {"month": "2026-07", "tradeCount": 3},
        (
            FactClaim("2026-07", "MONTH"),
            FactClaim("24억 5,000만원", "KOREAN_KRW_AVERAGE_DISPLAY"),
            FactClaim("3", "COUNT"),
        ),
    )

    trade_answer = presenter.present(
        plan=QueryPlan("recent_trade_lookup", "잠실엘스"),
        facts=[trade], limitations=[], readiness="supported",
    )
    trend_answer = presenter.present(
        plan=QueryPlan(
            "price_trend", "잠실엘스",
            start_date=date(2026, 1, 1), end_date=date(2026, 7, 22),
        ),
        facts=[trend], limitations=[], readiness="supported",
    )

    assert "2026-07-20 전용 84.8㎡ 25억원, 12층" in trade_answer.sentences[0].text
    assert "2026-07 평균 24억 5,000만원, 거래 3건" in trend_answer.sentences[0].text
    assert len(trade_answer.sentences[0].claims) == 2


def test_deterministic_presenter_writes_facility_and_verified_zero() -> None:
    presenter = DeterministicAnswerPresenter()
    facility = _fact(
        "rail-station-1",
        {"stationName": "잠실새내역", "distanceMeters": 420},
        (FactClaim("잠실새내역", "TEXT"), FactClaim("420", "METERS")),
    )
    verified_zero = _fact(
        "rail-scope-1",
        {"radiusMeters": 1500, "matchedCount": 0, "verifiedZero": True},
        (FactClaim("1500", "RADIUS_METERS"), FactClaim("0", "COUNT")),
    )

    facility_answer = presenter.present(
        plan=QueryPlan("rail_station_lookup", "잠실엘스"),
        facts=[facility], limitations=[], readiness="supported",
    )
    zero_answer = presenter.present(
        plan=QueryPlan("rail_station_lookup", "잠실엘스"),
        facts=[verified_zero], limitations=[], readiness="supported",
    )
    unavailable = presenter.present(
        plan=QueryPlan("rail_station_lookup", "잠실엘스"),
        facts=[], limitations=[], readiness="unavailable",
    )

    assert "잠실새내역 직선거리 420m" in facility_answer.sentences[0].text
    assert "반경 1500m 안에서 확인된 시설 0곳" in zero_answer.sentences[0].text
    assert unavailable.sentences[0].text == "현재 확인 가능한 근거가 없습니다."


def test_answer_quality_gate_rejects_empty_and_request_only_drafts() -> None:
    gate = AnswerQualityGate()
    fact = _fact("property-complex-501", {}, (FactClaim("잠실엘스", "TEXT"),))

    with pytest.raises(AnswerQualityError):
        gate.validate(draft=DraftAnswer([]), facts=[fact], readiness="supported")
    with pytest.raises(AnswerQualityError):
        gate.validate(
            draft=DraftAnswer([DraftSentence("지역을 더 알려주세요.", [], [])]),
            facts=[fact],
            readiness="supported",
        )

    gate.validate(
        draft=DraftAnswer([DraftSentence("확인된 후보가 있습니다.", [], [])]),
        facts=[fact],
        readiness="supported",
    )


def test_answer_report_builds_grounded_generic_sections_and_action_references() -> None:
    fact = _fact(
        "property-complex-501",
        {"complexId": 501, "complexName": "잠실엘스"},
        (FactClaim("잠실엘스", "TEXT"),),
    )
    artifact = {
        "version": 1,
        "artifactId": "fact-list-1",
        "type": "factList",
        "title": "단지 정보",
        "factIds": [fact.fact_id],
        "items": [],
    }

    report, artifacts = build_answer_report(
        plan=QueryPlan(
            "comparison",
            "잠실엘스와 헬리오시티를 비교해줘",
            complex_names=("잠실엘스", "헬리오시티"),
        ),
        ui_summary={
            "headline": {
                "text": "두 단지를 확인된 항목으로 비교했어요.",
                "factIds": [fact.fact_id],
            },
            "criteria": [
                {"label": "비교 기준", "value": "교육과 교통", "factIds": [fact.fact_id]},
                {"label": "잘못된 기준", "value": "제외", "factIds": ["unknown-fact"]},
            ],
        },
        artifacts=(artifact,),
        actions=(
            {"actionId": "show-on-map", "kind": "MAP_FIT_BOUNDS"},
            {"actionId": 3, "kind": "MAP_FIT_BOUNDS"},
        ),
        facts=(fact,),
    )

    assert report is not None
    assert report["kind"] == "COMPARISON"
    assert report["opening"]["text"] == "두 단지를 확인된 항목으로 비교했어요."
    assert report["basis"] == [
        {"text": "비교 기준: 교육과 교통", "factIds": [fact.fact_id]},
    ]
    assert report["primaryArtifactId"] == "fact-list-1"
    assert report["actionIds"] == ["show-on-map"]
    assert artifacts == (artifact,)


def test_answer_report_skips_empty_and_tolerates_malformed_recommendation_rows() -> None:
    no_report, no_artifacts = build_answer_report(
        plan=QueryPlan("complex_identity", "이 단지 알려줘"),
        ui_summary=None,
        artifacts=(),
        actions=(),
        facts=(),
    )

    malformed = {
        "version": 1,
        "artifactId": "recommendation-table-malformed",
        "type": "recommendationTable",
        "title": "추천 후보",
        "basis": {},
        "rows": "invalid",
    }
    report, artifacts = build_answer_report(
        plan=QueryPlan("recommendation", "마포구 단지를 추천해줘"),
        ui_summary={
            "headline": {
                "text": "마포구 후보를 정리했어요.",
                "factIds": [],
            },
        },
        artifacts=(malformed,),
        actions=(),
        facts=(),
    )

    assert no_report is None
    assert no_artifacts == ()
    assert report is not None
    assert report["kind"] == "RECOMMENDATION"
    assert report["highlights"] == []
    assert report["detailArtifactIds"] == []
    assert artifacts == (malformed,)


def test_answer_report_formatters_cover_supported_units_and_unknown_values() -> None:
    fact = _fact("property-complex-501", {}, (FactClaim("잠실엘스", "TEXT"),))

    assert _krw(0) == "0만원"
    assert _krw(10_000) == "1억원"
    assert _krw(10_500) == "1억 500만원"
    assert _metric_value("ACADEMY", {"value": 12}, None) == "12곳"
    assert _metric_value(
        "ACADEMY",
        {"value": 12, "nearestDistanceMeters": 340},
        800,
    ) == "800m 내 12곳 · 최근접 340m"
    assert _metric_value("TRANSIT", {"value": 420}, None) == "직선거리 420m"
    assert _metric_value("TRANSIT", {"value": "unknown"}, None) is None
    assert _fact_ids("invalid", {fact.fact_id: fact}) == ()
    assert _artifact_id(None) is None
    assert _artifact_id({"artifactId": 501}) is None
    assert _report_kind("recent_trade_lookup") == "RECENT_TRADE"
    assert _report_kind("unsupported") == "GENERAL"


def test_answer_report_artifact_boundaries_reject_duplicate_count_and_size_overflow() -> None:
    duplicate_base = ({"artifactId": "duplicate", "type": "factList"},)
    duplicate = _append_bounded_artifacts(
        duplicate_base,
        ({"artifactId": "duplicate", "type": "candidateProfile"},),
    )
    full = tuple(
        {"artifactId": f"artifact-{index}", "type": "factList"}
        for index in range(8)
    )
    at_limit = _append_bounded_artifacts(
        full,
        ({"artifactId": "candidate-profile-501", "type": "candidateProfile"},),
    )
    oversized = {
        "artifactId": "candidate-profile-large",
        "type": "candidateProfile",
        "body": "가" * 65_536,
    }
    small = {"artifactId": "candidate-profile-small", "type": "candidateProfile"}
    size_bounded = _append_bounded_artifacts((), (oversized, small))

    assert duplicate == duplicate_base
    assert at_limit == full
    assert size_bounded == (small,)


def test_criteria_candidate_interpretation_uses_distinct_observed_metrics() -> None:
    academy = _criteria_candidate_interpretation(
        "후보 A",
        "ACADEMY",
        {"value": 12, "nearestDistanceMeters": 340},
        800,
    )
    academy_without_radius = _criteria_candidate_interpretation(
        "후보 B", "ACADEMY", {"value": 8}, None,
    )
    transit = _criteria_candidate_interpretation(
        "후보 B", "TRANSIT", {"value": 420}, None,
    )
    unknown = _criteria_candidate_interpretation(
        "후보 C", "UNKNOWN", {"value": None}, None,
    )

    assert academy == (
        "학원 접근성",
        "후보 A: 반경 800m 안에서 학원 위치 12곳, 최근접 학원 직선거리 340m로 확인했습니다.",
    )
    assert academy_without_radius == (
        "학원 접근성", "후보 B: 주변에서 학원 위치 8곳을 확인했습니다."
    )
    assert transit == (
        "철도역 접근성", "후보 B: 철도역 접근성 기준 직선거리 420m로 확인됐습니다."
    )
    assert unknown == (
        "확인 기준", "후보 C: 확인 기준 관찰값이 확인된 후보입니다."
    )

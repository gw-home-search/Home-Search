from datetime import date

import pytest

from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat.answer_quality import AnswerQualityError, AnswerQualityGate
from ai_service.property_chat.answer_document import AnswerDocument
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
from ai_service.property_chat.presentation import (
    PresentationAssembler,
    _criteria_candidate_interpretation,
)
from ai_service.property_chat.question_normalizer import normalize_question


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
        ("잠실엘스 기본정보", "complex_identity"),
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

    for question in (
        "잠실엘스 전체적으로 어때?",
        "반포자이 아파트 어때?",
        "반포자이 아파트 어떄?",
    ):
        overview = router.plan(ChatbotQueryRequest(question=question))
        assert isinstance(overview, QueryPlanBundle)
        assert [plan.capability for plan in overview.fragments] == [
            "complex_identity", "recent_trade_lookup",
        ]
        assert {plan.complex_name for plan in overview.fragments} == {
            "잠실엘스" if question.startswith("잠실엘스") else "반포자이"
        }

    area_overview = router.plan(ChatbotQueryRequest(question="반포자이 84㎡ 어때?"))
    assert isinstance(area_overview, QueryPlanBundle)
    assert [plan.capability for plan in area_overview.fragments] == [
        "complex_identity", "recent_trade_lookup", "price_trend",
    ]
    assert area_overview.fragments[-1].exclusive_area_square_meters == 84

    regional = router.plan(ChatbotQueryRequest(question="가락동 헬리오시티는 어때"))
    assert isinstance(regional, QueryPlanBundle)
    assert regional.fragments[0].complex_name == "헬리오시티"
    assert regional.fragments[0].region_name == "가락동"
    assert router.plan(ChatbotQueryRequest(question="여기 가격 알려줘")) is None
    assert router.plan(ChatbotQueryRequest(question="잠실엘스 궁금해")) is None
    assert router.overview("잠실엘스", "잠실동").fragments[0].region_name == "잠실동"


@pytest.mark.parametrize(
    ("question", "capability"),
    [
        ("가락동 헬리오시티 전용 59㎡ 최근 실거래 5건", "recent_trade_lookup"),
        ("가락동 헬리오시티 전용 59㎡ 월별 가격 흐름", "price_trend"),
    ],
)
def test_deterministic_router_preserves_region_for_direct_fact_queries(
    question: str, capability: str,
) -> None:
    plan = DeterministicQueryRouter(today=date(2026, 8, 8)).plan(
        ChatbotQueryRequest(question=question)
    )

    assert isinstance(plan, QueryPlan)
    assert plan.capability == capability
    assert plan.complex_name == "헬리오시티"
    assert plan.region_name == "가락동"


@pytest.mark.parametrize(
    "area_text",
    ("59㎡", "59m²", "59m2", "59제곱미터"),
)
def test_question_normalizer_accepts_supported_square_meter_spellings(
    area_text: str,
) -> None:
    normalized = normalize_question(f"임의단지 {area_text} 최근 실거래")

    assert normalized.entity_candidate == "임의단지"
    assert normalized.area_criterion is not None
    assert normalized.area_criterion.exclusive_area_square_meters == 59
    assert normalized.area_criterion.requires_exclusive_confirmation is False


def test_question_normalizer_converts_explicit_pyeong_and_removes_it_from_entity() -> None:
    normalized = normalize_question("임의단지 전용 17.85평의 최근 1년 가격 흐름")

    assert normalized.entity_candidate == "임의단지"
    assert normalized.area_criterion is not None
    assert normalized.area_criterion.exclusive_area_square_meters == 59.01
    assert normalized.area_criterion.conversion_note == "전용 17.85평을 59.01㎡로 환산"

    plan = DeterministicQueryRouter(today=date(2026, 8, 8)).plan(
        ChatbotQueryRequest(question="임의단지 전용 17.85평의 최근 1년 가격 흐름")
    )
    assert isinstance(plan, QueryPlan)
    assert plan.exclusive_area_square_meters == 59.01
    assert plan.area_conversion_note == "전용 17.85평을 59.01㎡로 환산"


@pytest.mark.parametrize("area_text", ("24평", "24평형", "30평대"))
def test_question_normalizer_requires_confirmation_for_bare_pyeong(
    area_text: str,
) -> None:
    normalized = normalize_question(f"임의단지 {area_text} 최근 실거래")

    assert normalized.entity_candidate == "임의단지"
    assert normalized.area_criterion is not None
    assert normalized.area_criterion.exclusive_area_square_meters is None
    assert normalized.area_criterion.requires_exclusive_confirmation is True


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


def test_deterministic_presenter_keeps_comparison_copy_for_multiple_complexes() -> None:
    presenter = DeterministicAnswerPresenter()
    facts = [
        _fact(
            f"property-complex-{complex_id}",
            {"displayName": name, "address": address},
            (FactClaim(name, "TEXT"),),
        )
        for complex_id, name, address in (
            (101, "임의단지A", "서울 가구 가동 1"),
            (102, "임의단지B", "서울 나구 나동 2"),
        )
    ]

    answer = presenter.present(
        plan=QueryPlan(
            "comparison",
            "임의단지A",
            complex_names=("임의단지A", "임의단지B"),
        ),
        facts=facts,
        limitations=[],
        readiness="partial",
    )

    assert answer.sentences[0].text == "같은 기준으로 확인 가능한 비교 항목을 정리했습니다."


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


def test_deterministic_presenter_preserves_rail_lines_and_academy_context() -> None:
    presenter = DeterministicAnswerPresenter()
    rail = EvidenceFact(
        fact_id="rail-station-1",
        payload={
            "stationName": "잠실",
            "lines": ["2호선", "8호선"],
            "distanceMeters": 640,
            "observedDate": "2026-06-30",
        },
        claims=(
            FactClaim("잠실", "TEXT"),
            FactClaim("2호선,8호선", "RAIL_LINES"),
            FactClaim("640", "METERS"),
            FactClaim("2026-06-30", "DATE"),
        ),
        data_as_of=date(2026, 6, 30),
        source_id="transport.rail-station",
    )
    academy = EvidenceFact(
        fact_id="sbiz-academy-location-1",
        payload={
            "facilityName": "가나다 학원",
            "address": "서울 송파구 올림픽로 300",
            "distanceMeters": 800,
            "observedDate": "2026-07-20",
        },
        claims=(
            FactClaim("가나다 학원", "TEXT"),
            FactClaim("서울 송파구 올림픽로 300", "TEXT"),
            FactClaim("800", "METERS"),
            FactClaim("2026-07-20", "DATE"),
        ),
        data_as_of=date(2026, 7, 20),
        source_id="place.sbiz-academy",
    )

    rail_answer = presenter.present(
        plan=QueryPlan("rail_station_lookup", "잠실엘스"),
        facts=[rail], limitations=[], readiness="supported",
    )
    academy_answer = presenter.present(
        plan=QueryPlan("academy_lookup", "잠실엘스"),
        facts=[academy], limitations=[], readiness="supported",
    )

    assert rail_answer.sentences[0].text == (
        "확인된 주변 정보는 잠실역(2호선·8호선) 직선거리 640m · 기준일 2026-06-30입니다."
    )
    assert academy_answer.sentences[0].text == (
        "확인된 주변 정보는 가나다 학원(서울 송파구 올림픽로 300) "
        "직선거리 800m · 기준일 2026-07-20입니다."
    )


def test_academy_presentation_merges_registry_evidence_into_facility_item() -> None:
    location = _fact(
        "sbiz-academy-location-store-1",
        {
            "facilityId": "store-1",
            "facilityName": "솔바이올린학원",
            "distanceMeters": 287,
        },
        (FactClaim("솔바이올린학원", "TEXT"), FactClaim("287", "METERS")),
    )
    registry = _fact(
        "academy-registry-exact-registry-1",
        {
            "facilityId": "store-1",
            "academyName": "솔바이올린학원",
            "matchType": "EXACT",
        },
        (FactClaim("솔바이올린학원", "TEXT"), FactClaim("EXACT", "MATCH_TYPE")),
    )

    _, artifacts = PresentationAssembler().present(
        plan=QueryPlan("academy_lookup", "잠실엘스", radius_meters=800),
        used_facts=[location, registry],
        readiness="supported",
        artifacts=[],
    )

    assert artifacts == [{
        "type": "factList",
        "version": 1,
        "artifactId": "fact-list-academy_lookup-sbiz-academy-location-store-1",
        "title": "확인된 시설 정보",
        "items": [{
            "label": "솔바이올린학원",
            "value": "직선거리 287m",
            "factIds": [
                "sbiz-academy-location-store-1",
                "academy-registry-exact-registry-1",
            ],
        }],
    }]


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


def test_answer_document_rejects_dangling_action_fact_reference() -> None:
    fact = _fact(
        "property-complex-501",
        {"complexId": 501, "displayName": "잠실엘스"},
        (FactClaim("잠실엘스", "TEXT"),),
    )
    document = AnswerDocument.from_grounded_result(
        request=ChatbotQueryRequest(question="잠실엘스 알려줘"),
        request_id="request-invariant",
        plan=QueryPlan("complex_identity", "잠실엘스"),
        draft=DraftAnswer([
            DraftSentence("잠실엘스를 확인했습니다.", [fact.fact_id]),
        ]),
        used_facts=[fact],
        limitations=[],
        readiness="supported",
        artifacts=[],
        actions=[{
            "version": 1,
            "actionId": "nearby-invalid",
            "type": "showNearbyCategory",
            "label": "주변 병원 보기",
            "category": "HOSPITAL",
            "center": {"lat": 37.5, "lng": 127.0},
            "level": 4,
            "factIds": ["property-complex-missing"],
        }],
    )

    with pytest.raises(ValueError, match="unknown evidence fact"):
        document.to_public_dict()


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

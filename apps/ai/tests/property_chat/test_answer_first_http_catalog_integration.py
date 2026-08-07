from __future__ import annotations

import asyncio
import json
import re
from collections import Counter
from collections.abc import Iterator
from datetime import date, timedelta
from pathlib import Path

import psycopg
import pytest
from fastapi.testclient import TestClient
from testcontainers.postgres import PostgresContainer

from ai_service.auth import AuthenticatedUser, get_authenticator
from ai_service.chat import ChatbotProviderUnavailable, ConfiguredChatbotEngine, get_chatbot_engine
from ai_service.main import app
from ai_service.models import ChatbotQueryRequest
from ai_service.property_chat import answer_first_eval
from ai_service.property_chat.academy_locations import PostgresAcademyLocationRepository
from ai_service.property_chat.answer_first_eval import (
    AnswerFirstGoldenCase,
    evaluate_rollout_catalog,
    load_answer_first_catalog,
)
from ai_service.property_chat.models import QueryPlan, QueryPlanBundle
from ai_service.property_chat.postgres import PostgresPropertyFactRepository
from ai_service.property_chat.rail_stations import PostgresRailStationRepository


_COMPLEXES = (
    (101, "잠실엘스", "송파구", "11710101", 37.513, 127.082),
    (102, "헬리오시티", "송파구", "11710102", 37.497, 127.107),
    (103, "래미안원베일리", "서초구", "11650101", 37.506, 126.994),
    (104, "반포자이", "서초구", "11650101", 37.507, 127.010),
    (105, "마포래미안푸르지오", "마포구", "11440101", 37.554, 126.954),
    (106, "올림픽파크포레온", "강동구", "11740101", 37.520, 127.142),
    (107, "아크로리버파크", "서초구", "11650101", 37.510, 126.993),
    (108, "한빛아파트", "송파구", "11710101", 37.514, 127.083),
    (109, "중앙아파트", "마포구", "11440101", 37.555, 126.955),
    (110, "신동 래미안 1단지", "영등포구", "11560101", 37.520, 126.920),
    (111, "신동 래미안 2단지", "영등포구", "11560101", 37.521, 126.921),
    (112, "서울숲테스트단지", "성동구", "11200101", 37.545, 127.040),
)
_MIN_HTTP_CATALOG_QUALITY_PASS_COUNT = 96
_CATALOG_SCENARIO = {"value": "NORMAL_FULL"}


@pytest.fixture(scope="module")
def answer_first_catalog_dsn() -> Iterator[str]:
    with PostgresContainer("postgis/postgis:16-3.4") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            connection.execute("CREATE SCHEMA ai_read")
            connection.execute("CREATE SCHEMA reference_read")
            connection.execute(
                """
                CREATE TABLE ai_read.region_fact (
                    region_id bigint PRIMARY KEY, parent_region_id bigint,
                    region_code text NOT NULL UNIQUE, region_name text NOT NULL,
                    region_type text NOT NULL
                );
                CREATE TABLE ai_read.complex_fact (
                    complex_id bigint PRIMARY KEY, parcel_id bigint NOT NULL,
                    display_name text NOT NULL, name text, trade_name text,
                    region_code text, region_name text, address text,
                    latitude numeric, longitude numeric, marker_safe boolean NOT NULL,
                    data_updated_at timestamptz NOT NULL, unit_count integer, use_date date
                );
                CREATE TABLE ai_read.complex_search_fact (
                    complex_id bigint PRIMARY KEY, display_name text NOT NULL,
                    canonical_name text NOT NULL, trade_name text,
                    canonical_search_name text NOT NULL, aliases text[] NOT NULL,
                    alias_search_names text[] NOT NULL, region_code text,
                    region_name text, address text, search_document text NOT NULL,
                    unit_count integer, use_date date, marker_safe boolean NOT NULL,
                    data_updated_at timestamptz NOT NULL
                );
                CREATE TABLE ai_read.trade_fact (
                    trade_id bigint PRIMARY KEY, complex_id bigint NOT NULL,
                    deal_date date NOT NULL, deal_amount_ten_thousand_krw bigint NOT NULL,
                    exclusive_area_square_meters numeric NOT NULL, floor integer
                );
                CREATE TABLE reference_read.active_source_metadata (
                    source_id text PRIMARY KEY, publication_id uuid NOT NULL,
                    dataset_version text NOT NULL, observed_at timestamptz,
                    source_date date, freshness_days integer NOT NULL
                );
                CREATE TABLE reference_read.source_coverage (
                    publication_id uuid NOT NULL, region_code text NOT NULL,
                    total_count bigint NOT NULL, spatial_count bigint NOT NULL
                );
                CREATE TABLE reference_read.facility_point_fact (
                    publication_id uuid NOT NULL, source_id text NOT NULL,
                    fact_id text PRIMARY KEY, name text NOT NULL, subcategory text NOT NULL,
                    status text NOT NULL, road_address text, lot_address text,
                    position geography(Point, 4326) NOT NULL, dataset_version text NOT NULL,
                    dataset_observed_at timestamptz NOT NULL
                );
                CREATE TABLE reference_read.sbiz_academy_exact_match (
                    sbiz_publication_id uuid NOT NULL, sbiz_fact_id text PRIMARY KEY,
                    registry_fact_id text, registry_academy_name text,
                    registry_status text, registry_dataset_version text,
                    registry_observed_at timestamptz
                );
                CREATE TABLE reference_read.rail_station_occurrence (
                    occurrence_id text PRIMARY KEY, station_name text NOT NULL,
                    line_name text NOT NULL, transfer_lines text[] NOT NULL,
                    latitude double precision NOT NULL, longitude double precision NOT NULL,
                    position geography(Point, 4326) NOT NULL
                );
                INSERT INTO ai_read.region_fact VALUES
                    (11, NULL, '11', '서울특별시', 'si-do'),
                    (11710, 11, '11710', '송파구', 'si-gun-gu'),
                    (11710101, 11710, '11710101', '잠실동', 'eup-myeon-dong'),
                    (11710102, 11710, '11710102', '가락동', 'eup-myeon-dong'),
                    (11650, 11, '11650', '서초구', 'si-gun-gu'),
                    (11650101, 11650, '11650101', '반포동', 'eup-myeon-dong'),
                    (11440, 11, '11440', '마포구', 'si-gun-gu'),
                    (11440101, 11440, '11440101', '아현동', 'eup-myeon-dong'),
                    (11740, 11, '11740', '강동구', 'si-gun-gu'),
                    (11740101, 11740, '11740101', '둔촌동', 'eup-myeon-dong'),
                    (11560, 11, '11560', '영등포구', 'si-gun-gu'),
                    (11560101, 11560, '11560101', '신동', 'eup-myeon-dong'),
                    (11200, 11, '11200', '성동구', 'si-gun-gu'),
                    (11200101, 11200, '11200101', '성수동', 'eup-myeon-dong');
                INSERT INTO reference_read.active_source_metadata VALUES
                    ('place.sbiz-academy', '00000000-0000-0000-0000-000000000001',
                     'academy-v1', '2026-07-20T00:00:00Z', NULL, 45),
                    ('transport.rail-station', '00000000-0000-0000-0000-000000000002',
                     'rail-v1', NULL, '2026-06-30', 410);
                INSERT INTO reference_read.source_coverage VALUES
                    ('00000000-0000-0000-0000-000000000001', 'ALL', 1, 1),
                    ('00000000-0000-0000-0000-000000000002', 'ALL', 1, 1);
                INSERT INTO reference_read.facility_point_fact VALUES
                    ('00000000-0000-0000-0000-000000000001', 'place.sbiz-academy',
                     'academy-1', '테스트학원', 'P10101', 'OPEN', '서울 송파구', NULL,
                     ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography,
                     'academy-v1', '2026-07-20T00:00:00Z');
                INSERT INTO reference_read.rail_station_occurrence VALUES
                    ('rail-1', '잠실', '2호선', ARRAY['8호선'], 37.513, 127.082,
                     ST_SetSRID(ST_MakePoint(127.082, 37.513), 4326)::geography);
                """
            )
            for complex_id, name, district, region_code, latitude, longitude in _COMPLEXES:
                aliases = {
                    "잠실엘스": ["잠실 엘스", "잠실 엘쓰"],
                    "래미안원베일리": ["래미안 원베일리", "레미안원베일리"],
                    "헬리오시티": ["헬리오 시티"],
                }.get(name, [])
                connection.execute(
                    "INSERT INTO ai_read.complex_fact VALUES "
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true,'2026-07-20T00:00:00Z',%s,%s)",
                    (complex_id, complex_id + 1000, name, name, name, region_code,
                     district, f"서울 {district} {name}", latitude, longitude, 1200,
                     date(2010, 1, 1)),
                )
                connection.execute(
                    "INSERT INTO ai_read.complex_search_fact VALUES "
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true,'2026-07-20T00:00:00Z')",
                    (complex_id, name, name, name, name.replace(" ", ""), aliases,
                     [alias.replace(" ", "") for alias in aliases], region_code,
                     district, f"서울 {district} {name}",
                     "".join((name, *aliases)).replace(" ", ""),
                     1200, date(2010, 1, 1)),
                )
                for ordinal, area in enumerate((84.0, 84.5, 59.0), start=1):
                    connection.execute(
                        "INSERT INTO ai_read.trade_fact VALUES (%s,%s,%s,%s,%s,%s)",
                        (complex_id * 10 + ordinal, complex_id,
                         date(2026, 5, ordinal), 150_000 + complex_id * 100 + ordinal,
                         area, 10 + ordinal),
                    )
        yield dsn


class CatalogLanguageModel:
    async def plan_query(self, request: ChatbotQueryRequest) -> QueryPlan | QueryPlanBundle:
        if _CATALOG_SCENARIO["value"] in {"LLM_ALL_TIMEOUT", "LLM_MALFORMED"}:
            raise ChatbotProviderUnavailable()
        question = request.question
        entity = next(
            (
                name
                for _, name, *_ in _COMPLEXES
                if name.replace(" ", "") in question.replace(" ", "")
            ),
            "잠실엘스",
        )
        area_match = re.search(r"(?:전용\s*)?([0-9]+(?:\.[0-9]+)?)\s*㎡", question)
        area = float(area_match.group(1)) if area_match else None
        region_match = re.search(
            r"(송파구|서초구|마포구|강동구|영등포구|성동구)", question
        )
        region = region_match.group(1) if region_match else "송파구"
        if re.search(r"(?:추천|후보|골라)", question):
            return QueryPlan(
                "recommendation", region, region_name=region,
                exclusive_area_square_meters=area, limit=3,
                recommendation_mode="CRITERIA",
                recommendation_criteria=("TRANSIT",)
                if re.search(r"(?:교통|철도)", question)
                else (),
            )
        if re.search(r"(?:비교|차이|대조)", question):
            return QueryPlan(
                "comparison", "잠실엘스",
                complex_names=("잠실엘스", "헬리오시티"),
                exclusive_area_square_meters=area,
            )
        capabilities: list[str] = []
        for capability, pattern in (
            ("complex_identity", r"(?:주소|기본정보|위치|지도|단지\s*정보)"),
            ("recent_trade_lookup", r"(?:실거래|최근\s*거래|거래\s*(?:내역|결과))"),
            ("price_trend", r"(?:가격\s*(?:흐름|추이)|시세\s*추이|월별|거래량)"),
            ("academy_lookup", r"(?:학원|교습소)"),
            ("rail_station_lookup", r"(?:철도|지하철|가까운\s*역|역세권)"),
            ("school_location", r"(?:초등학교|중학교|고등학교|주변\s*학교)"),
            ("retail_location", r"(?:대규모점포|대형마트|백화점|쇼핑시설)"),
            ("childcare_lookup", r"(?:어린이집|유치원)"),
        ):
            if re.search(pattern, question):
                capabilities.append(capability)
        if not capabilities:
            capabilities = ["complex_identity", "recent_trade_lookup"]
        today = date(2026, 7, 20)
        plans = tuple(
            QueryPlan(
                capability, entity,
                start_date=today - timedelta(days=365) if capability == "price_trend" else None,
                end_date=today if capability == "price_trend" else None,
                exclusive_area_square_meters=area,
            )
            for capability in capabilities[:4]
        )
        return plans[0] if len(plans) == 1 else QueryPlanBundle(plans)

    async def draft_answer(self, **_kwargs: object) -> object:
        raise ChatbotProviderUnavailable()


class AcceptingAuthenticator:
    def authenticate(self, _authorization: str | None) -> AuthenticatedUser:
        return AuthenticatedUser(user_id=42)


def test_seeded_http_engine_executes_all_120_cases_in_legacy_and_graph(
    answer_first_catalog_dsn: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    boundary = {"expected_database": "test", "expected_username": "test"}
    property_repository = PostgresPropertyFactRepository(
        answer_first_catalog_dsn, **boundary
    )
    academy_repository = PostgresAcademyLocationRepository(
        answer_first_catalog_dsn, **boundary
    )
    rail_repository = PostgresRailStationRepository(
        answer_first_catalog_dsn, **boundary
    )
    mode = {"value": "off"}
    current_case: dict[str, AnswerFirstGoldenCase | None] = {"value": None}
    def unavailable_repository() -> object:
        raise ChatbotProviderUnavailable()
    def scenario_repository(repository: object) -> object:
        if (
            current_case["value"] is not None
            and current_case["value"].scenario
            in {"OPTIONAL_SOURCE_UNAVAILABLE", "OPTIONAL_SOURCE_TIMEOUT"}
        ):
            raise ChatbotProviderUnavailable()
        return repository
    monkeypatch.setattr(
        "ai_service.chat.get_property_fact_repository", lambda: property_repository
    )
    monkeypatch.setattr(
        "ai_service.chat.get_grounded_language_model", CatalogLanguageModel
    )
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_property_capabilities",
        lambda: frozenset(
            {
                "complex_identity",
                "recent_trade_lookup",
                "price_trend",
                "recommendation",
                "comparison",
            }
        ),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_enabled_reference_capabilities",
        lambda: frozenset({"academy_lookup", "rail_station_lookup"}).union(
            set(current_case["value"].required_capabilities).intersection({
                "school_location", "retail_location", "childcare_lookup",
            }) if current_case["value"] is not None else set()
        ),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_academy_location_repository",
        lambda: scenario_repository(academy_repository),
    )
    monkeypatch.setattr(
        "ai_service.chat.get_rail_station_repository",
        lambda: scenario_repository(rail_repository),
    )
    monkeypatch.setattr("ai_service.chat.get_school_fact_repository", unavailable_repository)
    monkeypatch.setattr("ai_service.chat.get_point_facility_repository", unavailable_repository)
    monkeypatch.setattr("ai_service.chat.get_childcare_repository", unavailable_repository)
    monkeypatch.setattr(
        "ai_service.chat.get_supervisor_graph_mode", lambda: mode["value"]
    )
    monkeypatch.setattr(
        "ai_service.chat.get_supervisor_graph_canary_percent", lambda: 100
    )
    app.dependency_overrides[get_authenticator] = AcceptingAuthenticator
    app.dependency_overrides[get_chatbot_engine] = ConfiguredChatbotEngine
    client = TestClient(app)
    temporary_failures: list[tuple[str, str]] = []
    observed: dict[tuple[str, str], tuple[object, object, object, object]] = {}
    cases = load_answer_first_catalog(
        Path(answer_first_eval.__file__).with_name("answer_first_golden_catalog.json")
    )

    async def run(
        case: AnswerFirstGoldenCase, selected_mode: str
    ) -> dict[str, object]:
        mode["value"] = selected_mode
        current_case["value"] = case
        _CATALOG_SCENARIO["value"] = case.scenario
        response = client.post(
            "/api/v1/chatbot/query",
            headers={"Authorization": "Bearer integration-token"},
            json={
                "question": case.question,
                **({
                    "uiContext": {"selectedComplex": {
                        "complexId": case.selected_complex_id,
                        "parcelId": case.selected_parcel_id,
                    }}
                } if case.selected_complex_id is not None else {}),
                **({"conversationContext": {"messages": list(case.memory)}} if case.memory else {}),
            },
        )
        assert response.status_code == 200
        payload = response.json()
        resolution = payload.get("conversationResolution")
        terminal = payload.get("terminalOutcome")
        observed[(case.case_id, selected_mode)] = (
            resolution.get("answerMode") if isinstance(resolution, dict) else None,
            terminal.get("status") if isinstance(terminal, dict) else None,
            terminal.get("reason") if isinstance(terminal, dict) else None,
            [
                goal.get("capability")
                for goal in resolution.get("goals", [])
                if isinstance(goal, dict)
            ]
            if isinstance(resolution, dict)
            else None,
        )
        if isinstance(terminal, dict) and terminal.get("reason") == "TEMPORARY_FAILURE":
            temporary_failures.append((case.case_id, selected_mode))
        return payload

    try:
        results = asyncio.run(
            evaluate_rollout_catalog(
                cases,
                lambda case: run(case, "off"),
                lambda case: run(case, "active"),
            )
        )
    finally:
        app.dependency_overrides.clear()
        property_repository.close()
        academy_repository.close()
        rail_repository.close()

    assert len(results) == 120
    assert temporary_failures == [], json.dumps(temporary_failures, ensure_ascii=False)
    clean_results = [result for result in results if not result.failures]
    diagnostic = {
        "cleanByCategory": dict(Counter(
            case.category
            for case, result in zip(cases, results, strict=True)
            if not result.failures
        )),
        "failureCounts": dict(Counter(
            failure
            for result in results
            for failure in result.failures
        )),
        "failedCaseIds": [result.case_id for result in results if result.failures],
        "failuresByCase": {
            result.case_id: result.failures for result in results if result.failures
        },
        "observed": {
            case_id: {selected_mode: observed[(case_id, selected_mode)] for selected_mode in ("off", "active")}
            for case_id in (result.case_id for result in results if result.failures)
        },
    }
    assert len(clean_results) >= _MIN_HTTP_CATALOG_QUALITY_PASS_COUNT, json.dumps(
        diagnostic, ensure_ascii=False, sort_keys=True
    )
    clean_by_category = Counter(
        case.category
        for case, result in zip(cases, results, strict=True)
        if not result.failures
    )
    assert clean_by_category["broad_overview"] == 6
    assert clean_by_category["provider_failure"] == 8
    assert (
        clean_by_category["ambiguous_typo"]
        + clean_by_category["multi_complex_ambiguity"]
    ) >= 9
    assert clean_by_category["compound"] >= 10
    assert all(
        failure not in {
            "GOAL_SET_MISMATCH", "ANSWER_MODE_MISMATCH",
            "TERMINAL_OUTCOME_MISMATCH", "FACT_CITATION_CLOSURE_FAILED",
        }
        for result in results
        for failure in result.failures
    ), json.dumps(diagnostic, ensure_ascii=False, sort_keys=True)
    assert all(
        "ANSWER_SENTENCE_DUPLICATED" not in failure
        and "ANSWER_HALF_DUPLICATED" not in failure
        for result in results
        for failure in result.failures
    )
    assert all("LEGACY_RUNNER_FAILED" not in result.failures for result in results)
    assert all("GRAPH_RUNNER_FAILED" not in result.failures for result in results)
    assert all(
        not any("TERMINAL_OUTCOME_MISSING" in failure for failure in result.failures)
        for result in results
    )

from __future__ import annotations

import re
from datetime import date, timedelta

from ai_service.models import ChatbotQueryRequest

from .models import CAPABILITY_EXECUTION_ORDER, QueryPlan, QueryPlanBundle
from .question_normalizer import normalize_question


class DeterministicQueryRouter:
    """Conservative fallback router for clear, supported Korean property intents."""

    def __init__(self, *, today: date) -> None:
        self._today = today

    def plan(self, request: ChatbotQueryRequest) -> QueryPlan | QueryPlanBundle | None:
        normalized = normalize_question(request.question)
        question = normalized.normalized_question
        complex_name = normalized.entity_candidate
        area = (
            normalized.area_criterion.exclusive_area_square_meters
            if normalized.area_criterion is not None else None
        )
        area_options = _area_options(normalized)
        period_start = self._today - timedelta(days=normalized.period_days or 365)
        if complex_name is None:
            return None
        if re.search(r"(?:비교|차이|추천|후보)", question):
            return None

        if normalized.overview:
            plans = [
                QueryPlan("complex_identity", complex_name, **area_options),
                QueryPlan(
                    "recent_trade_lookup",
                    complex_name,
                    region_name=normalized.region_hint,
                    start_date=period_start,
                    end_date=self._today,
                    exclusive_area_square_meters=area,
                    limit=3,
                    **area_options,
                ),
            ]
            if area is not None:
                plans.append(QueryPlan(
                    "price_trend",
                    complex_name,
                    region_name=normalized.region_hint,
                    start_date=period_start,
                    end_date=self._today,
                    exclusive_area_square_meters=area,
                    **area_options,
                ))
            if normalized.region_hint is not None:
                plans[0] = QueryPlan(
                    "complex_identity", complex_name, region_name=normalized.region_hint
                )
            return QueryPlanBundle(tuple(plans))
        plans: list[tuple[int, QueryPlan]] = []
        trend_match = re.search(r"(?:가격\s*(?:흐름|추이)|월별|거래량)", question)
        if trend_match is not None:
            plans.append((trend_match.start(), QueryPlan(
                "price_trend",
                complex_name,
                region_name=normalized.region_hint,
                start_date=period_start,
                end_date=self._today,
                exclusive_area_square_meters=_exclusive_area(question),
                **area_options,
            )))
        trade_match = re.search(r"(?:실거래|최근\s*거래|거래\s*내역)", question)
        if trade_match is not None:
            plans.append((trade_match.start(), QueryPlan(
                "recent_trade_lookup",
                complex_name,
                region_name=normalized.region_hint,
                start_date=period_start,
                end_date=self._today,
                exclusive_area_square_meters=_exclusive_area(question),
                limit=normalized.requested_count or _result_limit(question),
                **area_options,
            )))
        for capability, pattern in (
            ("rail_station_lookup", r"(?:철도|지하철|가까운\s*역|역[·\s-]*노선|역세권)"),
            ("academy_lookup", r"(?:학원|교습소)"),
            ("school_location", r"(?:초등학교|중학교|고등학교|주변\s*학교)"),
            ("retail_location", r"(?:대형마트|백화점|쇼핑센터|복합몰|대규모점포)"),
            ("childcare_lookup", r"(?:어린이집|유치원)"),
        ):
            match = re.search(pattern, question)
            if match is not None:
                plans.append((match.start(), QueryPlan(
                    capability, complex_name, region_name=normalized.region_hint
                )))
        identity_position = _identity_position(question)
        if identity_position is not None:
            plans.append((identity_position, QueryPlan(
                "complex_identity", complex_name, region_name=normalized.region_hint
            )))
        if not plans:
            return None
        capability_order = {
            capability: index
            for index, capability in enumerate(CAPABILITY_EXECUTION_ORDER)
        }
        ordered = tuple(
            plan
            for _position, plan in sorted(
                plans,
                key=lambda item: capability_order[item[1].capability],
            )
        )
        return ordered[0] if len(ordered) == 1 else QueryPlanBundle(ordered[:4])

    def overview(
        self,
        complex_name: str,
        region_name: str | None,
        exclusive_area_square_meters: float | None = None,
    ) -> QueryPlanBundle:
        plans = [
            QueryPlan("complex_identity", complex_name, region_name=region_name),
            QueryPlan(
                "recent_trade_lookup",
                complex_name,
                region_name=region_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
                exclusive_area_square_meters=exclusive_area_square_meters,
                limit=3,
            ),
        ]
        if exclusive_area_square_meters is not None:
            plans.append(QueryPlan(
                "price_trend",
                complex_name,
                region_name=region_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
                exclusive_area_square_meters=exclusive_area_square_meters,
            ))
        return QueryPlanBundle(tuple(plans))


def _exclusive_area(question: str) -> float | None:
    normalized = normalize_question(question)
    criterion = normalized.area_criterion
    return criterion.exclusive_area_square_meters if criterion is not None else None


def _area_options(normalized) -> dict[str, object]:
    criterion = normalized.area_criterion
    if criterion is None:
        return {}
    return {
        "area_input_text": criterion.input_text,
        "area_conversion_note": criterion.conversion_note,
        "area_confirmation_required": criterion.requires_exclusive_confirmation,
    }


def _result_limit(question: str) -> int:
    match = re.search(r"([1-9]|10)\s*건", question)
    return int(match.group(1)) if match else 5


def _identity_position(question: str) -> int | None:
    for match in re.finditer(r"(?:위치|주소|기본\s*정보|어디|단지\s*정보)", question):
        prefix = question[max(0, match.start() - 8):match.start()]
        if match.group() == "위치" and re.search(
            r"(?:학원|학교|점포|마트|어린이집|유치원)\s*$", prefix
        ):
            continue
        return match.start()
    return None

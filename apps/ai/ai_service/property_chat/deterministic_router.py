from __future__ import annotations

import re
from datetime import date, timedelta

from ai_service.models import ChatbotQueryRequest

from .models import QueryPlan, QueryPlanBundle


class DeterministicQueryRouter:
    """Conservative fallback router for clear, supported Korean property intents."""

    def __init__(self, *, today: date) -> None:
        self._today = today

    def plan(self, request: ChatbotQueryRequest) -> QueryPlan | QueryPlanBundle | None:
        question = " ".join(request.question.split())
        complex_name = _complex_name(question)
        if complex_name is None:
            return None

        if re.search(r"(?:전체적|전반적|이\s*단지\s*어때|살기\s*괜찮)", question):
            return QueryPlanBundle((
                QueryPlan("complex_identity", complex_name),
                QueryPlan(
                    "recent_trade_lookup",
                    complex_name,
                    start_date=self._today - timedelta(days=365),
                    end_date=self._today,
                    limit=3,
                ),
                QueryPlan(
                    "price_trend",
                    complex_name,
                    start_date=self._today - timedelta(days=365),
                    end_date=self._today,
                ),
                QueryPlan("rail_station_lookup", complex_name),
            ))
        plans: list[tuple[int, QueryPlan]] = []
        trend_match = re.search(r"(?:가격\s*(?:흐름|추이)|월별|거래량)", question)
        if trend_match is not None:
            plans.append((trend_match.start(), QueryPlan(
                "price_trend",
                complex_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
                exclusive_area_square_meters=_exclusive_area(question),
            )))
        trade_match = re.search(r"(?:실거래|최근\s*거래|거래\s*내역)", question)
        if trade_match is not None:
            plans.append((trade_match.start(), QueryPlan(
                "recent_trade_lookup",
                complex_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
                exclusive_area_square_meters=_exclusive_area(question),
                limit=_result_limit(question),
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
                plans.append((match.start(), QueryPlan(capability, complex_name)))
        identity_position = _identity_position(question)
        if identity_position is not None:
            plans.append((identity_position, QueryPlan("complex_identity", complex_name)))
        if not plans:
            return None
        ordered = tuple(plan for _position, plan in sorted(plans, key=lambda item: item[0]))
        return ordered[0] if len(ordered) == 1 else QueryPlanBundle(ordered[:4])

    def overview(self, complex_name: str, region_name: str | None) -> QueryPlanBundle:
        return QueryPlanBundle((
            QueryPlan("complex_identity", complex_name, region_name=region_name),
            QueryPlan(
                "recent_trade_lookup",
                complex_name,
                region_name=region_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
                limit=3,
            ),
            QueryPlan(
                "price_trend",
                complex_name,
                region_name=region_name,
                start_date=self._today - timedelta(days=365),
                end_date=self._today,
            ),
            QueryPlan("rail_station_lookup", complex_name, region_name=region_name),
        ))


def _complex_name(question: str) -> str | None:
    candidate = re.split(
        r"\s+(?:전용|최근|가격|실거래|거래|위치|주소|어디|주변|가까운|전체적|전반적)",
        question,
        maxsplit=1,
    )[0].strip(" ?!,.\"")
    if candidate in {"이 단지", "여기", "이곳"}:
        return None
    return candidate if 1 <= len(candidate) <= 100 else None


def _exclusive_area(question: str) -> float | None:
    match = re.search(r"(?:전용\s*)?([0-9]+(?:\.[0-9]+)?)\s*(?:㎡|m2|제곱미터)", question)
    return float(match.group(1)) if match else None


def _result_limit(question: str) -> int:
    match = re.search(r"([1-9]|10)\s*건", question)
    return int(match.group(1)) if match else 5


def _identity_position(question: str) -> int | None:
    for match in re.finditer(r"(?:위치|주소|어디|단지\s*정보)", question):
        prefix = question[max(0, match.start() - 8):match.start()]
        if match.group() == "위치" and re.search(
            r"(?:학원|학교|점포|마트|어린이집|유치원)\s*$", prefix
        ):
            continue
        return match.start()
    return None

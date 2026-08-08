from __future__ import annotations

from collections.abc import Iterable

from .models import DraftAnswer, DraftClaim, DraftSentence, EvidenceFact, FactClaim, QueryPlan


_SUPPORTED_COPY = {
    "complex_identity": "확인된 단지 기본정보를 정리했습니다.",
    "recent_trade_lookup": "확인된 최근 실거래를 거래일 순으로 정리했습니다.",
    "price_trend": "확인된 월별 가격과 거래량을 정리했습니다.",
    "school_location": "확인된 주변 학교 위치를 정리했습니다.",
    "academy_registry_summary": "확인된 공식 학원·교습소 집계를 정리했습니다.",
    "academy_lookup": "확인된 주변 학원 위치를 정리했습니다.",
    "rail_station_lookup": "확인된 주변 철도역 위치와 노선을 정리했습니다.",
    "retail_location": "확인된 주변 대규모점포 위치를 정리했습니다.",
    "childcare_lookup": "확인된 주변 어린이집 정보를 정리했습니다.",
    "kakao_place_search": "지도에서 주변 장소를 이어서 확인할 수 있습니다.",
    "comparison": "같은 기준으로 확인 가능한 비교 항목을 정리했습니다.",
    "recommendation": "현재 데이터에서 먼저 살펴볼 후보를 정리했습니다.",
}


class DeterministicAnswerPresenter:
    """Build a grounded answer without depending on a text-generation provider."""

    def present(
        self,
        *,
        plan: QueryPlan,
        facts: list[EvidenceFact],
        limitations: list[str],
        readiness: str,
    ) -> DraftAnswer:
        if readiness == "unavailable" or not facts:
            return DraftAnswer([
                DraftSentence(
                    limitations[0]
                    if limitations
                    else "현재 확인 가능한 근거가 없습니다.",
                    [],
                    [],
                )
            ])

        return DraftAnswer([
            DraftSentence(
                _grounded_copy(plan, facts),
                [fact.fact_id for fact in facts],
                _all_claims(facts),
            )
        ])


def _grounded_copy(plan: QueryPlan, facts: list[EvidenceFact]) -> str:
    complex_facts = [
        fact for fact in facts if fact.fact_id.startswith("property-complex-")
    ]
    complex_fact = next(
        iter(complex_facts),
        None,
    )
    if plan.capability == "complex_identity" or (
        complex_fact is not None and len(facts) == 1
    ):
        assert complex_fact is not None
        name = complex_fact.payload.get("displayName")
        address = complex_fact.payload.get("address")
        if isinstance(name, str) and isinstance(address, str):
            return f"{name}의 주소는 {address}로 확인했습니다."
        if isinstance(name, str):
            return f"{name}의 확인된 단지 기본정보를 정리했습니다."
    if plan.capability != "comparison" and len(complex_facts) > 1:
        candidates = []
        for fact in complex_facts[:6]:
            name = fact.payload.get("displayName")
            address = fact.payload.get("address")
            if isinstance(name, str):
                candidates.append(
                    f"{name}({address})" if isinstance(address, str) and address else name
                )
        if candidates:
            return (
                "동명 또는 유사 단지 후보로 확인했습니다: "
                + "; ".join(candidates)
                + ". 조회할 주소나 지역을 선택해 주세요."
            )

    if plan.capability in {"recent_trade_lookup", "price_trend"}:
        details = [_property_observation(fact) for fact in facts]
        observed = [detail for detail in details if detail]
        if observed:
            label = "참고 거래" if plan.capability == "recent_trade_lookup" else "확인값"
            return f"{label}은 " + "; ".join(observed[:5]) + "입니다."

    facility_details = [_facility_observation(fact) for fact in facts]
    observed_facilities = [detail for detail in facility_details if detail]
    if observed_facilities:
        return "확인된 주변 정보는 " + "; ".join(observed_facilities[:5]) + "입니다."

    return _SUPPORTED_COPY[plan.capability]


def _property_observation(fact: EvidenceFact) -> str | None:
    payload = fact.payload
    deal_date = payload.get("dealDate")
    area = payload.get("exclusiveAreaSquareMeters")
    floor = payload.get("floor")
    amount = _claim_value(fact.claims, "KOREAN_KRW_DISPLAY")
    if isinstance(deal_date, str) and isinstance(area, int | float) and amount:
        floor_text = f", {floor}층" if isinstance(floor, int) else ""
        return f"{deal_date} 전용 {area:g}㎡ {amount}{floor_text}"

    month = payload.get("month")
    average = _claim_value(fact.claims, "KOREAN_KRW_AVERAGE_DISPLAY")
    minimum = _claim_value(fact.claims, "KOREAN_KRW_MIN_DISPLAY")
    maximum = _claim_value(fact.claims, "KOREAN_KRW_MAX_DISPLAY")
    trade_count = payload.get("tradeCount")
    if isinstance(month, str) and average and isinstance(trade_count, int):
        range_text = (
            f", 최솟값 {minimum}, 최댓값 {maximum}"
            if minimum is not None and maximum is not None else ""
        )
        return f"{month} 평균 {average}, 거래 {trade_count}건{range_text}"
    return None


def _facility_observation(fact: EvidenceFact) -> str | None:
    payload = fact.payload
    distance = payload.get("distanceMeters")
    observed_date = payload.get("observedDate")
    station_name = payload.get("stationName")
    lines = payload.get("lines")
    if (
        isinstance(station_name, str)
        and isinstance(lines, list)
        and lines
        and all(isinstance(line, str) and line for line in lines)
        and isinstance(distance, int | float)
    ):
        station_label = station_name if station_name.endswith("역") else f"{station_name}역"
        date_text = (
            f" · 기준일 {observed_date}" if isinstance(observed_date, str) else ""
        )
        return (
            f"{station_label}({'·'.join(lines)}) 직선거리 {distance:g}m"
            f"{date_text}"
        )

    academy_name = payload.get("facilityName")
    address = payload.get("address")
    if (
        fact.source_id == "place.sbiz-academy"
        and isinstance(academy_name, str)
        and isinstance(distance, int | float)
    ):
        address_text = f"({address})" if isinstance(address, str) and address else ""
        date_text = (
            f" · 기준일 {observed_date}" if isinstance(observed_date, str) else ""
        )
        return f"{academy_name}{address_text} 직선거리 {distance:g}m{date_text}"

    name = next(
        (
            payload.get(key)
            for key in ("schoolName", "facilityName", "stationName", "centerName")
            if isinstance(payload.get(key), str)
        ),
        None,
    )
    if isinstance(name, str) and isinstance(distance, int | float):
        return f"{name} 직선거리 {distance:g}m"

    radius = payload.get("radiusMeters")
    matched = payload.get("matchedCount")
    verified_zero = payload.get("verifiedZero")
    if (
        isinstance(radius, int)
        and isinstance(matched, int)
        and verified_zero is True
    ):
        return f"반경 {radius}m 안에서 확인된 시설 {matched}곳"
    return None


def _claim_value(claims: Iterable[FactClaim], unit: str) -> str | None:
    return next((claim.value for claim in claims if claim.unit == unit), None)


def _all_claims(facts: list[EvidenceFact]) -> list[DraftClaim]:
    return [
        DraftClaim(fact_id=fact.fact_id, value=claim.value, unit=claim.unit)
        for fact in facts
        for claim in fact.claims
    ]

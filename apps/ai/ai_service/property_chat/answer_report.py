from __future__ import annotations

from collections.abc import Iterable
import json

from .models import EvidenceFact, QueryPlan


def build_answer_report(
    *,
    plan: QueryPlan,
    ui_summary: dict[str, object] | None,
    artifacts: tuple[dict[str, object], ...],
    actions: tuple[dict[str, object], ...],
    facts: tuple[EvidenceFact, ...],
    preferred_primary_artifact_id: str | None = None,
) -> tuple[dict[str, object] | None, tuple[dict[str, object], ...]]:
    """Builds a bounded presentation report without becoming a fact owner."""
    fact_by_id = {fact.fact_id: fact for fact in facts}
    allowed_fact_ids = set(fact_by_id)
    primary = next((
        artifact for artifact in artifacts
        if preferred_primary_artifact_id is not None
        and _artifact_id(artifact) == preferred_primary_artifact_id
    ), None)
    if primary is None:
        primary = next((
            artifact for artifact in artifacts
            if artifact.get("type") in {
                "tradeTable", "trendTable", "comparisonTable",
                "recommendationTable", "recommendationCards",
            }
            and _artifact_id(artifact)
        ), None)
    if primary is None:
        primary = next((artifact for artifact in artifacts if _artifact_id(artifact)), None)
    profiles: tuple[dict[str, object], ...] = ()
    highlights: list[dict[str, object]] = []
    if primary is not None and primary.get("type") == "recommendationTable":
        profiles, highlights = _criteria_recommendation_details(primary, fact_by_id)
    elif primary is not None and primary.get("type") == "recommendationCards":
        profiles, highlights = _budget_recommendation_details(primary, fact_by_id)

    augmented = _append_bounded_artifacts(artifacts, profiles)
    primary_id = _artifact_id(primary) if primary is not None else None
    opening = _opening(plan, ui_summary, primary, facts)
    if opening is None:
        return None, augmented
    basis = _basis(ui_summary, allowed_fact_ids)
    detail_ids = [
        artifact_id
        for artifact in augmented
        if artifact.get("type") in {"factList", "candidateProfile"}
        and (artifact_id := _artifact_id(artifact)) is not None
        and artifact_id != primary_id
    ][:5]
    report = {
        "version": 1,
        "kind": _report_kind(plan.capability),
        "opening": opening,
        "basis": basis,
        "primaryArtifactId": primary_id,
        "highlights": highlights,
        "detailArtifactIds": detail_ids,
        "actionIds": [
            action_id
            for action in actions
            if isinstance((action_id := action.get("actionId")), str)
        ],
    }
    return report, augmented


def _criteria_recommendation_details(
    table: dict[str, object],
    fact_by_id: dict[str, EvidenceFact],
) -> tuple[tuple[dict[str, object], ...], list[dict[str, object]]]:
    raw_rows = table.get("rows")
    raw_basis = table.get("basis")
    if not isinstance(raw_rows, list) or not isinstance(raw_basis, dict):
        return (), []
    criteria = raw_basis.get("criteriaOrder")
    criteria_order = tuple(item for item in criteria if isinstance(item, str)) \
        if isinstance(criteria, list) else ()
    radius = raw_basis.get("radiusMeters")
    profiles: list[dict[str, object]] = []
    highlights: list[dict[str, object]] = []
    for index, raw_row in enumerate(raw_rows[:5]):
        if not isinstance(raw_row, dict):
            continue
        complex_id = raw_row.get("complexId")
        name = raw_row.get("complexName")
        rank = raw_row.get("order")
        if not isinstance(complex_id, int) or not isinstance(name, str) or not isinstance(rank, int):
            continue
        complex_fact = fact_by_id.get(f"property-complex-{complex_id}")
        if complex_fact is None:
            continue
        metrics = raw_row.get("metrics")
        metrics = metrics if isinstance(metrics, dict) else {}
        reasons: list[dict[str, object]] = []
        sections: list[dict[str, object]] = []
        section_items: dict[str, list[dict[str, object]]] = {}
        trade_fact = next((
            fact for fact in fact_by_id.values()
            if fact.fact_id.startswith(f"criteria-trade-basis-{complex_id}-")
        ), None)
        if trade_fact is not None:
            latest_date = trade_fact.payload.get("latestTradeDate")
            latest_amount = trade_fact.payload.get("latestTradeAmountTenThousandKrw")
            median_amount = trade_fact.payload.get("medianAmountTenThousandKrw")
            trade_items = []
            if isinstance(latest_date, str) and isinstance(latest_amount, int):
                trade_items.append({
                    "label": "최근 거래",
                    "value": f"{latest_date} · {_krw(latest_amount)}",
                    "factIds": [trade_fact.fact_id],
                })
            if isinstance(median_amount, int):
                trade_items.append({
                    "label": "최근 3건 중앙값",
                    "value": _krw(median_amount),
                    "factIds": [trade_fact.fact_id],
                })
            if trade_items:
                sections.append({"key": "TRADE", "label": "같은 면적 최근 실거래", "items": trade_items})
        for key in criteria_order:
            metric = metrics.get(key)
            if not isinstance(metric, dict) or metric.get("availability") != "available":
                continue
            fact_ids = _fact_ids(metric.get("factIds"), fact_by_id)
            if not fact_ids:
                continue
            rendered = _metric_value(key, metric, radius)
            if rendered is None:
                continue
            label, section_key, _ = _metric_labels(key)
            reason_text = _metric_reason(name, key, metric, radius)
            reasons.append({"text": reason_text, "factIds": list(fact_ids)})
            section_items.setdefault(section_key, []).append({
                "label": label,
                "value": rendered,
                "factIds": list(fact_ids),
            })
        for section_key, section_label in (
            ("EDUCATION", "교육"),
            ("TRANSPORT", "교통"),
            ("LIFESTYLE", "생활 인프라"),
        ):
            items = section_items.get(section_key)
            if items:
                sections.append({"key": section_key, "label": section_label, "items": items})
        if not reasons:
            unit_count = raw_row.get("unitCount")
            body = (
                f"{unit_count:,}세대 규모를 확인할 수 있는 후보입니다."
                if isinstance(unit_count, int)
                else "단지 기본정보를 확인할 수 있는 후보입니다."
            )
            reasons.append({"text": body, "factIds": [complex_fact.fact_id]})
        all_fact_ids = tuple(dict.fromkeys((
            complex_fact.fact_id,
            *((trade_fact.fact_id,) if trade_fact is not None else ()),
            *(fact_id for reason in reasons for fact_id in reason["factIds"]),
        )))
        payload = complex_fact.payload
        profiles.append({
            "type": "candidateProfile",
            "version": 1,
            "artifactId": f"candidate-profile-{complex_id}",
            "title": name.strip(),
            "rank": rank,
            "complexId": complex_id,
            "address": payload.get("address") if isinstance(payload.get("address"), str) else None,
            "unitCount": payload.get("unitCount") if isinstance(payload.get("unitCount"), int) else None,
            "useDate": payload.get("useDate") if isinstance(payload.get("useDate"), str) else None,
            "reasons": reasons[:3],
            "sections": sections,
            "factIds": list(all_fact_ids),
        })
        if index < 2:
            highlight_reason = reasons[min(index, len(reasons) - 1)]
            highlights.append({
                "complexId": complex_id,
                "title": f"{rank}순위 · {name.strip()}",
                "body": highlight_reason["text"],
                "factIds": highlight_reason["factIds"],
            })
    return tuple(profiles), highlights


def _budget_recommendation_details(
    cards_artifact: dict[str, object],
    fact_by_id: dict[str, EvidenceFact],
) -> tuple[tuple[dict[str, object], ...], list[dict[str, object]]]:
    cards = cards_artifact.get("cards")
    if not isinstance(cards, list):
        return (), []
    profiles: list[dict[str, object]] = []
    highlights: list[dict[str, object]] = []
    for index, card in enumerate(cards[:5]):
        if not isinstance(card, dict):
            continue
        complex_id, name, rank = card.get("complexId"), card.get("complexName"), card.get("rank")
        if not isinstance(complex_id, int) or not isinstance(name, str) or not isinstance(rank, int):
            continue
        complex_fact = fact_by_id.get(f"property-complex-{complex_id}")
        if complex_fact is None:
            continue
        breakdown = card.get("scoreBreakdown")
        breakdown = breakdown if isinstance(breakdown, list) else []
        reasons = []
        for item in breakdown:
            if not isinstance(item, dict) or item.get("key") == "PRICE":
                continue
            fact_ids = _fact_ids(item.get("factIds"), fact_by_id)
            label = item.get("label")
            distance = item.get("distanceMeters")
            if fact_ids and isinstance(label, str):
                text = (
                    f"{label}은 직선거리 {distance:,}m 기준으로 확인했습니다."
                    if isinstance(distance, int)
                    else f"{label} 관련 관찰값을 확인했습니다."
                )
                reasons.append({"text": text, "factIds": list(fact_ids)})
        latest = card.get("latestTrade")
        sections: list[dict[str, object]] = []
        if isinstance(latest, dict):
            fact_ids = _fact_ids(latest.get("factIds"), fact_by_id)
            if fact_ids:
                trade_items = []
                trade_date = latest.get("date")
                trade_amount = latest.get("amountTenThousandKrw")
                reason_text = (
                    f"{name}: {trade_date} 최근 거래 금액은 {_krw(trade_amount)}입니다."
                    if isinstance(trade_date, str) and isinstance(trade_amount, int)
                    else f"{name}: 예산 조건을 통과한 최근 거래가 확인됩니다."
                )
                reasons.insert(0, {"text": reason_text, "factIds": list(fact_ids)})
                if isinstance(trade_date, str) and isinstance(trade_amount, int):
                    trade_items.append({
                        "label": "최근 거래",
                        "value": f"{trade_date} · {_krw(trade_amount)}",
                        "factIds": list(fact_ids),
                    })
                median = card.get("recentThreeMedian")
                if isinstance(median, dict):
                    median_ids = _fact_ids(median.get("factIds"), fact_by_id)
                    median_amount = median.get("amountTenThousandKrw")
                    if median_ids and isinstance(median_amount, int):
                        trade_items.append({
                            "label": "최근 3건 중앙값",
                            "value": _krw(median_amount),
                            "factIds": list(median_ids),
                        })
                if trade_items:
                    sections.append({"key": "TRADE", "label": "최근 실거래", "items": trade_items})
        if not reasons:
            reasons.append({"text": "단지 기본정보를 확인할 수 있는 후보입니다.", "factIds": [complex_fact.fact_id]})
        payload = complex_fact.payload
        profile_fact_ids = tuple(dict.fromkeys((
            complex_fact.fact_id,
            *(fact_id for reason in reasons for fact_id in reason["factIds"]),
            *(
                fact_id
                for section in sections
                for item in section["items"]
                for fact_id in item["factIds"]
            ),
        )))
        profiles.append({
            "type": "candidateProfile", "version": 1,
            "artifactId": f"candidate-profile-{complex_id}", "title": name.strip(),
            "rank": rank, "complexId": complex_id,
            "address": payload.get("address") if isinstance(payload.get("address"), str) else None,
            "unitCount": payload.get("unitCount") if isinstance(payload.get("unitCount"), int) else None,
            "useDate": payload.get("useDate") if isinstance(payload.get("useDate"), str) else None,
            "reasons": reasons[:3], "sections": sections, "factIds": list(profile_fact_ids),
        })
        if index < 2:
            highlights.append({
                "complexId": complex_id,
                "title": f"{rank}순위 · {name.strip()}",
                "body": reasons[0]["text"],
                "factIds": reasons[0]["factIds"],
            })
    return tuple(profiles), highlights


def _opening(
    plan: QueryPlan,
    ui_summary: dict[str, object] | None,
    primary: dict[str, object] | None,
    facts: tuple[EvidenceFact, ...],
) -> dict[str, object] | None:
    if isinstance(ui_summary, dict):
        headline = ui_summary.get("headline")
        if isinstance(headline, dict):
            text, raw_ids = headline.get("text"), headline.get("factIds")
            ids = _fact_ids(raw_ids, {fact.fact_id: fact for fact in facts})
            if isinstance(text, str) and ids:
                return {"text": text.strip(), "factIds": list(ids)}
    if plan.capability == "recommendation" and primary is not None:
        rows = primary.get("rows") if primary.get("type") == "recommendationTable" else primary.get("cards")
        count = len(rows) if isinstance(rows, list) else 0
        basis = primary.get("basis")
        scope = basis.get("scopeLabel") if isinstance(basis, dict) else plan.region_name
        text = (
            f"{scope}에서 확인한 후보 중 먼저 살펴볼 {count}곳입니다."
            if isinstance(scope, str) and count > 0
            else f"현재 데이터에서 먼저 살펴볼 후보 {count}곳입니다."
        )
        ids = _fact_ids_from_iterable(facts)
        scope_ids = tuple(
            fact.fact_id for fact in facts if fact.fact_id.startswith("criteria-scope-")
        )
        return {"text": text, "factIds": list(scope_ids or ids[:1])}
    return None


def _basis(
    ui_summary: dict[str, object] | None,
    allowed_fact_ids: set[str],
) -> list[dict[str, object]]:
    if not isinstance(ui_summary, dict):
        return []
    criteria = ui_summary.get("criteria")
    if not isinstance(criteria, list):
        return []
    result = []
    for item in criteria[:4]:
        if not isinstance(item, dict):
            continue
        if item.get("key") == "representativeSelection":
            continue
        label, value = item.get("label"), item.get("value")
        ids = item.get("factIds")
        if not isinstance(label, str) or not isinstance(value, str) or not isinstance(ids, list):
            continue
        fact_ids = [fact_id for fact_id in ids if isinstance(fact_id, str) and fact_id in allowed_fact_ids]
        if fact_ids:
            result.append({"text": f"{label}: {value}", "factIds": fact_ids})
    return result


def _metric_value(key: str, metric: dict[str, object], radius: object) -> str | None:
    value = metric.get("value")
    nearest = metric.get("nearestDistanceMeters")
    if not isinstance(value, int):
        return None
    if key == "ACADEMY":
        scope = f"{radius:,}m 내 " if isinstance(radius, int) else ""
        suffix = f" · 최근접 {nearest:,}m" if isinstance(nearest, int) else ""
        return f"{scope}{value:,}곳{suffix}"
    return f"직선거리 {value:,}m"


def _metric_reason(name: str, key: str, metric: dict[str, object], radius: object) -> str:
    rendered = _metric_value(key, metric, radius) or "관찰값 확인"
    label, _, _ = _metric_labels(key)
    return f"{name}: {label} 관찰값은 {rendered}입니다."


def _metric_labels(key: str) -> tuple[str, str, str]:
    return {
        "ACADEMY": ("학원 접근성", "EDUCATION", "교육"),
        "SCHOOL": ("학교 위치", "EDUCATION", "교육"),
        "TRANSIT": ("철도역 거리", "TRANSPORT", "교통"),
        "SHOPPING": ("대규모점포 거리", "LIFESTYLE", "생활 인프라"),
    }.get(key, ("확인 기준", "LIFESTYLE", "생활 인프라"))


def _fact_ids(value: object, fact_by_id: dict[str, EvidenceFact]) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    return tuple(dict.fromkeys(
        fact_id for fact_id in value if isinstance(fact_id, str) and fact_id in fact_by_id
    ))


def _fact_ids_from_iterable(facts: Iterable[EvidenceFact]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(fact.fact_id for fact in facts))


def _artifact_id(artifact: dict[str, object] | None) -> str | None:
    if artifact is None:
        return None
    value = artifact.get("artifactId")
    return value if isinstance(value, str) else None


def _append_bounded_artifacts(
    artifacts: tuple[dict[str, object], ...],
    additions: tuple[dict[str, object], ...],
) -> tuple[dict[str, object], ...]:
    result = list(artifacts)
    existing_ids = {_artifact_id(artifact) for artifact in artifacts}
    encoded_bytes = len(json.dumps(result, ensure_ascii=False).encode("utf-8"))
    for artifact in additions:
        addition_bytes = len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) + 1
        if len(result) >= 8 or _artifact_id(artifact) in existing_ids:
            break
        if encoded_bytes + addition_bytes > 65_536:
            continue
        result.append(artifact)
        encoded_bytes += addition_bytes
        existing_ids.add(_artifact_id(artifact))
    return tuple(result)


def _report_kind(capability: str) -> str:
    return {
        "recommendation": "RECOMMENDATION",
        "comparison": "COMPARISON",
        "recent_trade_lookup": "RECENT_TRADE",
        "price_trend": "PRICE_TREND",
        "complex_identity": "PROPERTY_OVERVIEW",
    }.get(capability, "GENERAL")


def _krw(amount_ten_thousand_krw: int) -> str:
    won = amount_ten_thousand_krw * 10_000
    eok, remainder = divmod(won, 100_000_000)
    man = remainder // 10_000
    if eok and man:
        return f"{eok:,}억 {man:,}만원"
    if eok:
        return f"{eok:,}억원"
    return f"{man:,}만원"

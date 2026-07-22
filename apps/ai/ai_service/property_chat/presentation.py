from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Literal

from .models import EvidenceFact, QueryPlan

_MAX_DISPLAY_LENGTH = 2_000
_MAX_LABEL_LENGTH = 100
_MAX_ARTIFACT_BYTES = 65_536


@dataclass(frozen=True)
class GroundedPresentationText:
    text: str
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if not 1 <= len(self.text.strip()) <= _MAX_DISPLAY_LENGTH:
            raise ValueError("presentation text is outside the public contract")
        if not self.fact_ids or len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("presentation text requires unique fact ids")

    def to_public_dict(self) -> dict[str, object]:
        return {"text": self.text.strip(), "factIds": list(self.fact_ids)}


ScopeNotice = GroundedPresentationText


@dataclass(frozen=True)
class AppliedCriterion:
    key: str
    label: str
    value: str
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            not _is_identifier(self.key)
            or not 1 <= len(self.label.strip()) <= _MAX_LABEL_LENGTH
            or not 1 <= len(self.value.strip()) <= _MAX_DISPLAY_LENGTH
            or not self.fact_ids
            or len(self.fact_ids) != len(set(self.fact_ids))
        ):
            raise ValueError("applied criterion is outside the public contract")

    def to_public_dict(self) -> dict[str, object]:
        return {
            "key": self.key,
            "label": self.label.strip(),
            "value": self.value.strip(),
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class Interpretation:
    key: str
    label: str
    text: str
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            not _is_identifier(self.key)
            or not 1 <= len(self.label.strip()) <= _MAX_LABEL_LENGTH
            or not 1 <= len(self.text.strip()) <= _MAX_DISPLAY_LENGTH
            or not self.fact_ids
            or len(self.fact_ids) != len(set(self.fact_ids))
        ):
            raise ValueError("interpretation is outside the public contract")

    def to_public_dict(self) -> dict[str, object]:
        return {
            "key": self.key,
            "label": self.label.strip(),
            "text": self.text.strip(),
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class FollowUpPrompt:
    text: str

    def __post_init__(self) -> None:
        if not 1 <= len(self.text.strip()) <= _MAX_DISPLAY_LENGTH:
            raise ValueError("follow-up prompt is outside the public contract")


@dataclass(frozen=True)
class FragmentPresentation:
    fragment_id: str
    capability: str
    status: Literal["success", "failed"]
    headline: str
    fact_ids: tuple[str, ...]

    def to_public_dict(self) -> dict[str, object]:
        if (
            not _is_identifier(self.fragment_id)
            or not _is_identifier(self.capability)
            or self.status not in {"success", "failed"}
            or not 1 <= len(self.headline.strip()) <= _MAX_DISPLAY_LENGTH
            or len(self.fact_ids) != len(set(self.fact_ids))
        ):
            raise ValueError("fragment presentation is outside the public contract")
        return {
            "fragmentId": self.fragment_id,
            "capability": self.capability,
            "status": self.status,
            "headline": self.headline.strip(),
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class AnswerPresentation:
    headline: GroundedPresentationText
    scope_notice: ScopeNotice | None = None
    criteria: tuple[AppliedCriterion, ...] = ()
    interpretations: tuple[Interpretation, ...] = ()
    follow_up: FollowUpPrompt | None = None
    fragment_summaries: tuple[FragmentPresentation, ...] = ()

    def to_public_dict(self, allowed_fact_ids: set[str]) -> dict[str, object]:
        if (
            len(self.criteria) > 8
            or len(self.interpretations) > 4
            or len(self.fragment_summaries) > 4
        ):
            raise ValueError("presentation collection exceeds the public contract")
        referenced = {
            *self.headline.fact_ids,
            *(self.scope_notice.fact_ids if self.scope_notice else ()),
            *(fact_id for item in self.criteria for fact_id in item.fact_ids),
            *(fact_id for item in self.interpretations for fact_id in item.fact_ids),
            *(fact_id for item in self.fragment_summaries for fact_id in item.fact_ids),
        }
        if not referenced.issubset(allowed_fact_ids):
            raise ValueError("presentation references an unknown fact")
        return {
            "version": 1,
            "scopeNotice": (
                self.scope_notice.to_public_dict() if self.scope_notice else None
            ),
            "headline": self.headline.to_public_dict(),
            "criteria": [item.to_public_dict() for item in self.criteria],
            "interpretations": [
                item.to_public_dict() for item in self.interpretations
            ],
            "followUp": self.follow_up.text.strip() if self.follow_up else None,
            "fragmentSummaries": [
                item.to_public_dict() for item in self.fragment_summaries
            ],
        }


@dataclass(frozen=True)
class PresentationBasis:
    resolved_scope: str | None = None
    applied_criteria: tuple[AppliedCriterion, ...] = ()
    result_roles: tuple[str, ...] = ()
    interpretation_facts: tuple[str, ...] = ()
    follow_up_options: tuple[str, ...] = ()


class PresentationAssembler:
    def present(
        self,
        *,
        plan: QueryPlan,
        used_facts: list[EvidenceFact],
        readiness: str,
        artifacts: list[dict[str, object]],
    ) -> tuple[AnswerPresentation | None, list[dict[str, object]]]:
        if readiness == "unavailable" or not used_facts:
            return None, artifacts
        fact_ids = tuple(fact.fact_id for fact in used_facts)
        if plan.capability == "complex_identity":
            return self._identity(plan, used_facts), artifacts
        if plan.capability == "recent_trade_lookup":
            artifact = _trade_table(used_facts, plan)
            return self._trade(plan, used_facts), [*artifacts, *([artifact] if artifact else [])]
        if plan.capability == "price_trend":
            artifact = _trend_table(used_facts, plan)
            return self._trend(plan, used_facts), [*artifacts, *([artifact] if artifact else [])]
        if plan.capability == "comparison":
            return self._comparison(plan, used_facts, artifacts), artifacts
        if plan.capability == "recommendation":
            if any(item.get("type") == "recommendationTable" for item in artifacts):
                return self._criteria_recommendation(plan, used_facts, artifacts), artifacts
            return self._recommendation(plan, used_facts), artifacts
        presentation = AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지를 기준으로 확인했습니다.", fact_ids
            ),
            headline=GroundedPresentationText(
                "요청한 범위에서 확인된 정보를 정리했습니다.", fact_ids
            ),
            criteria=_reference_criteria(plan, fact_ids),
            interpretations=(),
            follow_up=FollowUpPrompt("반경이나 시설 조건을 바꿔 다시 확인할 수 있습니다."),
        )
        reference_artifact = _reference_fact_list(plan, used_facts)
        return presentation, [*artifacts, *([reference_artifact] if reference_artifact else [])]

    @staticmethod
    def _identity(plan: QueryPlan, facts: list[EvidenceFact]) -> AnswerPresentation:
        fact = facts[0]
        display_name = fact.payload.get("displayName")
        if not isinstance(display_name, str) or not display_name.strip():
            raise ValueError("identity presentation requires a display name")
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지를 기준으로 확인했습니다.",
                (fact.fact_id,),
            ),
            headline=GroundedPresentationText(
                f"{display_name}의 확인된 단지 정보를 정리했습니다.",
                (fact.fact_id,),
            ),
            follow_up=FollowUpPrompt(
                "최근 실거래, 가격 흐름 또는 주변 시설을 이어서 확인할 수 있습니다."
            ),
        )

    @staticmethod
    def _trade(plan: QueryPlan, facts: list[EvidenceFact]) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지의 실거래를 기준으로 확인했습니다.", ids
            ),
            headline=GroundedPresentationText(
                f"요청한 조건에서 최근 실거래 {len(facts)}건을 확인했습니다.", ids
            ),
            criteria=_trade_criteria(plan, ids),
            interpretations=(Interpretation(
                "OBSERVED_TRADES",
                "거래 표본",
                "표에 표시된 거래는 현재 데이터 기준으로 확인된 신고 거래입니다.",
                ids,
            ),),
            follow_up=FollowUpPrompt("기간이나 전용면적을 바꿔 다시 확인할 수 있습니다."),
        )

    @staticmethod
    def _trend(plan: QueryPlan, facts: list[EvidenceFact]) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지의 월별 거래 관찰값을 기준으로 확인했습니다.", ids
            ),
            headline=GroundedPresentationText(
                f"요청한 기간에서 월별 관찰값 {len(facts)}개를 정리했습니다.", ids
            ),
            criteria=_trade_criteria(plan, ids),
            interpretations=(Interpretation(
                "OBSERVED_TREND",
                "가격 흐름",
                "월별 평균·최솟값·최댓값과 거래 수를 과거 관찰값으로 비교할 수 있습니다.",
                ids,
            ),),
            follow_up=FollowUpPrompt("기간이나 전용면적을 바꿔 과거 관찰값을 다시 확인할 수 있습니다."),
        )

    @staticmethod
    def _comparison(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        artifacts: list[dict[str, object]],
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        count = len(plan.complex_names)
        interpretations: list[Interpretation] = []
        table = next((item for item in artifacts if item.get("type") == "comparisonTable"), None)
        if table is not None:
            for row in table.get("rows", [])[:3]:  # type: ignore[union-attr]
                if not isinstance(row, dict):
                    continue
                cells = row.get("cells")
                label = row.get("label")
                if not isinstance(cells, list) or not isinstance(label, str):
                    continue
                available = [cell for cell in cells if isinstance(cell, dict) and cell.get("availability") == "available"]
                row_ids = tuple(dict.fromkeys(
                    fact_id
                    for cell in available
                    for fact_id in cell.get("factIds", [])
                    if isinstance(fact_id, str)
                ))
                if len(available) >= 2 and row_ids:
                    values = ", ".join(str(cell.get("value")) for cell in available)
                    interpretations.append(Interpretation(
                        f"COMPARISON_{row.get('key', len(interpretations))}",
                        label,
                        f"{label}은 표에서 {values}로 확인됩니다. 중요하게 보는 조건에 따라 선택이 달라질 수 있습니다.",
                        row_ids,
                    ))
        return AnswerPresentation(
            headline=GroundedPresentationText(
                f"동일 기준으로 {count}개 단지의 항목별 차이를 정리했습니다.", ids
            ),
            criteria=_trade_criteria(plan, ids),
            interpretations=tuple(interpretations[:3]),
            follow_up=FollowUpPrompt("가격·교통·시설 중 중요하게 보는 조건을 알려주면 해당 항목부터 설명할 수 있습니다."),
        )

    @staticmethod
    def _recommendation(plan: QueryPlan, facts: list[EvidenceFact]) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.region_name}’ 지역과 사용자가 지정한 조건을 기준으로 확인했습니다.", ids
            ) if plan.region_name else None,
            headline=GroundedPresentationText(
                "최근 거래와 확인 가능한 생활 조건으로 후보를 정리했습니다.", ids
            ),
            criteria=_recommendation_criteria(plan, ids),
            interpretations=(Interpretation(
                "CONDITION_FIT",
                "비교 기준",
                "최근 거래와 단지·생활 인프라의 확인 가능한 수치를 함께 비교했습니다.",
                ids,
            ),),
            follow_up=FollowUpPrompt(
                "상위 후보의 최근 실거래나 교육·교통 차이를 이어서 비교할 수 있어요."
            ),
        )

    @staticmethod
    def _criteria_recommendation(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        artifacts: list[dict[str, object]],
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        scope_fact = next(
            (fact for fact in facts if fact.fact_id.startswith("criteria-scope-")),
            facts[0],
        )
        scope_label = scope_fact.payload.get("scopeLabel")
        if not isinstance(scope_label, str) or not scope_label.strip():
            raise ValueError("criteria recommendation scope is missing")
        table = next(
            (item for item in artifacts if item.get("type") == "recommendationTable"),
            None,
        )
        rows = table.get("rows", []) if isinstance(table, dict) else []
        row_count = len(rows) if isinstance(rows, list) else 0
        headline = (
            f"요청한 조건을 적용한 후보 {row_count}곳을 정리했습니다."
            if row_count > 1
            else "요청한 조건에서 확인된 후보를 정리했습니다."
        )
        if plan.recommendation_mode == "BUDGET":
            criteria = list(_recommendation_criteria(plan, ids))
        else:
            criteria = [AppliedCriterion(
                "STATION_SCOPE" if plan.station_name else "REGION",
                "역 주변 범위" if plan.station_name else "지역",
                scope_label,
                (scope_fact.fact_id,),
            )]
            if plan.minimum_unit_count is not None:
                criteria.append(AppliedCriterion(
                    "MIN_UNIT_COUNT", "최소 세대수",
                    f"{plan.minimum_unit_count:,}세대 이상", (scope_fact.fact_id,),
                ))
            area_facts = tuple(
                fact.fact_id for fact in facts
                if fact.fact_id.startswith("criteria-trade-basis-")
            )
            if plan.exclusive_area_square_meters is not None and area_facts:
                criteria.append(AppliedCriterion(
                    "EXCLUSIVE_AREA", "거래 면적",
                    f"전용면적 {plan.exclusive_area_square_meters:g}㎡ ±1.0㎡ · 최근 거래 3건",
                    area_facts,
                ))
        criteria.extend(_criteria_metric_presentations(plan, facts))
        interpretations: list[Interpretation] = []
        if isinstance(rows, list):
            for index, row in enumerate(rows[:2], start=1):
                if not isinstance(row, dict):
                    continue
                name = row.get("complexName")
                metrics = row.get("metrics")
                if not isinstance(name, str) or not isinstance(metrics, dict):
                    continue
                available = [
                    (key, metric)
                    for key in (*plan.criteria_order, *plan.recommendation_criteria)
                    if isinstance((metric := metrics.get(key)), dict)
                    and metric.get("availability") == "available"
                ]
                unique_available = list(dict.fromkeys(key for key, _ in available))
                if not unique_available:
                    continue
                key = unique_available[min(index - 1, len(unique_available) - 1)]
                metric = metrics[key]
                fact_ids = metric.get("factIds")
                grounded_ids = tuple(
                    fact_id for fact_id in fact_ids if isinstance(fact_id, str)
                ) if isinstance(fact_ids, list) else ()
                if not grounded_ids:
                    continue
                label, text = _criteria_candidate_interpretation(
                    name, key, metric, plan.radius_meters
                )
                interpretations.append(Interpretation(
                    f"CONDITION_FIT_{index}", label, text, grounded_ids,
                ))
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{scope_label}’ 기준으로 해석했습니다.", (scope_fact.fact_id,)
            ),
            headline=GroundedPresentationText(headline, ids),
            criteria=tuple(criteria[:8]),
            interpretations=tuple(interpretations),
            follow_up=FollowUpPrompt(
                "상위 후보의 최근 실거래를 보거나 교육·교통 기준으로 다시 정렬할 수 있어요."
            ),
        )


def _criteria_candidate_interpretation(
    name: str,
    key: str,
    metric: dict[str, object],
    radius_meters: int | None,
) -> tuple[str, str]:
    value = metric.get("value")
    nearest = metric.get("nearestDistanceMeters")
    if key == "ACADEMY" and isinstance(value, int):
        radius = (
            f"반경 {radius_meters:,}m 안에서 "
            if radius_meters is not None
            else "주변에서 "
        )
        detail = (
            f"{radius}학원 위치 {value:,}곳, 최근접 학원 직선거리 {nearest:,}m로 확인했습니다."
            if isinstance(nearest, int)
            else f"{radius}학원 위치 {value:,}곳을 확인했습니다."
        )
        return (
            "학원 접근성",
            f"{name}: {detail}",
        )
    labels = {
        "SCHOOL": "학교 위치",
        "TRANSIT": "철도역 접근성",
        "SHOPPING": "생활 인프라",
    }
    label = labels.get(key, "확인 기준")
    if isinstance(value, int):
        return label, f"{name}: {label} 기준 직선거리 {value:,}m로 확인됐습니다."
    return label, f"{name}: {label} 관찰값이 확인된 후보입니다."


def _trade_criteria(plan: QueryPlan, fact_ids: tuple[str, ...]) -> tuple[AppliedCriterion, ...]:
    criteria: list[AppliedCriterion] = []
    if plan.start_date is not None and plan.end_date is not None:
        criteria.append(AppliedCriterion(
            "DATE_RANGE", "조회 기간",
            f"{plan.start_date.isoformat()} ~ {plan.end_date.isoformat()}", fact_ids,
        ))
    elif plan.end_date is not None:
        criteria.append(AppliedCriterion(
            "END_DATE", "기준 종료일", plan.end_date.isoformat(), fact_ids,
        ))
    if plan.exclusive_area_square_meters is not None:
        criteria.append(AppliedCriterion(
            "EXCLUSIVE_AREA", "전용면적",
            f"{plan.exclusive_area_square_meters:g}㎡ ±1.0㎡", fact_ids,
        ))
    criteria.append(AppliedCriterion("RESULT_LIMIT", "결과 수", f"최대 {plan.limit}건", fact_ids))
    return tuple(criteria)


def _reference_criteria(plan: QueryPlan, fact_ids: tuple[str, ...]) -> tuple[AppliedCriterion, ...]:
    if plan.radius_meters is None:
        return ()
    return (AppliedCriterion(
        "RADIUS", "관찰 반경", f"단지 중심 직선거리 {plan.radius_meters}m", fact_ids,
    ),)


def _recommendation_criteria(
    plan: QueryPlan, fact_ids: tuple[str, ...]
) -> tuple[AppliedCriterion, ...]:
    criteria: list[AppliedCriterion] = []
    if plan.region_name:
        criteria.append(AppliedCriterion("REGION", "지역", plan.region_name, fact_ids))
    if plan.maximum_budget_ten_thousand_krw is not None:
        criteria.append(AppliedCriterion(
            "MAX_BUDGET", "최대 예산",
            f"{plan.maximum_budget_ten_thousand_krw:,}만원 이하", fact_ids,
        ))
    if plan.exclusive_area_square_meters is not None:
        criteria.append(AppliedCriterion(
            "EXCLUSIVE_AREA", "전용면적",
            f"{plan.exclusive_area_square_meters:g}㎡ ±1.0㎡", fact_ids,
        ))
    if plan.minimum_unit_count is not None:
        criteria.append(AppliedCriterion(
            "MIN_UNIT_COUNT", "최소 세대수",
            f"{plan.minimum_unit_count:,}세대 이상", fact_ids,
        ))
    return tuple(criteria[:8])


def _criteria_metric_presentations(
    plan: QueryPlan,
    facts: list[EvidenceFact],
) -> list[AppliedCriterion]:
    definitions = {
        "ACADEMY": ("학원 접근성", "단지 중심 800m 내 학원 위치"),
        "SCHOOL": ("학교 위치 접근성", "단지 중심 직선거리 1,500m 내 운영 학교"),
        "TRANSIT": ("철도 접근성", "단지 중심 직선거리 1,500m 내 철도역"),
        "SHOPPING": ("대규모점포 접근성", "단지 중심 직선거리 1,000m 내 대규모점포"),
    }
    result: list[AppliedCriterion] = []
    for key in plan.recommendation_criteria:
        fact_ids = tuple(
            fact.fact_id
            for fact in facts
            if fact.payload.get("criterion") == key
        )
        if not fact_ids:
            continue
        label, value = definitions[key]
        result.append(AppliedCriterion(key, label, value, fact_ids))
    return result


def _trade_table(facts: list[EvidenceFact], plan: QueryPlan) -> dict[str, object] | None:
    rows = []
    for fact in facts[:10]:
        payload = fact.payload
        required = (
            payload.get("tradeId"), payload.get("dealDate"),
            payload.get("exclusiveAreaSquareMeters"),
            payload.get("dealAmountTenThousandKrw"),
        )
        if (
            not isinstance(required[0], int)
            or not isinstance(required[1], str)
            or not isinstance(required[2], int | float)
            or not isinstance(required[3], int)
        ):
            return None
        rows.append({
            "tradeId": required[0], "dealDate": required[1],
            "exclusiveAreaSquareMeters": required[2],
            "amountTenThousandKrw": required[3], "floor": payload.get("floor"),
            "factIds": [fact.fact_id],
        })
    if not rows:
        return None
    artifact = {
        "type": "tradeTable", "version": 1,
        "artifactId": f"trade-table-{rows[0]['tradeId']}-{len(rows)}",
        "title": "최근 실거래", "amountUnit": "10_000_KRW", "rows": rows,
    }
    return _bounded_artifact(artifact)


def _trend_table(facts: list[EvidenceFact], plan: QueryPlan) -> dict[str, object] | None:
    del plan
    rows = []
    for fact in facts[:24]:
        payload = fact.payload
        if not all(isinstance(payload.get(key), int) for key in (
            "averageAmountTenThousandKrw", "tradeCount",
            "minimumAmountTenThousandKrw", "maximumAmountTenThousandKrw",
        )) or not isinstance(payload.get("month"), str):
            return None
        rows.append({
            "month": payload["month"],
            "averageAmountTenThousandKrw": payload["averageAmountTenThousandKrw"],
            "minimumAmountTenThousandKrw": payload["minimumAmountTenThousandKrw"],
            "maximumAmountTenThousandKrw": payload["maximumAmountTenThousandKrw"],
            "tradeCount": payload["tradeCount"], "availability": "available",
            "reason": None, "factIds": [fact.fact_id],
        })
    if not rows:
        return None
    artifact = {
        "type": "trendTable", "version": 1,
        "artifactId": f"trend-table-{rows[0]['month']}-{len(rows)}",
        "title": "월별 가격 관찰값", "amountUnit": "10_000_KRW", "rows": rows,
    }
    return _bounded_artifact(artifact)


def _reference_fact_list(
    plan: QueryPlan, facts: list[EvidenceFact]
) -> dict[str, object] | None:
    items: list[dict[str, object]] = []
    name_keys = ("schoolName", "facilityName", "academyName", "stationName", "centerName")
    for fact in facts:
        name = next(
            (fact.payload.get(key) for key in name_keys if isinstance(fact.payload.get(key), str)),
            None,
        )
        if (
            not isinstance(name, str)
            or not name.strip()
            or len(name.strip()) > _MAX_LABEL_LENGTH
        ):
            continue
        details: list[str] = []
        distance = fact.payload.get("distanceMeters")
        if isinstance(distance, int) and not isinstance(distance, bool):
            details.append(f"직선거리 {distance}m")
        lines = fact.payload.get("lines")
        if isinstance(lines, list) and all(isinstance(line, str) for line in lines):
            details.append("·".join(lines))
        level = fact.payload.get("schoolLevel")
        if isinstance(level, str):
            details.append({
                "ELEMENTARY": "초등학교", "MIDDLE": "중학교", "HIGH": "고등학교",
            }.get(level, level))
        items.append({
            "label": name.strip(),
            "value": " · ".join(details) if details else "확인된 정보",
            "factIds": [fact.fact_id],
        })
        if len(items) == 10:
            break
    if not items:
        return None
    return _bounded_artifact({
        "type": "factList", "version": 1,
        "artifactId": f"fact-list-{plan.capability}-{facts[0].fact_id}"[:200],
        "title": "확인된 시설 정보", "items": items,
    })


def _bounded_artifact(artifact: dict[str, object]) -> dict[str, object]:
    if len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) > _MAX_ARTIFACT_BYTES:
        raise ValueError("artifact exceeds the public size limit")
    return artifact


def _is_identifier(value: str) -> bool:
    return bool(value) and len(value) <= 200 and all(
        character.isalnum() or character in "._:-" for character in value
    )

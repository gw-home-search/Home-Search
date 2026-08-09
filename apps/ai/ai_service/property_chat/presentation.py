from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
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


@dataclass(frozen=True)
class ResolvedAnswerContext:
    """Verified values used to build the immutable first answer paragraph."""

    capability: str
    subject_name: str | None
    region_name: str | None
    address: str | None
    start_date: date | None
    end_date: date | None
    exclusive_area_square_meters: float | None
    radius_meters: int | None
    result_facts: tuple[EvidenceFact, ...]
    subject_fact_ids: tuple[str, ...]


class AnswerLeadBuilder:
    """Build a direct, grounded lead that the model is not allowed to rewrite."""

    def build(
        self,
        *,
        plan: QueryPlan,
        facts: list[EvidenceFact],
        artifacts: list[dict[str, object]],
    ) -> GroundedPresentationText:
        context = self.resolve(plan=plan, facts=facts)
        fact_ids = tuple(dict.fromkeys(fact.fact_id for fact in facts))
        if not fact_ids:
            raise ValueError("answer lead requires verified facts")
        text = self._text(context, artifacts, plan)
        return GroundedPresentationText(text, fact_ids)

    @staticmethod
    def resolve(*, plan: QueryPlan, facts: list[EvidenceFact]) -> ResolvedAnswerContext:
        subject = next(
            (fact for fact in facts if fact.fact_id.startswith("property-complex-")),
            None,
        )
        payload = subject.payload if subject is not None else {}
        result_facts = tuple(
            fact for fact in facts if fact is not subject
            and not fact.fact_id.startswith("candidate-observation-")
        )
        return ResolvedAnswerContext(
            capability=plan.capability,
            subject_name=_optional_text(payload.get("displayName")) or plan.complex_name,
            region_name=_optional_text(payload.get("regionName")) or plan.region_name,
            address=_optional_text(payload.get("address")),
            start_date=plan.start_date,
            end_date=plan.end_date,
            exclusive_area_square_meters=plan.exclusive_area_square_meters,
            radius_meters=plan.radius_meters,
            result_facts=result_facts,
            subject_fact_ids=((subject.fact_id,) if subject is not None else ()),
        )

    def _text(
        self,
        context: ResolvedAnswerContext,
        artifacts: list[dict[str, object]],
        plan: QueryPlan,
    ) -> str:
        name = context.subject_name or "요청한 단지"
        if context.capability == "complex_identity":
            location = context.address or context.region_name
            return (
                f"{name}{_topic_particle(name)} {location}에 있습니다."
                if location else f"{name}의 검증된 위치 정보는 현재 확인할 수 없습니다."
            )
        if context.capability == "recent_trade_lookup":
            trades = [
                fact for fact in context.result_facts
                if fact.fact_id.startswith("property-trade-")
            ]
            condition = _condition_text(context)
            if not trades:
                return f"{name}의 {condition}에서는 실거래가 0건으로 확인됐습니다."
            latest = trades[0]
            payload = latest.payload
            date_text = payload.get("dealDate")
            area = payload.get("exclusiveAreaSquareMeters")
            amount = payload.get("dealAmountTenThousandKrw")
            floor = payload.get("floor")
            details = []
            if isinstance(date_text, str):
                details.append(date_text)
            if isinstance(area, int | float) and not isinstance(area, bool):
                details.append(f"전용 {area:g}㎡")
            if isinstance(amount, int) and not isinstance(amount, bool):
                details.append(_krw(amount))
            if isinstance(floor, int) and not isinstance(floor, bool):
                details.append(f"{floor}층")
            latest_text = (
                f" 가장 최근 거래는 {', '.join(details)}입니다." if details else ""
            )
            return (
                f"{name}의 {condition} 실거래 {len(trades)}건을 확인했습니다."
                f"{latest_text}"
            )
        if context.capability == "price_trend":
            trends = [
                fact for fact in context.result_facts
                if fact.fact_id.startswith("property-trend-")
            ]
            condition = _condition_text(context)
            if not trends:
                return f"{name}의 {condition}에서는 월별 가격 관찰값이 0건으로 확인됐습니다."
            total = sum(
                int(fact.payload["tradeCount"])
                for fact in trends
                if isinstance(fact.payload.get("tradeCount"), int)
                and not isinstance(fact.payload.get("tradeCount"), bool)
            )
            latest = trends[-1].payload.get("averageAmountTenThousandKrw")
            latest_text = _krw(latest) if isinstance(latest, int) else "확인 불가"
            return (
                f"{name}의 {condition} 월별 관찰값은 {len(trends)}개월·총 {total}건입니다. "
                f"최근 관찰월 평균은 {latest_text}입니다."
            )
        if context.capability == "academy_lookup":
            return _facility_lead(context, "학원 위치", "facilityName")
        if context.capability == "rail_station_lookup":
            return _rail_lead(context)
        if context.capability == "school_location":
            return _facility_lead(context, "운영 학교", "schoolName")
        if context.capability == "retail_location":
            return _facility_lead(context, "대규모점포", "facilityName")
        if context.capability == "childcare_lookup":
            return _facility_lead(context, "어린이집", "centerName")
        if context.capability == "comparison":
            table = next(
                (item for item in artifacts if item.get("type") == "comparisonTable"),
                None,
            )
            columns = table.get("columns") if isinstance(table, dict) else None
            rows = table.get("rows") if isinstance(table, dict) else None
            names = [
                column.get("label") for column in columns
                if isinstance(column, dict) and isinstance(column.get("label"), str)
            ] if isinstance(columns, list) else list(plan.complex_names)
            difference = None
            if isinstance(rows, list):
                for row in rows:
                    if not isinstance(row, dict) or not isinstance(row.get("label"), str):
                        continue
                    cells = row.get("cells")
                    available = [
                        cell for cell in cells
                        if isinstance(cell, dict) and cell.get("availability") == "available"
                    ] if isinstance(cells, list) else []
                    if len(available) >= 2 and available[0].get("value") != available[1].get("value"):
                        difference = (
                            f"{row['label']}{_topic_particle(row['label'])} "
                            f"{names[0]} {available[0].get('value')}, "
                            f"{names[1]} {available[1].get('value')}"
                        )
                        break
            conditions = _condition_text(context)
            pair = "와 ".join(str(item) for item in names[:2]) or name
            return (
                f"{conditions}에서 {pair}를 비교하면 {difference}입니다."
                if difference else f"{conditions}에서 {pair}의 검증 가능한 항목을 비교했습니다."
            )
        if context.capability == "recommendation":
            table = next(
                (item for item in artifacts if item.get("type") in {
                    "recommendationTable", "recommendationCards",
                }),
                None,
            )
            rows = (
                table.get("rows")
                if isinstance(table, dict) and table.get("type") == "recommendationTable"
                else table.get("cards") if isinstance(table, dict) else None
            )
            first = rows[0] if isinstance(rows, list) and rows else None
            first_name = first.get("complexName") if isinstance(first, dict) else None
            count = len(rows) if isinstance(rows, list) else 0
            scope = context.region_name or name
            return (
                f"{scope} 조건을 적용해 {count}곳을 확인했으며 먼저 볼 곳은 {first_name}입니다."
                if isinstance(first_name, str) and count
                else f"{scope} 조건에서 검증된 후보를 확인했습니다."
            )
        return f"{name}의 요청 조건에서 검증된 정보를 확인했습니다."


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
        lead = AnswerLeadBuilder().build(
            plan=plan, facts=used_facts, artifacts=artifacts,
        )
        if plan.capability == "complex_identity":
            return self._identity(plan, used_facts, lead), artifacts
        if plan.capability == "recent_trade_lookup":
            artifact = _trade_table([
                fact for fact in used_facts
                if fact.fact_id.startswith("property-trade-")
            ], plan)
            return self._trade(plan, used_facts, lead), [*artifacts, *([artifact] if artifact else [])]
        if plan.capability == "price_trend":
            artifact = _trend_table([
                fact for fact in used_facts
                if fact.fact_id.startswith("property-trend-")
            ], plan)
            return self._trend(plan, used_facts, lead), [*artifacts, *([artifact] if artifact else [])]
        if plan.capability == "comparison":
            return self._comparison(plan, used_facts, artifacts, lead), artifacts
        if plan.capability == "recommendation":
            if any(item.get("type") == "recommendationTable" for item in artifacts):
                return self._criteria_recommendation(
                    plan, used_facts, artifacts, lead,
                ), artifacts
            return self._recommendation(plan, used_facts, lead), artifacts
        presentation = AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지를 기준으로 확인했습니다.", fact_ids
            ),
            headline=lead,
            criteria=_reference_criteria(plan, fact_ids),
            interpretations=(),
            follow_up=_follow_up(plan, used_facts),
        )
        reference_artifact = _reference_fact_list(plan, used_facts)
        return presentation, [*artifacts, *([reference_artifact] if reference_artifact else [])]

    @staticmethod
    def _identity(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        lead: GroundedPresentationText,
    ) -> AnswerPresentation:
        fact = facts[0]
        display_name = fact.payload.get("displayName")
        if not isinstance(display_name, str) or not display_name.strip():
            raise ValueError("identity presentation requires a display name")
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지를 기준으로 확인했습니다.",
                (fact.fact_id,),
            ),
            headline=lead,
            follow_up=_follow_up(plan, facts),
        )

    @staticmethod
    def _trade(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        lead: GroundedPresentationText,
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지의 실거래를 기준으로 확인했습니다.", ids
            ),
            headline=lead,
            criteria=_trade_criteria(plan, ids),
            interpretations=(Interpretation(
                "OBSERVED_TRADES",
                "거래 표본",
                "표에 표시된 거래는 현재 데이터 기준으로 확인된 신고 거래입니다.",
                ids,
            ),),
            follow_up=_follow_up(plan, facts),
        )

    @staticmethod
    def _trend(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        lead: GroundedPresentationText,
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.complex_name}’ 단지의 월별 거래 관찰값을 기준으로 확인했습니다.", ids
            ),
            headline=lead,
            criteria=_trade_criteria(plan, ids),
            interpretations=(Interpretation(
                "OBSERVED_TREND",
                "가격 흐름",
                "월별 평균·최솟값·최댓값과 거래 수를 과거 관찰값으로 비교할 수 있습니다.",
                ids,
            ),),
            follow_up=_follow_up(plan, facts),
        )

    @staticmethod
    def _comparison(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        artifacts: list[dict[str, object]],
        lead: GroundedPresentationText,
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
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
            headline=lead,
            criteria=_trade_criteria(plan, ids),
            interpretations=tuple(interpretations[:3]),
            follow_up=_follow_up(plan, facts),
        )

    @staticmethod
    def _recommendation(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        lead: GroundedPresentationText,
    ) -> AnswerPresentation:
        ids = tuple(fact.fact_id for fact in facts)
        return AnswerPresentation(
            scope_notice=ScopeNotice(
                f"‘{plan.region_name}’ 지역과 사용자가 지정한 조건을 기준으로 확인했습니다.", ids
            ) if plan.region_name else None,
            headline=lead,
            criteria=_recommendation_criteria(plan, ids),
            interpretations=(Interpretation(
                "CONDITION_FIT",
                "비교 기준",
                "최근 거래와 단지·생활 인프라의 확인 가능한 수치를 함께 비교했습니다.",
                ids,
            ),),
            follow_up=_follow_up(plan, facts),
        )

    @staticmethod
    def _criteria_recommendation(
        plan: QueryPlan,
        facts: list[EvidenceFact],
        artifacts: list[dict[str, object]],
        lead: GroundedPresentationText,
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
            headline=lead,
            criteria=tuple(criteria[:8]),
            interpretations=tuple(interpretations),
            follow_up=_follow_up(plan, facts),
        )


def _follow_up(plan: QueryPlan, facts: list[EvidenceFact]) -> FollowUpPrompt:
    subject = next(
        (
            fact.payload.get("displayName")
            for fact in facts
            if fact.fact_id.startswith("property-complex-")
            and isinstance(fact.payload.get("displayName"), str)
        ),
        None,
    )
    name = subject or plan.complex_name or plan.region_name or "요청한 조건"
    area = (
        f" 전용 {plan.exclusive_area_square_meters:g}㎡"
        if plan.exclusive_area_square_meters is not None else ""
    )
    by_capability = {
        "complex_identity": (
            f"{name}{area} 최근 실거래 5건을 알려줘",
            f"{name}{area} 최근 1년 가격 흐름과 거래량을 보여줘",
            f"{name} 주변 학원 위치와 가까운 역·노선을 알려줘",
        ),
        "recent_trade_lookup": (
            f"{name}{area} 최근 1년 가격 흐름과 거래량을 보여줘",
            f"{name} 위치와 세대수·사용승인일을 알려줘",
            f"{name} 주변 학원 위치와 가까운 역·노선을 알려줘",
        ),
        "price_trend": (
            f"{name}{area} 최근 실거래 5건을 알려줘",
            f"{name} 위치와 세대수·사용승인일을 알려줘",
        ),
        "comparison": (
            "두 단지의 최근 실거래를 같은 면적으로 비교해줘",
            "두 단지의 학원과 역 접근성을 비교해줘",
        ),
        "recommendation": (
            "첫 번째 후보의 최근 실거래를 알려줘",
            "상위 후보의 학원과 역 접근성을 비교해줘",
        ),
    }
    prompts = by_capability.get(plan.capability, (
        f"{name} 주변 반경을 넓혀 다시 확인해줘",
        f"{name} 위치와 세대수·사용승인일을 알려줘",
    ))
    return FollowUpPrompt(" · ".join(dict.fromkeys(prompts)))


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


def _optional_text(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _topic_particle(value: str) -> str:
    last = value[-1]
    code = ord(last)
    return "은" if 0xAC00 <= code <= 0xD7A3 and (code - 0xAC00) % 28 else "는"


def _subject_particle(value: str) -> str:
    last = value[-1]
    code = ord(last)
    return "이" if 0xAC00 <= code <= 0xD7A3 and (code - 0xAC00) % 28 else "가"


def _condition_text(context: ResolvedAnswerContext) -> str:
    parts: list[str] = []
    if context.start_date is not None and context.end_date is not None:
        parts.append(f"{context.start_date.isoformat()}~{context.end_date.isoformat()}")
    if context.exclusive_area_square_meters is not None:
        parts.append(f"전용 {context.exclusive_area_square_meters:g}㎡")
    return "·".join(parts) if parts else "요청 조건"


def _krw(amount_ten_thousand_krw: int) -> str:
    eok, manwon = divmod(amount_ten_thousand_krw, 10_000)
    if eok and manwon:
        return f"{eok:,}억 {manwon:,}만원"
    if eok:
        return f"{eok:,}억원"
    return f"{manwon:,}만원"


def _facility_lead(
    context: ResolvedAnswerContext,
    facility_label: str,
    name_key: str,
) -> str:
    name = context.subject_name or "요청한 단지"
    facilities = [
        fact for fact in context.result_facts
        if isinstance(fact.payload.get(name_key), str)
        and isinstance(fact.payload.get("distanceMeters"), int)
    ]
    radius = (
        f" 중심 {context.radius_meters:,}m에서"
        if context.radius_meters else " 주변에서"
    )
    if not facilities:
        verified_zero = any(
            fact.payload.get("verifiedZero") is True
            for fact in context.result_facts
        )
        if verified_zero:
            return (
                f"{name}{radius} {facility_label}{_subject_particle(facility_label)} "
                "0곳으로 확인됐습니다."
            )
        return f"{name}의 {facility_label}은 현재 검증 가능한 근거가 없어 답할 수 없습니다."
    nearest = min(
        facilities,
        key=lambda fact: int(fact.payload["distanceMeters"]),
    )
    nearest_name = nearest.payload[name_key]
    distance = nearest.payload["distanceMeters"]
    return (
        f"{name}{radius} {facility_label} {len(facilities)}곳을 확인했습니다. "
        f"가장 가까운 곳은 {nearest_name}, 직선거리 {distance:,}m입니다."
    )


def _rail_lead(context: ResolvedAnswerContext) -> str:
    name = context.subject_name or "요청한 단지"
    stations = [
        fact for fact in context.result_facts
        if isinstance(fact.payload.get("stationName"), str)
        and isinstance(fact.payload.get("distanceMeters"), int)
    ]
    radius = (
        f" 중심 {context.radius_meters:,}m에서"
        if context.radius_meters else " 주변에서"
    )
    if not stations:
        return f"{name}의 가까운 철도역·노선은 현재 검증 가능한 근거가 없어 답할 수 없습니다."
    nearest = min(
        stations,
        key=lambda fact: int(fact.payload["distanceMeters"]),
    )
    station_name = nearest.payload["stationName"]
    distance = nearest.payload["distanceMeters"]
    lines = nearest.payload.get("lines")
    line_text = (
        "·".join(lines)
        if isinstance(lines, list) and lines and all(isinstance(line, str) for line in lines)
        else "노선 정보 확인 불가"
    )
    return (
        f"{name}{radius} 철도역 {len(stations)}곳을 확인했습니다. 가장 가까운 역은 "
        f"{station_name}, 직선거리 {distance:,}m이며 {line_text}이 운행됩니다."
    )


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
    if plan.capability == "recent_trade_lookup":
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
    academy_registry_ids: dict[str, list[str]] = {}
    if plan.capability == "academy_lookup":
        for fact in facts:
            facility_id = fact.payload.get("facilityId")
            if (
                fact.fact_id.startswith("academy-registry-exact-")
                and isinstance(facility_id, str)
            ):
                academy_registry_ids.setdefault(facility_id, []).append(fact.fact_id)
    for fact in facts:
        if fact.fact_id.startswith("academy-registry-exact-"):
            continue
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
        fact_ids = [fact.fact_id]
        facility_id = fact.payload.get("facilityId")
        if isinstance(facility_id, str):
            fact_ids.extend(academy_registry_ids.get(facility_id, ()))
        items.append({
            "label": name.strip(),
            "value": " · ".join(details) if details else "확인된 정보",
            "factIds": fact_ids,
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

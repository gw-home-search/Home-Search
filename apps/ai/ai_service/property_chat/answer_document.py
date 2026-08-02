from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date

from ai_service.models import ChatbotQueryRequest

from .models import DraftAnswer, EvidenceFact, QueryPlan
from .answer_report import build_answer_report
from .presentation import AnswerPresentation, FragmentPresentation, GroundedPresentationText
from .recommendation_errors import RecommendationExecutionError

_MAX_ARTIFACT_BYTES = 65_536
_MAX_LABEL_LENGTH = 100
_MAX_DISPLAY_LENGTH = 2_000


@dataclass(frozen=True)
class AnswerSection:
    text: str
    fact_ids: tuple[str, ...]


@dataclass(frozen=True)
class FactListItem:
    label: str
    value: str
    fact_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if not 1 <= len(self.label.strip()) <= _MAX_LABEL_LENGTH:
            raise ValueError("fact list label is outside the public contract")
        if not 1 <= len(self.value.strip()) <= _MAX_DISPLAY_LENGTH:
            raise ValueError("fact list value is outside the public contract")
        if not self.fact_ids or len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("fact list item requires unique fact ids")

    def to_public_dict(self) -> dict[str, object]:
        return {
            "label": self.label.strip(),
            "value": self.value.strip(),
            "factIds": list(self.fact_ids),
        }


@dataclass(frozen=True)
class FactListArtifact:
    artifact_id: str
    title: str
    items: tuple[FactListItem, ...]

    def __post_init__(self) -> None:
        if not 1 <= len(self.title.strip()) <= _MAX_LABEL_LENGTH:
            raise ValueError("fact list title is outside the public contract")
        if not 1 <= len(self.items) <= 10:
            raise ValueError("fact list item count is outside the public contract")

    def to_public_dict(self) -> dict[str, object]:
        artifact: dict[str, object] = {
            "type": "factList",
            "version": 1,
            "artifactId": self.artifact_id,
            "title": self.title.strip(),
            "items": [item.to_public_dict() for item in self.items],
        }
        if len(json.dumps(artifact, ensure_ascii=False).encode("utf-8")) > _MAX_ARTIFACT_BYTES:
            raise ValueError("fact list artifact exceeds the public size limit")
        return artifact


class FactListPresenter:
    def present(
        self,
        *,
        plan: QueryPlan,
        used_facts: list[EvidenceFact],
        readiness: str,
    ) -> list[dict[str, object]]:
        if readiness != "supported":
            return []
        if plan.capability == "childcare_lookup":
            return self._present_childcare(plan, used_facts)
        if plan.capability != "complex_identity":
            return []
        fact = next(
            (
                item for item in used_facts
                if item.fact_id.startswith("property-complex-")
                and isinstance(item.payload.get("complexId"), int)
            ),
            None,
        )
        if fact is None:
            return []
        payload = fact.payload
        complex_id = payload.get("complexId")
        display_name = payload.get("displayName")
        if not isinstance(complex_id, int) or not isinstance(display_name, str):
            return []
        items = [FactListItem("단지명", display_name, (fact.fact_id,))]
        self._append_text(items, "지역", payload.get("regionName"), fact.fact_id)
        self._append_text(items, "주소", payload.get("address"), fact.fact_id)
        unit_count = payload.get("unitCount")
        if isinstance(unit_count, int) and not isinstance(unit_count, bool):
            items.append(
                FactListItem(
                    "세대수",
                    f"{unit_count:,}세대",
                    (fact.fact_id,),
                )
            )
        use_date = payload.get("useDate")
        if isinstance(use_date, str):
            try:
                formatted_use_date = date.fromisoformat(use_date).strftime("%Y.%m.%d")
            except ValueError:
                formatted_use_date = None
            if formatted_use_date is not None:
                items.append(FactListItem(
                    "사용승인일", formatted_use_date, (fact.fact_id,),
                ))
        artifact = FactListArtifact(
            artifact_id=f"fact-list-complex-{complex_id}",
            title="확인된 단지 정보",
            items=tuple(items),
        )
        return [artifact.to_public_dict()]

    @staticmethod
    def _present_childcare(
        plan: QueryPlan,
        used_facts: list[EvidenceFact],
    ) -> list[dict[str, object]]:
        scope = next(
            (
                fact
                for fact in used_facts
                if fact.fact_id.startswith("childcare-scope-")
            ),
            None,
        )
        if scope is None or plan.radius_meters is None:
            return []
        complex_id = scope.payload.get("complexId")
        if not isinstance(complex_id, int):
            return []
        items: list[FactListItem] = []
        for fact in used_facts:
            if not fact.fact_id.startswith("childcare-center-"):
                continue
            payload = fact.payload
            center_name = payload.get("centerName")
            center_type = payload.get("centerType")
            capacity = payload.get("capacity")
            distance = payload.get("distanceMeters")
            reference_date = payload.get("referenceDate")
            if (
                not isinstance(center_name, str)
                or not 1 <= len(center_name.strip()) <= _MAX_LABEL_LENGTH
                or not isinstance(center_type, str)
                or not center_type.strip()
                or isinstance(capacity, bool)
                or not isinstance(capacity, int)
                or isinstance(distance, bool)
                or not isinstance(distance, int)
                or not isinstance(reference_date, str)
            ):
                return []
            items.append(
                FactListItem(
                    center_name,
                    (
                        f"{center_type} · 정원 {capacity}명 · 직선거리 {distance}m · "
                        f"기준일 {reference_date}"
                    ),
                    (fact.fact_id,),
                )
            )
        if not items:
            matched_count = scope.payload.get("matchedCount")
            verified_zero = scope.payload.get("verifiedZero")
            if matched_count != 0 or verified_zero is not True:
                return []
            items.append(
                FactListItem(
                    "검색 결과",
                    f"반경 {plan.radius_meters}m에서 확인되지 않음",
                    (scope.fact_id,),
                )
            )
        return [
            FactListArtifact(
                artifact_id=(
                    f"fact-list-childcare-{complex_id}-{plan.radius_meters}"
                ),
                title="확인된 공식 어린이집",
                items=tuple(items),
            ).to_public_dict()
        ]

    @staticmethod
    def _append_text(
        items: list[FactListItem], label: str, value: object, fact_id: str
    ) -> None:
        if isinstance(value, str) and value.strip():
            items.append(FactListItem(label, value, (fact_id,)))


@dataclass(frozen=True)
class AnswerDocument:
    request: ChatbotQueryRequest
    request_id: str
    plan: QueryPlan
    sections: tuple[AnswerSection, ...]
    used_facts: tuple[EvidenceFact, ...]
    limitations: tuple[str, ...]
    readiness: str
    artifacts: tuple[dict[str, object], ...] = ()
    actions: tuple[dict[str, object], ...] = ()
    presentation: AnswerPresentation | None = None
    outcome_state: str = "EXACT"
    assumptions: tuple[str, ...] = ()
    fallback_steps: tuple[str, ...] = ()
    recoverable: bool = True
    primary_artifact_id: str | None = None
    suggested_questions: tuple[str, ...] = ()
    selection_reason: str | None = None
    selection_reason_fact_ids: tuple[str, ...] = ()

    @classmethod
    def from_grounded_result(
        cls,
        *,
        request: ChatbotQueryRequest,
        request_id: str,
        plan: QueryPlan,
        draft: DraftAnswer,
        used_facts: list[EvidenceFact],
        limitations: list[str],
        readiness: str,
        artifacts: list[dict[str, object]],
        actions: list[dict[str, object]],
        presentation: AnswerPresentation | None = None,
        outcome_state: str = "EXACT",
        assumptions: tuple[str, ...] = (),
        fallback_steps: tuple[str, ...] = (),
        recoverable: bool = True,
        primary_artifact_id: str | None = None,
        suggested_questions: tuple[str, ...] = (),
        selection_reason: str | None = None,
        selection_reason_fact_ids: tuple[str, ...] = (),
    ) -> AnswerDocument:
        draft_sections = tuple(
            AnswerSection(sentence.text.strip(), tuple(sentence.fact_ids))
            for sentence in draft.sentences
        )
        if presentation is not None:
            lead = AnswerSection(
                presentation.headline.text.strip(),
                tuple(presentation.headline.fact_ids),
            )
            sections = (lead, *draft_sections[1:])
        else:
            sections = draft_sections
        return cls(
            request=request,
            request_id=request_id,
            plan=plan,
            sections=sections,
            used_facts=tuple(used_facts),
            limitations=tuple(limitations),
            readiness=readiness,
            artifacts=_bounded_artifacts(artifacts),
            actions=_bounded_actions(actions),
            presentation=presentation,
            outcome_state=outcome_state,
            assumptions=assumptions,
            fallback_steps=fallback_steps,
            recoverable=recoverable,
            primary_artifact_id=primary_artifact_id,
            suggested_questions=suggested_questions,
            selection_reason=selection_reason,
            selection_reason_fact_ids=selection_reason_fact_ids,
        )

    def to_public_dict(self) -> dict[str, object]:
        answer = " ".join(section.text for section in self.sections)
        try:
            citations = _citations(self.used_facts)
        except Exception as exception:
            if self.plan.capability == "recommendation":
                raise RecommendationExecutionError(
                    "RECOMMENDATION_CITATION_SERIALIZATION_FAILED"
                ) from exception
            raise
        data_as_of = min((fact.data_as_of for fact in self.used_facts), default=None)
        success = self.readiness != "unavailable"
        legacy_status = (
            "failed"
            if self.readiness == "unavailable"
            else "partial_success"
            if self.readiness == "partial"
            else "success"
        )
        try:
            ui_summary = (
                self.presentation.to_public_dict(
                    {fact.fact_id for fact in self.used_facts}
                )
                if self.presentation else None
            )
        except Exception as exception:
            if self.plan.capability == "recommendation":
                raise RecommendationExecutionError(
                    "RECOMMENDATION_UI_SUMMARY_SERIALIZATION_FAILED"
                ) from exception
            raise
        if self.suggested_questions:
            if ui_summary is None:
                primary = next((
                    fact for fact in self.used_facts
                    if fact.fact_id.startswith("property-complex-")
                ), None)
                if primary is not None:
                    ui_summary = {
                        "version": 1,
                        "scopeNotice": None,
                        "headline": {
                            "text": self.limitations[0],
                            "factIds": [primary.fact_id],
                        },
                        "criteria": [],
                        "interpretations": [],
                        "followUp": " · ".join(self.suggested_questions),
                        "fragmentSummaries": [],
                    }
            else:
                ui_summary = {
                    **ui_summary,
                    "followUp": " · ".join(self.suggested_questions),
                }
        if self.selection_reason and ui_summary is not None:
            used_fact_ids = {fact.fact_id for fact in self.used_facts}
            if (
                not 1 <= len(self.selection_reason_fact_ids) <= 10
                or len(self.selection_reason_fact_ids)
                != len(set(self.selection_reason_fact_ids))
                or not set(self.selection_reason_fact_ids).issubset(used_fact_ids)
            ):
                raise ValueError("selection reason facts are invalid")
            criteria = ui_summary.get("criteria")
            existing_criteria = criteria if isinstance(criteria, list) else []
            ui_summary = {
                **ui_summary,
                "criteria": [
                    *existing_criteria[:3],
                    {
                        "key": "representativeSelection",
                        "label": "대표 선택 근거",
                        "value": self.selection_reason,
                        "factIds": list(self.selection_reason_fact_ids),
                    },
                ],
            }
        ui_report, public_artifacts = build_answer_report(
            plan=self.plan,
            ui_summary=ui_summary,
            artifacts=self.artifacts,
            actions=self.actions,
            facts=self.used_facts,
            preferred_primary_artifact_id=self.primary_artifact_id,
        )
        return {
            "success": success,
            "status": legacy_status,
            "question": self.request.question,
            "fragments": [],
            "result": {},
            "message": "",
            "executionSummary": {
                "total": 1,
                "succeeded": int(success),
                "failed": int(not success),
            },
            "answer": answer,
            "resolvedQuestion": self.request.question,
            "conversationResolution": _conversation_resolution((self,)),
            "conversationMemoryPatch": _conversation_memory_patch(
                self.plan, self.used_facts
            ),
            "uiActions": list(self.actions),
            "uiArtifacts": list(public_artifacts),
            "uiSummary": ui_summary,
            "uiReport": ui_report,
            "requestId": self.request_id,
            "citations": citations,
            "dataAsOf": data_as_of.isoformat() if data_as_of else None,
            "limitations": list(self.limitations),
            "evidenceSummary": {
                "status": self.readiness,
                "capabilities": [self.plan.capability],
                "factCount": len(self.used_facts),
                "citationCount": len(citations),
            },
        }


@dataclass(frozen=True)
class CompoundAnswerDocument:
    request: ChatbotQueryRequest
    request_id: str
    fragments: tuple[AnswerDocument, ...]

    def __post_init__(self) -> None:
        if not 2 <= len(self.fragments) <= 4:
            raise ValueError("compound answer must contain 2..4 fragments")
        if any(
            fragment.request != self.request or fragment.request_id != self.request_id
            for fragment in self.fragments
        ):
            raise ValueError("compound answer fragment request does not match")

    def to_public_dict(self) -> dict[str, object]:
        facts = _deduplicate_facts(self.fragments)
        citations = _citations(facts)
        data_as_of = min((fact.data_as_of for fact in facts), default=None)
        succeeded = sum(
            fragment.readiness != "unavailable" for fragment in self.fragments
        )
        failed = len(self.fragments) - succeeded
        evidence_status = (
            "unavailable"
            if succeeded == 0
            else "partial"
            if any(fragment.readiness != "supported" for fragment in self.fragments)
            else "supported"
        )
        status = (
            "failed"
            if succeeded == 0
            else "partial_success"
            if failed or evidence_status == "partial"
            else "success"
        )
        ui_summary = _compound_presentation(
            self.fragments, succeeded, failed, facts
        )
        compound_lead = (
            ui_summary["headline"]["text"]
            if isinstance(ui_summary, dict)
            and isinstance(ui_summary.get("headline"), dict)
            and isinstance(ui_summary["headline"].get("text"), str)
            else None
        )
        successful_first = tuple(sorted(
            self.fragments,
            key=lambda fragment: fragment.readiness == "unavailable",
        ))
        detail_text = " ".join(
            section.text
            for fragment in successful_first
            for section in fragment.sections
        )
        answer = (
            f"{compound_lead} {detail_text}" if compound_lead else detail_text
        ).strip()
        if not answer or len(answer) > 20_000:
            raise ValueError("compound answer text exceeds the public contract")
        limitations = tuple(dict.fromkeys(
            limitation
            for fragment in self.fragments
            for limitation in fragment.limitations
        ))[:50]
        artifacts = _bounded_artifacts(
            artifact
            for fragment in self.fragments
            for artifact in fragment.artifacts
        )
        actions = _bounded_actions(
            action
            for fragment in self.fragments
            for action in fragment.actions
        )
        artifact_ids = {
            artifact_id
            for artifact in artifacts
            if isinstance((artifact_id := artifact.get("artifactId")), str)
        }
        action_ids = {
            action_id
            for action in actions
            if isinstance((action_id := action.get("actionId")), str)
        }
        return {
            "success": succeeded > 0,
            "status": status,
            "question": self.request.question,
            "fragments": [
                _fragment_dict(index, fragment, artifact_ids, action_ids)
                for index, fragment in enumerate(self.fragments, start=1)
            ],
            "result": {},
            "message": "",
            "executionSummary": {
                "total": len(self.fragments),
                "succeeded": succeeded,
                "failed": failed,
            },
            "answer": answer,
            "resolvedQuestion": self.request.question,
            "conversationResolution": _conversation_resolution(self.fragments),
            "conversationMemoryPatch": _conversation_memory_patch(None, facts),
            "uiActions": list(actions),
            "uiArtifacts": list(artifacts),
            "uiSummary": ui_summary,
            "requestId": self.request_id,
            "citations": citations,
            "dataAsOf": data_as_of.isoformat() if data_as_of else None,
            "limitations": list(limitations),
            "evidenceSummary": {
                "status": evidence_status,
                "capabilities": [
                    fragment.plan.capability for fragment in self.fragments
                ],
                "factCount": len(facts),
                "citationCount": len(citations),
            },
        }


def _fragment_dict(
    index: int,
    fragment: AnswerDocument,
    allowed_artifact_ids: set[str],
    allowed_action_ids: set[str],
) -> dict[str, object]:
    succeeded = fragment.readiness != "unavailable"
    return {
        "fragmentId": f"fragment-{index}",
        "capability": fragment.plan.capability,
        "status": "success" if succeeded else "failed",
        "answer": " ".join(section.text for section in fragment.sections),
        "factIds": [fact.fact_id for fact in fragment.used_facts],
        "artifactIds": [
            artifact_id
            for artifact in fragment.artifacts
            if isinstance((artifact_id := artifact.get("artifactId")), str)
            and artifact_id in allowed_artifact_ids
        ],
        "actionIds": [
            action_id
            for action in fragment.actions
            if isinstance((action_id := action.get("actionId")), str)
            and action_id in allowed_action_ids
        ],
        "limitations": list(fragment.limitations),
    }


def _conversation_resolution(
    fragments: tuple[AnswerDocument, ...],
) -> dict[str, object]:
    has_unavailable = any(
        fragment.readiness == "unavailable" for fragment in fragments
    )
    has_degraded = any(
        fragment.readiness == "partial" or fragment.outcome_state == "DEGRADED"
        for fragment in fragments
    )
    has_only_empty_results = all(
        fragment.outcome_state == "EMPTY" for fragment in fragments
    )
    answer_mode = (
        "NO_RESULT"
        if all(fragment.readiness == "unavailable" for fragment in fragments)
        or has_only_empty_results
        else "PARTIAL"
        if has_unavailable
        else "BEST_EFFORT"
        if has_degraded
        else "COMPLETE"
    )
    assumptions = []
    for fragment in fragments:
        for step in fragment.fallback_steps:
            assumptions.append({
                "code": step,
                "text": _fallback_step_text(step),
            })
        for index, text in enumerate(fragment.assumptions, start=1):
            assumptions.append({
                "code": f"ASSUMPTION_{index}",
                "text": text,
            })
    unique_assumptions = list({
        (item["code"], item["text"]): item for item in assumptions
    }.values())[:8]
    omissions = list(dict.fromkeys(
        limitation
        for fragment in fragments
        if fragment.readiness == "unavailable"
        for limitation in fragment.limitations
    ))[:8]
    return {
        "version": 1,
        "answerMode": answer_mode,
        "goals": [
            {
                "capability": fragment.plan.capability,
                "status": (
                    "unavailable"
                    if fragment.readiness == "unavailable"
                    else "degraded"
                    if fragment.readiness == "partial"
                    or fragment.outcome_state in {"DEGRADED", "EMPTY"}
                    else "answered"
                ),
            }
            for fragment in fragments
        ],
        "assumptions": unique_assumptions,
        "omissions": omissions,
    }


def _fallback_step_text(step: str) -> str:
    return {
        "SAME_AREA_ANY_PERIOD": (
            "정확 조건에 거래가 없어 같은 면적의 확인 가능한 최근 거래를 참고했습니다."
        ),
        "RECENT_INDIVIDUAL_TRADES": (
            "월별 추이를 만들 표본이 없어 같은 조건의 최근 개별 거래를 참고했습니다."
        ),
        "DEFAULT_PERIOD_ONE_YEAR": "기간을 지정하지 않아 최근 1년을 기준으로 확인했습니다.",
        "DEFAULT_FACILITY_RADIUS": "시설별 기본 검색 반경을 적용했습니다.",
        "PARTIAL_RECOMMENDATION_METRICS": (
            "확인 가능한 기준만 사용해 추천 후보를 정리했습니다."
        ),
        "NEAREST_CONSTRAINT_CANDIDATES": (
            "정확 조건을 충족한 후보가 없어 조건 차이가 작은 후보를 참고했습니다."
        ),
    }.get(step, "확인 가능한 대체 근거를 함께 사용했습니다.")


def _conversation_memory_patch(
    plan: QueryPlan | None,
    facts: tuple[EvidenceFact, ...],
) -> dict[str, object] | None:
    candidate_facts = tuple(
        fact
        for fact in facts
        if fact.fact_id.startswith("property-complex-")
        and isinstance(fact.payload.get("complexId"), int)
        and not isinstance(fact.payload.get("complexId"), bool)
    )
    if plan is not None and plan.capability == "recommendation":
        complex_ids = list(dict.fromkeys(
            fact.payload["complexId"] for fact in candidate_facts
        ))[:5]
        if len(complex_ids) >= 2:
            patch: dict[str, object] = {
                "version": 2,
                "complexIds": complex_ids,
                "scopeKind": "RECOMMENDATION",
            }
            region_code = candidate_facts[0].payload.get("regionCode")
            if isinstance(region_code, str) and region_code.isdigit():
                patch["regionCode"] = region_code
            return patch
    for fact in facts:
        complex_id = fact.payload.get("complexId")
        if not isinstance(complex_id, int) or isinstance(complex_id, bool) or complex_id <= 0:
            continue
        patch: dict[str, object] = {
            "version": 1,
            "complexId": complex_id,
            "scopeKind": "COMPLEX",
        }
        region_code = fact.payload.get("regionCode")
        if isinstance(region_code, str) and region_code.isdigit():
            patch["regionCode"] = region_code
        return patch
    return None


def _deduplicate_facts(
    fragments: tuple[AnswerDocument, ...],
) -> tuple[EvidenceFact, ...]:
    by_id: dict[str, EvidenceFact] = {}
    for fragment in fragments:
        for fact in fragment.used_facts:
            existing = by_id.get(fact.fact_id)
            if existing is not None and existing != fact:
                raise ValueError("compound fragments disagree on an evidence fact")
            by_id.setdefault(fact.fact_id, fact)
    return tuple(by_id.values())


def _bounded_artifacts(
    artifacts: Iterable[dict[str, object]],
) -> tuple[dict[str, object], ...]:
    accepted: list[dict[str, object]] = []
    encoded_bytes = 2
    for artifact in artifacts:
        if len(accepted) == 8:
            break
        size = len(json.dumps(artifact, ensure_ascii=False).encode("utf-8"))
        if encoded_bytes + size + int(bool(accepted)) > _MAX_ARTIFACT_BYTES:
            continue
        accepted.append(artifact)
        encoded_bytes += size + int(len(accepted) > 1)
    return tuple(accepted)


def _bounded_actions(
    actions: Iterable[dict[str, object]],
) -> tuple[dict[str, object], ...]:
    accepted: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    seen_complex_ids: set[int] = set()
    focus_count = 0
    nearby_count = 0
    has_auto_run = False
    encoded_bytes = 2
    for action in actions:
        if len(accepted) == 10:
            break
        action_id = action.get("actionId")
        action_type = action.get("type")
        if not isinstance(action_id, str) or action_id in seen_ids:
            continue
        if action_type == "focusComplex":
            complex_id = action.get("complexId")
            auto_run = action.get("autoRun")
            if (
                focus_count == 6
                or not isinstance(complex_id, int)
                or isinstance(complex_id, bool)
                or complex_id <= 0
                or complex_id in seen_complex_ids
                or not isinstance(auto_run, bool)
                or auto_run and has_auto_run
            ):
                continue
        elif action_type == "showNearbyCategory":
            if nearby_count == 4:
                continue
        else:
            continue
        size = len(json.dumps(action, ensure_ascii=False).encode("utf-8"))
        if encoded_bytes + size + int(bool(accepted)) > 16_384:
            continue
        accepted.append(action)
        seen_ids.add(action_id)
        encoded_bytes += size + int(len(accepted) > 1)
        if action_type == "focusComplex":
            focus_count += 1
            seen_complex_ids.add(action["complexId"])  # type: ignore[arg-type]
            has_auto_run = has_auto_run or bool(action["autoRun"])
        else:
            nearby_count += 1
    return tuple(accepted)


def _citations(facts: tuple[EvidenceFact, ...]) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str, str | None, str, str, date], list[str]] = {}
    for fact in facts:
        key = (
            fact.source_id,
            fact.source_name,
            fact.source_url,
            fact.evidence_grade,
            fact.dataset_version,
            fact.data_as_of,
        )
        grouped.setdefault(key, []).append(fact.fact_id)
    return [
        {
            "citationId": f"citation-{index}",
            "sourceId": source_id,
            "sourceName": source_name,
            "sourceUrl": source_url,
            "evidenceGrade": evidence_grade,
            "datasetVersion": version,
            "dataAsOf": data_as_of.isoformat(),
            "observedAt": None,
            "factIds": fact_ids,
        }
        for index, (
            (source_id, source_name, source_url, evidence_grade, version, data_as_of),
            fact_ids,
        ) in enumerate(grouped.items(), start=1)
    ]


def _compound_presentation(
    fragments: tuple[AnswerDocument, ...],
    succeeded: int,
    failed: int,
    facts: tuple[EvidenceFact, ...],
) -> dict[str, object] | None:
    if not facts:
        return None
    fact_ids = tuple(fact.fact_id for fact in facts)
    successful = tuple(
        fragment for fragment in fragments
        if fragment.readiness != "unavailable" and fragment.presentation is not None
    )
    if not successful:
        return None
    headline = _compound_lead(successful, fragments, failed)
    presentation = AnswerPresentation(
        headline=GroundedPresentationText(headline, fact_ids),
        fragment_summaries=tuple(
            FragmentPresentation(
                fragment_id=f"fragment-{index}",
                capability=fragment.plan.capability,
                status=("failed" if fragment.readiness == "unavailable" else "success"),
                headline=(
                    fragment.presentation.headline.text
                    if fragment.presentation
                    else "필요한 데이터가 아직 준비되지 않았습니다."
                ),
                fact_ids=tuple(fact.fact_id for fact in fragment.used_facts),
            )
            for index, fragment in enumerate(fragments, start=1)
        ),
    )
    return presentation.to_public_dict(set(fact_ids))


def _compound_lead(
    successful: tuple[AnswerDocument, ...],
    fragments: tuple[AnswerDocument, ...],
    failed: int,
) -> str:
    by_capability = {fragment.plan.capability: fragment for fragment in successful}
    academy = by_capability.get("academy_lookup")
    rail = by_capability.get("rail_station_lookup")
    if academy is not None and rail is not None:
        facts = _deduplicate_facts((academy, rail))
        complex_fact = next(
            (fact for fact in facts if fact.fact_id.startswith("property-complex-")),
            None,
        )
        name = (
            complex_fact.payload.get("displayName")
            if complex_fact is not None else academy.plan.complex_name
        )
        academy_scope = next(
            (fact for fact in facts if fact.fact_id.startswith("sbiz-academy-scope-")),
            None,
        )
        rail_scope = next(
            (fact for fact in facts if fact.fact_id.startswith("rail-scope-")),
            None,
        )
        academy_count = (
            academy_scope.payload.get("matchedCount") if academy_scope else None
        )
        rail_count = rail_scope.payload.get("stationCount") if rail_scope else None
        nearest = next(
            (fact for fact in facts if fact.fact_id.startswith("rail-station-")),
            None,
        )
        station_name = nearest.payload.get("stationName") if nearest else None
        lines = nearest.payload.get("lines") if nearest else None
        if (
            isinstance(name, str)
            and isinstance(academy_count, int)
            and isinstance(rail_count, int)
            and isinstance(station_name, str)
        ):
            line_text = (
                "·".join(lines)
                if isinstance(lines, list) and all(isinstance(line, str) for line in lines)
                else "노선 정보"
            )
            return (
                f"{name} 주변에서 학원 위치 {academy_count}곳과 가까운 철도역 "
                f"{rail_count}곳을 확인했습니다. 가장 가까운 역은 {station_name}이며 "
                f"{line_text}이 운행됩니다."
            )
    first = successful[0].presentation
    assert first is not None
    headline = first.headline.text
    if failed:
        unavailable = next(
            fragment for fragment in fragments if fragment.readiness == "unavailable"
        )
        label = {
            "academy_lookup": "학원 위치",
            "rail_station_lookup": "가까운 역·노선",
            "recent_trade_lookup": "최근 실거래",
            "price_trend": "가격 흐름",
        }.get(unavailable.plan.capability, "나머지 정보")
        return f"{headline} 다만 {label}은 현재 확인하지 못했습니다."
    if len(successful) == 1:
        return headline
    return " ".join(
        fragment.presentation.headline.text
        for fragment in successful
        if fragment.presentation is not None
    )

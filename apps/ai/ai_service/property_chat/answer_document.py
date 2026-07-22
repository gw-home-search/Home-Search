from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date

from ai_service.models import ChatbotQueryRequest

from .models import DraftAnswer, EvidenceFact, QueryPlan
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
        if len(used_facts) != 1:
            return []
        fact = used_facts[0]
        payload = fact.payload
        complex_id = payload.get("complexId")
        display_name = payload.get("displayName")
        if not isinstance(complex_id, int) or not isinstance(display_name, str):
            return []
        items = [FactListItem("단지명", display_name, (fact.fact_id,))]
        self._append_text(items, "지역", payload.get("regionName"), fact.fact_id)
        self._append_text(items, "주소", payload.get("address"), fact.fact_id)
        latitude = payload.get("latitude")
        longitude = payload.get("longitude")
        if isinstance(latitude, int | float) and isinstance(longitude, int | float):
            items.append(
                FactListItem(
                    "위치",
                    f"{latitude:g}, {longitude:g}",
                    (fact.fact_id,),
                )
            )
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
    ) -> AnswerDocument:
        return cls(
            request=request,
            request_id=request_id,
            plan=plan,
            sections=tuple(
                AnswerSection(sentence.text.strip(), tuple(sentence.fact_ids))
                for sentence in draft.sentences
            ),
            used_facts=tuple(used_facts),
            limitations=tuple(limitations),
            readiness=readiness,
            artifacts=_bounded_artifacts(artifacts),
            actions=tuple(actions[:4]),
            presentation=presentation,
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
            "conversationResolution": None,
            "conversationMemoryPatch": None,
            "uiActions": list(self.actions),
            "uiArtifacts": list(self.artifacts),
            "uiSummary": ui_summary,
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
        answer = " ".join(
            section.text
            for fragment in self.fragments
            for section in fragment.sections
        )
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
        actions = tuple(
            action
            for fragment in self.fragments
            for action in fragment.actions
        )[:4]
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
            "conversationResolution": None,
            "conversationMemoryPatch": None,
            "uiActions": list(actions),
            "uiArtifacts": list(artifacts),
            "uiSummary": _compound_presentation(
                self.fragments, succeeded, failed, facts
            ),
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
    headline = (
        f"{len(fragments)}개 요청을 모두 확인했습니다."
        if failed == 0
        else f"{len(fragments)}개 중 {succeeded}개 요청을 확인했습니다."
    )
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

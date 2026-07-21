from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date

from ai_service.models import ChatbotQueryRequest

from .models import DraftAnswer, EvidenceFact, QueryPlan

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
            artifacts=tuple(artifacts[:8]),
        )

    def to_public_dict(self) -> dict[str, object]:
        answer = " ".join(section.text for section in self.sections)
        citations = _citations(self.used_facts)
        data_as_of = min((fact.data_as_of for fact in self.used_facts), default=None)
        success = self.readiness != "unavailable"
        legacy_status = (
            "failed"
            if self.readiness == "unavailable"
            else "partial_success"
            if self.readiness == "partial"
            else "success"
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
            "conversationResolution": None,
            "conversationMemoryPatch": None,
            "uiActions": [],
            "uiArtifacts": list(self.artifacts),
            "uiSummary": None,
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

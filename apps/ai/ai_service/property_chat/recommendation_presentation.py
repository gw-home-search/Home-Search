from __future__ import annotations

from .models import DraftAnswer, DraftClaim, DraftSentence, EvidenceFact


class RecommendationTextPresenter:
    """Build the recommendation text fallback from server-observed facts."""

    def present(
        self,
        *,
        facts: list[EvidenceFact],
        limitations: list[str],
        readiness: str,
    ) -> DraftAnswer:
        if readiness == "unavailable":
            return DraftAnswer([
                DraftSentence(
                    limitations[0]
                    if limitations
                    else "필요한 데이터가 아직 준비되지 않았습니다.",
                    [],
                    [],
                )
            ])
        if not facts:
            raise ValueError("supported recommendation requires evidence facts")

        has_candidate = any(
            fact.fact_id.startswith("property-complex-") for fact in facts
        )
        text = (
            "현재 데이터 기준으로 요청한 조건에서 확인된 후보를 정리했습니다."
            if has_candidate
            else "확인한 범위에서는 요청한 조건을 모두 충족한 후보를 확인하지 못했습니다."
        )
        return DraftAnswer([
            DraftSentence(
                text,
                [fact.fact_id for fact in facts],
                [
                    DraftClaim(
                        fact.fact_id,
                        fact.claims[0].value,
                        fact.claims[0].unit,
                    )
                    for fact in facts
                ],
            )
        ])

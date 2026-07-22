from __future__ import annotations

import re

from .models import DraftAnswer, EvidenceFact


class AnswerQualityError(ValueError):
    pass


class AnswerQualityGate:
    """Reject answer-shaped deflections after useful evidence was observed."""

    def validate(
        self,
        *,
        draft: DraftAnswer,
        facts: list[EvidenceFact],
        readiness: str,
    ) -> None:
        text = " ".join(sentence.text.strip() for sentence in draft.sentences).strip()
        if not text:
            raise AnswerQualityError()
        if readiness != "unavailable" and facts and _is_request_only(text):
            raise AnswerQualityError()


def _is_request_only(text: str) -> bool:
    asks_for_more = re.search(
        r"(?:알려|입력|선택|지정|좁혀|추가).{0,30}(?:주세요|해야\s*합니다|필요합니다)",
        text,
    )
    contains_result_statement = re.search(
        r"(?:확인|조회|정리)(?:된|한|했습니다|됩니다)|"
        r"(?:없|있|해당|위치|주소|가격|거래|후보)(?:습니다|입니다|로\s*확인)",
        text,
    )
    return asks_for_more is not None and contains_result_statement is None

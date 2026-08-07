from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass


_OVERVIEW_SUFFIX = re.compile(
    r"(?:\s+(?:전체적(?:으로)?|전반적(?:으로)?))?"
    r"(?:\s+(?:아파트|단지))?"
    r"(?:은|는|이|가)?\s*"
    r"(?:어때|어떄|어떤가|괜찮아|평가해\s*줘|살기\s*어때)\s*$"
)
_AREA = re.compile(r"(?:전용\s*)?[0-9]+(?:\.[0-9]+)?\s*(?:㎡|m2|제곱미터)", re.I)
_REGION_PREFIX = re.compile(r"^([가-힣]{1,20}(?:동|읍|면|구|군|시))\s+(.+)$")
_QUESTION_SPLIT = re.compile(
    r"\s+(?:전용|최근|가격|실거래|거래|위치|주소|기본정보|어디|주변|가까운|전체적|전반적)"
)


@dataclass(frozen=True)
class NormalizedQuestion:
    normalized_question: str
    entity_candidate: str | None
    region_hint: str | None
    overview: bool


def normalize_question(question: str) -> NormalizedQuestion:
    normalized = unicodedata.normalize("NFKC", question)
    normalized = re.sub(r"[\s\u2000-\u200b\u3000]+", " ", normalized).strip()
    normalized = normalized.rstrip(" ?!,.\"")
    overview_match = _OVERVIEW_SUFFIX.search(normalized)

    if overview_match is not None:
        candidate = normalized[: overview_match.start()].strip()
        candidate = _AREA.sub(" ", candidate)
    else:
        candidate = _QUESTION_SPLIT.split(normalized, maxsplit=1)[0]
    candidate = re.sub(r"\s+", " ", candidate).strip(" ?!,.\"")

    if candidate in {"이 단지", "여기", "이곳"}:
        return NormalizedQuestion(normalized, None, None, overview_match is not None)

    region_hint = None
    region_match = _REGION_PREFIX.fullmatch(candidate)
    if region_match is not None:
        region_hint, candidate = region_match.groups()

    candidate = re.sub(r"(?:은|는)$", "", candidate).strip()
    candidate = re.sub(r"\s+(?:아파트|단지)$", "", candidate).strip()
    if not 1 <= len(candidate) <= 100:
        candidate = None
    return NormalizedQuestion(
        normalized_question=normalized,
        entity_candidate=candidate,
        region_hint=region_hint,
        overview=overview_match is not None,
    )

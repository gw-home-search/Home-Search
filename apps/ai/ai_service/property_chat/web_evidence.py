from __future__ import annotations

import re
from enum import Enum
from urllib.parse import parse_qsl, urlsplit


class WebEvidenceMode(Enum):
    REQUIRED = "REQUIRED"
    ALLOWED = "ALLOWED"
    DISABLED = "DISABLED"


OFFICIAL_WEB_DOMAINS = (
    "go.kr", "reb.or.kr", "railportal.kr", "korail.com", "seoulmetro.co.kr",
    "applyhome.co.kr",
)


class WebEvidencePolicy:
    _CURRENT_PATTERN = re.compile(
        r"(최신|현재|개통|계획|공고|고시|예정)"
    )
    _LEDGER_PATTERN = re.compile(r"(실거래|거래내역|거래 원장|가격 흐름|시세 추이)")
    _RECOMMENDATION_PATTERN = re.compile(r"(추천|어때|어떄|괜찮아|살기)\??")

    def classify(self, question: str, *, internal_axis_count: int) -> WebEvidenceMode:
        if self._LEDGER_PATTERN.search(question):
            return WebEvidenceMode.DISABLED
        if self._CURRENT_PATTERN.search(question):
            return WebEvidenceMode.REQUIRED
        if self._RECOMMENDATION_PATTERN.search(question) and internal_axis_count < 3:
            return WebEvidenceMode.ALLOWED
        return WebEvidenceMode.DISABLED


def validate_official_source_url(url: str) -> bool:
    try:
        parsed = urlsplit(url)
    except ValueError:
        return False
    if (
        parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
        or parsed.password is not None or parsed.fragment
    ):
        return False
    host = parsed.hostname.lower().rstrip(".")
    sensitive_markers = (
        "token", "key", "secret", "auth", "password", "signature", "credential",
    )
    if any(
        any(marker in key.casefold() for marker in sensitive_markers)
        for key, _value in parse_qsl(parsed.query)
    ):
        return False
    return any(host == domain or host.endswith(f".{domain}") for domain in OFFICIAL_WEB_DOMAINS)


def contains_prompt_injection(text: str) -> bool:
    normalized = text.casefold()
    return any(marker in normalized for marker in (
        "ignore previous", "ignore all", "system prompt", "developer message",
        "이전 지시를 무시", "시스템 프롬프트", "개발자 메시지",
    ))

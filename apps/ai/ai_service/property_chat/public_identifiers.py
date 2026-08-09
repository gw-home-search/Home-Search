from __future__ import annotations

import hashlib
import re

_PUBLIC_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$")


def public_identifier_token(source_identifier: str) -> str:
    """Keep safe source IDs readable and hash IDs outside the public contract."""
    normalized = source_identifier.strip()
    if _PUBLIC_IDENTIFIER.fullmatch(normalized) is not None:
        return normalized
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:16]

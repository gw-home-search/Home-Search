from __future__ import annotations

import ipaddress
import json
from collections.abc import Callable
from dataclasses import dataclass
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

_MAX_RESPONSE_BYTES = 1_048_576


@dataclass(frozen=True)
class PropertySearchCandidateDiscovery:
    base_url: str
    timeout_seconds: float = 3.0
    requester: Callable[[Request, float], bytes] | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "base_url", _validated_internal_origin(self.base_url))
        if not 0 < self.timeout_seconds <= 20:
            raise ValueError("property search timeout is outside the supported range")

    def find_complex_ids(self, query: str) -> tuple[int, ...]:
        normalized = query.strip()
        if not normalized or len(normalized) > 100:
            raise ValueError("property search query is outside the supported range")
        url = f"{self.base_url}/api/v1/search/complexes?{urlencode({'q': normalized})}"
        request = Request(url, headers={"Accept": "application/json"})
        body = (
            self.requester(request, self.timeout_seconds)
            if self.requester is not None
            else _request(request, self.timeout_seconds)
        )
        if len(body) > _MAX_RESPONSE_BYTES:
            raise RuntimeError("property search response is too large")
        payload = json.loads(body)
        if not isinstance(payload, list):
            raise RuntimeError("property search response is malformed")
        result: list[int] = []
        for item in payload[:20]:
            complex_id = item.get("complexId") if isinstance(item, dict) else None
            if (
                isinstance(complex_id, int)
                and not isinstance(complex_id, bool)
                and complex_id > 0
                and complex_id not in result
            ):
                result.append(complex_id)
        return tuple(result)


def _request(request: Request, timeout_seconds: float) -> bytes:
    with build_opener(_RejectRedirects()).open(
        request, timeout=timeout_seconds
    ) as response:  # noqa: S310
        return response.read(_MAX_RESPONSE_BYTES + 1)


class _RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        raise RuntimeError("property search redirects are not allowed")


def _validated_internal_origin(value: str) -> str:
    parsed = urlsplit(value.strip())
    if (
        parsed.scheme != "http"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("property search base URL must be an internal HTTP origin")
    hostname = parsed.hostname.lower()
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        internal = (
            hostname == "localhost"
            or "." not in hostname
            or hostname.endswith(".internal")
        )
    else:
        internal = (
            (address.is_private or address.is_loopback)
            and not address.is_link_local
            and not address.is_multicast
            and not address.is_unspecified
            and not address.is_reserved
        )
    if not internal:
        raise ValueError("property search base URL must be an internal HTTP origin")
    return value.strip().rstrip("/")

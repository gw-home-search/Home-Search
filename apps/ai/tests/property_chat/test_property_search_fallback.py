from __future__ import annotations

import json
from urllib.parse import parse_qs, urlsplit

import pytest

from ai_service.property_chat.property_search_fallback import (
    PropertySearchCandidateDiscovery,
)


def test_property_search_fallback_encodes_query_and_returns_only_valid_ids() -> None:
    captured: dict[str, object] = {}

    def request(request, timeout: float) -> bytes:
        captured["url"] = request.full_url
        captured["timeout"] = timeout
        return json.dumps([
            {"complexId": 501, "complexName": "외부 응답명"},
            {"complexId": True},
            {"complexId": -1},
            {"complexId": 501},
            {"complexId": 502},
        ]).encode()

    discovery = PropertySearchCandidateDiscovery(
        "http://property-api:8080/", requester=request
    )

    assert discovery.find_complex_ids("임의 단지&지역=서울") == (501, 502)
    assert parse_qs(urlsplit(str(captured["url"])).query) == {
        "q": ["임의 단지&지역=서울"]
    }
    assert captured["timeout"] == 3.0


@pytest.mark.parametrize(
    "base_url",
    (
        "https://property-api.internal",
        "http://example.com",
        "http://property-api.local",
        "http://user:password@property-api",
        "http://property-api/path",
        "http://169.254.169.254",
    ),
)
def test_property_search_fallback_rejects_non_internal_origin(base_url: str) -> None:
    with pytest.raises(ValueError):
        PropertySearchCandidateDiscovery(base_url)

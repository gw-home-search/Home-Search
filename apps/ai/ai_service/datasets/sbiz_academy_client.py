from __future__ import annotations

import math
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from http.client import HTTPSConnection
from urllib.parse import quote

from .bundle import BundleArtifact, build_deterministic_bundle
from .checksum import canonical_json_bytes
from .sbiz_academy import SbizTaxonomyContract, _page, taxonomy_fingerprint
from .validation import RawPayloadError


_HOST = "apis.data.go.kr"
_PATH = "/B553077/api/open/sdsc2/storeListInUpjong"
_PAGE_SIZE = 1000
_MAX_PAGES = 500
_MAX_PAGE_BYTES = 8 * 1024 * 1024
_MAX_BUNDLE_BYTES = 1024 * 1024 * 1024
_SAFE_REASONS = frozenset(
    {
        "API_AUTHENTICATION_FAILED", "API_BAD_REQUEST", "API_PAGE_TOO_LARGE",
        "API_RATE_LIMITED", "API_REDIRECT_REJECTED", "API_SERVER_ERROR",
        "API_TRANSPORT_FAILED", "PROVIDER_PAGE_INVALID",
    }
)

Requester = Callable[[str, float, str], tuple[int, Mapping[str, str], bytes]]


class SbizAcademyApiError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASONS:
            raise ValueError("invalid Sbiz API reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedSbizAcademyBundle:
    content: bytes
    observed_at: datetime
    page_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


class SbizAcademyApiClient:
    def __init__(
        self,
        *,
        taxonomy: SbizTaxonomyContract,
        taxonomy_artifacts: dict[str, object],
        requester: Requester | None = None,
        timeout_seconds: float = 10,
    ) -> None:
        if (
            set(taxonomy_artifacts) != {"taxonomy-large", "taxonomy-middle", "taxonomy-small"}
            or taxonomy_fingerprint(taxonomy_artifacts) != taxonomy.fingerprint
            or not math.isfinite(timeout_seconds)
            or not 1 <= timeout_seconds <= 30
        ):
            raise ValueError("Sbiz taxonomy evidence is not approved")
        self._taxonomy = taxonomy
        self._taxonomy_artifacts = taxonomy_artifacts
        self._requester = requester or _request
        self._timeout = float(timeout_seconds)

    def collect(self, service_key: str, *, observed_at: datetime) -> CollectedSbizAcademyBundle:
        key = service_key.strip()
        if not key or len(key) > 1024 or observed_at.tzinfo is None:
            raise ValueError("Sbiz API collection configuration is invalid")
        artifacts = [
            BundleArtifact(name, "json", "application/json", canonical_json_bytes(value))
            for name, value in sorted(self._taxonomy_artifacts.items())
        ]
        page_count = 0
        raw_row_count = 0
        total_bytes = sum(len(item.content) for item in artifacts)
        for code in sorted(self._taxonomy.allowed_small_categories):
            page_number = 1
            page_total = 1
            while page_number <= page_total:
                path = (
                    f"{_PATH}?divId=indsSclsCd&key={quote(code, safe='')}"
                    f"&pageNo={page_number}&numOfRows={_PAGE_SIZE}&type=json"
                )
                try:
                    content = self._load(path, key)
                    total, _, rows = _page(content)
                except (SbizAcademyApiError, RawPayloadError) as exception:
                    reason = (
                        exception.reason_code
                        if isinstance(exception, SbizAcademyApiError)
                        else "PROVIDER_PAGE_INVALID"
                    )
                    if page_count == 0:
                        raise SbizAcademyApiError(reason) from None
                    return _incomplete(
                        artifacts, observed_at, page_count, raw_row_count, reason
                    )
                page_total = max(1, math.ceil(total / _PAGE_SIZE))
                if page_total > _MAX_PAGES:
                    return _incomplete(
                        artifacts, observed_at, page_count, raw_row_count,
                        "PROVIDER_PAGE_INVALID",
                    )
                total_bytes += len(content)
                if total_bytes > _MAX_BUNDLE_BYTES:
                    return _incomplete(
                        artifacts, observed_at, page_count, raw_row_count,
                        "API_PAGE_TOO_LARGE",
                    )
                artifacts.append(
                    BundleArtifact(
                        f"{code.lower()}-page-{page_number}", "json",
                        "application/json", content,
                    )
                )
                page_count += 1
                raw_row_count += len(rows)
                page_number += 1
        content = build_deterministic_bundle(
            source_id="place.sbiz-academy", endpoint_path=_PATH,
            artifacts=tuple(artifacts), temporal_value=observed_at,
        )
        return CollectedSbizAcademyBundle(
            content, observed_at, page_count, raw_row_count, True, ()
        )

    def _load(self, path: str, service_key: str) -> bytes:
        for attempt in range(2):
            try:
                status, _headers, body = self._requester(path, self._timeout, service_key)
            except (OSError, TimeoutError, socket.timeout):
                if attempt == 0:
                    continue
                raise SbizAcademyApiError("API_TRANSPORT_FAILED") from None
            if status == 200:
                if len(body) > _MAX_PAGE_BYTES:
                    raise SbizAcademyApiError("API_PAGE_TOO_LARGE")
                return body
            if status >= 500 and attempt == 0:
                continue
            raise SbizAcademyApiError(_http_reason(status))
        raise SbizAcademyApiError("API_TRANSPORT_FAILED")


def _incomplete(
    artifacts: list[BundleArtifact], observed_at: datetime, page_count: int,
    raw_row_count: int, reason: str,
) -> CollectedSbizAcademyBundle:
    content = build_deterministic_bundle(
        source_id="place.sbiz-academy", endpoint_path=_PATH,
        artifacts=tuple(artifacts), temporal_value=observed_at,
        complete=False, reason_codes=(reason,),
    )
    return CollectedSbizAcademyBundle(
        content, observed_at, page_count, raw_row_count, False, (reason,)
    )


def _request(path: str, timeout: float, service_key: str) -> tuple[int, Mapping[str, str], bytes]:
    keyed_path = f"{path}&serviceKey={quote(service_key, safe='')}"
    connection = HTTPSConnection(_HOST, 443, timeout=timeout)
    try:
        connection.request("GET", keyed_path, headers={"Accept": "application/json"})
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read(_MAX_PAGE_BYTES + 1)
    finally:
        connection.close()


def _http_reason(status: int) -> str:
    if status in {401, 403}:
        return "API_AUTHENTICATION_FAILED"
    if status == 429:
        return "API_RATE_LIMITED"
    if 300 <= status < 400:
        return "API_REDIRECT_REJECTED"
    if status >= 500:
        return "API_SERVER_ERROR"
    return "API_BAD_REQUEST"

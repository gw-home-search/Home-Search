from __future__ import annotations

import math
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from http.client import HTTPSConnection
from urllib.parse import quote

from .academy_registry import _OFFICE_CODES, _page
from .bundle import BundleArtifact, build_deterministic_bundle
from .validation import RawPayloadError


_HOST = "open.neis.go.kr"
_PATH = "/hub/acaInsTiInfo"
_PAGE_SIZE = 1000
_MAX_PAGES_PER_OFFICE = 300
_MAX_PAGE_BYTES = 8 * 1024 * 1024
_MAX_BUNDLE_BYTES = 512 * 1024 * 1024
_SAFE_REASONS = frozenset(
    {
        "API_AUTHENTICATION_FAILED",
        "API_BAD_REQUEST",
        "API_PAGE_TOO_LARGE",
        "API_RATE_LIMITED",
        "API_REDIRECT_REJECTED",
        "API_SERVER_ERROR",
        "API_TRANSPORT_FAILED",
        "PROVIDER_PAGE_INVALID",
        "PROVIDER_TOTAL_COUNT_MISMATCH",
    }
)

Requester = Callable[[str, float, str], tuple[int, Mapping[str, str], bytes]]


class AcademyRegistryApiError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASONS:
            raise ValueError("invalid academy API failure reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedAcademyRegistryBundle:
    content: bytes
    observed_at: datetime
    page_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


class AcademyRegistryApiClient:
    def __init__(
        self,
        *,
        requester: Requester | None = None,
        timeout_seconds: float = 10,
    ) -> None:
        if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 30:
            raise ValueError("academy API timeout is outside the supported range")
        self._requester = requester or _request
        self._timeout = float(timeout_seconds)

    def collect(self, service_key: str, *, observed_at: datetime) -> CollectedAcademyRegistryBundle:
        key = service_key.strip()
        if not key or len(key) > 1024 or observed_at.tzinfo is None:
            raise ValueError("academy API collection configuration is invalid")
        artifacts: list[BundleArtifact] = []
        raw_row_count = 0
        total_bytes = 0
        for office in sorted(_OFFICE_CODES):
            expected_total: int | None = None
            page_index = 1
            total_pages = 1
            while page_index <= total_pages:
                path = (
                    f"{_PATH}?Type=json&pIndex={page_index}&pSize={_PAGE_SIZE}"
                    f"&ATPT_OFCDC_SC_CODE={office}"
                )
                try:
                    content = self._load_page(path, key)
                    total, rows = _page(content, "utf-8")
                except AcademyRegistryApiError as exception:
                    if not artifacts:
                        raise
                    return _incomplete(
                        artifacts, observed_at, raw_row_count, exception.reason_code
                    )
                except RawPayloadError:
                    if not artifacts:
                        raise AcademyRegistryApiError("PROVIDER_PAGE_INVALID") from None
                    return _incomplete(
                        artifacts, observed_at, raw_row_count, "PROVIDER_PAGE_INVALID"
                    )
                if expected_total is None:
                    expected_total = total
                    total_pages = max(1, math.ceil(total / _PAGE_SIZE))
                    if total_pages > _MAX_PAGES_PER_OFFICE:
                        return _incomplete(
                            artifacts, observed_at, raw_row_count, "PROVIDER_PAGE_INVALID"
                        )
                elif total != expected_total:
                    return _incomplete(
                        artifacts,
                        observed_at,
                        raw_row_count,
                        "PROVIDER_TOTAL_COUNT_MISMATCH",
                    )
                total_bytes += len(content)
                if total_bytes > _MAX_BUNDLE_BYTES:
                    return _incomplete(
                        artifacts, observed_at, raw_row_count, "API_PAGE_TOO_LARGE"
                    )
                artifacts.append(
                    BundleArtifact(
                        logical_name=f"{office.lower()}-page-{page_index:06d}",
                        extension="json",
                        media_type="application/json",
                        content=content,
                    )
                )
                raw_row_count += len(rows)
                page_index += 1
        content = build_deterministic_bundle(
            source_id="edu.academy-registry",
            endpoint_path=_PATH,
            artifacts=tuple(artifacts),
            temporal_value=observed_at,
        )
        return CollectedAcademyRegistryBundle(
            content, observed_at, len(artifacts), raw_row_count, True, ()
        )

    def _load_page(self, path: str, service_key: str) -> bytes:
        for attempt in range(2):
            try:
                status, _headers, body = self._requester(path, self._timeout, service_key)
            except (TimeoutError, socket.timeout, OSError):
                if attempt == 0:
                    continue
                raise AcademyRegistryApiError("API_TRANSPORT_FAILED") from None
            if status == 200:
                if len(body) > _MAX_PAGE_BYTES:
                    raise AcademyRegistryApiError("API_PAGE_TOO_LARGE")
                return body
            if 500 <= status < 600 and attempt == 0:
                continue
            raise AcademyRegistryApiError(_http_reason(status))
        raise AcademyRegistryApiError("API_TRANSPORT_FAILED")


def _incomplete(
    artifacts: list[BundleArtifact],
    observed_at: datetime,
    raw_row_count: int,
    reason_code: str,
) -> CollectedAcademyRegistryBundle:
    content = build_deterministic_bundle(
        source_id="edu.academy-registry",
        endpoint_path=_PATH,
        artifacts=tuple(artifacts),
        temporal_value=observed_at,
        complete=False,
        reason_codes=(reason_code,),
    )
    return CollectedAcademyRegistryBundle(
        content, observed_at, len(artifacts), raw_row_count, False, (reason_code,)
    )


def _request(path: str, timeout: float, service_key: str) -> tuple[int, Mapping[str, str], bytes]:
    separator = "&" if "?" in path else "?"
    keyed_path = f"{path}{separator}KEY={quote(service_key, safe='')}"
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
    if 500 <= status < 600:
        return "API_SERVER_ERROR"
    return "API_BAD_REQUEST"

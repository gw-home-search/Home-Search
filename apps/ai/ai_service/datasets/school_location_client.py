from __future__ import annotations

import math
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from http.client import HTTPSConnection
from urllib.parse import quote

from .school_location import (
    ACQUISITION_PATH,
    BUNDLE_FAILURE_REASON_CODES,
    PAGE_SIZE,
    _parse_page,
    build_bundle,
    extract_source_date,
)
from .validation import RawPayloadError


_HOST = "api.data.go.kr"
_MAX_PAGE_BYTES = 4 * 1024 * 1024
_MAX_PAGES = 128
_MAX_COLLECTED_PAGE_BYTES = 127 * 1024 * 1024
_SAFE_REASON_CODES = BUNDLE_FAILURE_REASON_CODES

Requester = Callable[[str, float], tuple[int, Mapping[str, str], bytes]]


class SchoolLocationApiError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASON_CODES:
            raise ValueError("invalid school API failure reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedSchoolBundle:
    content: bytes
    source_date: date | None
    page_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


class SchoolLocationApiClient:
    def __init__(
        self,
        *,
        requester: Requester | None = None,
        timeout_seconds: float = 20,
    ) -> None:
        if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 30:
            raise ValueError("school API timeout is outside the supported range")
        self._requester = requester or _request
        self._timeout_seconds = float(timeout_seconds)

    def collect(self, service_key: str) -> CollectedSchoolBundle:
        normalized_key = service_key.strip()
        if not normalized_key or len(normalized_key) > 1024:
            raise ValueError("school API service key configuration is invalid")
        encoded_key = quote(normalized_key, safe="")
        pages: list[bytes] = []
        total_count: int | None = None
        total_pages: int | None = None
        raw_row_count = 0
        collected_page_bytes = 0

        page_no = 1
        while total_pages is None or page_no <= total_pages:
            try:
                page = self._load_page(encoded_key, page_no)
            except SchoolLocationApiError as exception:
                if page_no == 1 and not pages:
                    raise
                return _incomplete_bundle(
                    pages, total_count, page_no, raw_row_count, exception.reason_code
                )
            try:
                rows, page_total = _parse_page(page, page_no)
            except RawPayloadError as exception:
                if exception.reason_code in _SAFE_REASON_CODES:
                    reason_code = exception.reason_code
                else:
                    reason_code = {
                        "API_PAGE_NUMBER_MISMATCH": "API_PAGINATION_INVALID",
                        "API_PAGE_SIZE_MISMATCH": "API_PAGINATION_INVALID",
                    }.get(exception.reason_code, "API_ENVELOPE_INVALID")
                if not pages:
                    raise SchoolLocationApiError(reason_code) from None
                return _incomplete_bundle(
                    pages, total_count, page_no, raw_row_count, reason_code
                )
            if collected_page_bytes + len(page) > _MAX_COLLECTED_PAGE_BYTES:
                return _incomplete_bundle(
                    pages,
                    total_count,
                    page_no,
                    raw_row_count,
                    "API_BUNDLE_TOO_LARGE",
                )
            if total_count is None:
                total_count = page_total
                total_pages = max(1, math.ceil(total_count / PAGE_SIZE))
                if total_pages > _MAX_PAGES:
                    pages.append(page)
                    raw_row_count += len(rows)
                    return _incomplete_bundle(
                        pages,
                        total_count,
                        page_no + 1,
                        raw_row_count,
                        "API_PAGINATION_INVALID",
                    )
            elif page_total != total_count:
                return _incomplete_bundle(
                    pages,
                    total_count,
                    page_no,
                    raw_row_count,
                    "API_PAGINATION_INVALID",
                )
            pages.append(page)
            collected_page_bytes += len(page)
            raw_row_count += len(rows)
            page_no += 1

        assert total_count is not None
        bundle = build_bundle(pages=pages, page_size=PAGE_SIZE, total_count=total_count)
        return CollectedSchoolBundle(
            content=bundle,
            source_date=extract_source_date(bundle),
            page_count=len(pages),
            raw_row_count=raw_row_count,
            complete=True,
            reason_codes=(),
        )

    def _load_page(self, encoded_key: str, page_no: int) -> bytes:
        path = (
            f"{ACQUISITION_PATH}?serviceKey={encoded_key}"
            f"&pageNo={page_no}&numOfRows={PAGE_SIZE}&type=json"
        )
        for attempt in range(2):
            try:
                status, headers, body = self._requester(path, self._timeout_seconds)
            except (TimeoutError, socket.timeout):
                if attempt == 0:
                    continue
                raise SchoolLocationApiError("API_TRANSPORT_FAILED") from None
            except OSError:
                raise SchoolLocationApiError("API_TRANSPORT_FAILED") from None
            if status == 200:
                if len(body) > _MAX_PAGE_BYTES:
                    raise SchoolLocationApiError("API_PAGE_TOO_LARGE")
                media_type = next(
                    (
                        str(value).split(";", 1)[0].strip().lower()
                        for key, value in headers.items()
                        if str(key).lower() == "content-type"
                    ),
                    "",
                )
                if media_type and media_type != "application/json":
                    raise SchoolLocationApiError("API_MEDIA_TYPE_INVALID")
                return body
            reason = _http_reason(status)
            if 500 <= status < 600 and attempt == 0:
                continue
            raise SchoolLocationApiError(reason)
        raise SchoolLocationApiError("API_TRANSPORT_FAILED")


def _incomplete_bundle(
    pages: list[bytes],
    total_count: int | None,
    failed_page: int,
    raw_row_count: int,
    reason_code: str,
) -> CollectedSchoolBundle:
    bundle = build_bundle(
        pages=pages,
        page_size=PAGE_SIZE,
        total_count=total_count,
        complete=False,
        failed_page=failed_page,
        reason_code=reason_code,
    )
    return CollectedSchoolBundle(
        content=bundle,
        source_date=extract_source_date(bundle),
        page_count=len(pages),
        raw_row_count=raw_row_count,
        complete=False,
        reason_codes=(reason_code,),
    )

def _request(path: str, timeout_seconds: float) -> tuple[int, Mapping[str, str], bytes]:
    if not path.startswith(f"{ACQUISITION_PATH}?"):
        raise SchoolLocationApiError("API_BAD_REQUEST")
    connection = HTTPSConnection(_HOST, 443, timeout=timeout_seconds)
    try:
        connection.request("GET", path, headers={"Accept": "application/json"})
        response = connection.getresponse()
        body = response.read(_MAX_PAGE_BYTES + 1)
        return response.status, dict(response.getheaders()), body
    finally:
        connection.close()


def _http_reason(status: int) -> str:
    if status in {401, 403}:
        return "API_AUTHENTICATION_FAILED"
    if status == 429:
        return "API_RATE_LIMITED"
    if 300 <= status < 400:
        return "API_REDIRECT_REJECTED"
    if status in {408, 413}:
        return "API_QUOTA_EXCEEDED" if status == 413 else "API_BAD_REQUEST"
    if 500 <= status < 600:
        return "API_SERVER_ERROR"
    return "API_BAD_REQUEST"

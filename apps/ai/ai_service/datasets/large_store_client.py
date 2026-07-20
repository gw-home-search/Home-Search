from __future__ import annotations

import json
import math
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from http.client import HTTPSConnection
from urllib.parse import quote

from .bundle import FileBundleArtifact, PreparedBundle, build_deterministic_bundle_file
from .secure_temp import SecureTempWorkspace


_HOST = "apis.data.go.kr"
_PATH = "/1741000/large_scale_retail_stores/info"
_PAGE_SIZE = 100
_MAX_PAGES = 100
_MAX_PAGE_BYTES = 4 * 1024 * 1024
_MAX_BUNDLE_BYTES = 256 * 1024 * 1024
_SAFE_REASONS = frozenset(
    {
        "API_AUTHENTICATION_FAILED", "API_BAD_REQUEST", "API_PAGE_TOO_LARGE",
        "API_RATE_LIMITED", "API_REDIRECT_REJECTED", "API_SERVER_ERROR",
        "API_TRANSPORT_FAILED", "PROVIDER_PAGE_INVALID",
    }
)

Requester = Callable[[str, float, str], tuple[int, Mapping[str, str], bytes]]


class LargeStoreApiError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASONS:
            raise ValueError("invalid large-store API reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedLargeStoreBundle:
    content: bytes
    observed_at: datetime
    page_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


@dataclass(frozen=True)
class PreparedLargeStoreBundle:
    prepared: PreparedBundle
    observed_at: datetime
    page_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


class LargeStoreApiClient:
    def __init__(
        self, *, requester: Requester | None = None, timeout_seconds: float = 10
    ) -> None:
        if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 30:
            raise ValueError("large-store API timeout is outside the supported range")
        self._requester = requester or _request
        self._timeout = float(timeout_seconds)

    def collect(
        self, service_key: str, *, observed_at: datetime
    ) -> CollectedLargeStoreBundle:
        with SecureTempWorkspace(required_free_bytes=_MAX_BUNDLE_BYTES * 2) as workspace:
            collected = self.collect_prepared(
                service_key, observed_at=observed_at, workspace=workspace
            )
            return CollectedLargeStoreBundle(
                collected.prepared.path.read_bytes(), observed_at,
                collected.page_count, collected.raw_row_count,
                collected.complete, collected.reason_codes,
            )

    def collect_prepared(
        self, service_key: str, *, observed_at: datetime,
        workspace: SecureTempWorkspace,
    ) -> PreparedLargeStoreBundle:
        key = service_key.strip()
        if not key or len(key) > 1024 or observed_at.tzinfo is None:
            raise ValueError("large-store API collection configuration is invalid")
        artifacts: list[FileBundleArtifact] = []
        total_count: int | None = None
        total_pages: int | None = None
        raw_row_count = 0
        total_bytes = 0
        page_number = 1
        while total_pages is None or page_number <= total_pages:
            path = (
                f"{_PATH}?pageNo={page_number}&numOfRows={_PAGE_SIZE}"
                "&returnType=JSON"
            )
            try:
                content = self._load(path, key)
                page_total, rows = _page(content, page_number)
            except LargeStoreApiError as exception:
                if not artifacts:
                    raise
                return _incomplete(
                    artifacts, observed_at, raw_row_count, exception.reason_code,
                    workspace,
                )
            if total_count is None:
                total_count = page_total
                total_pages = max(1, math.ceil(total_count / _PAGE_SIZE))
                if total_pages > _MAX_PAGES:
                    return _incomplete(
                        artifacts, observed_at, raw_row_count,
                        "PROVIDER_PAGE_INVALID", workspace,
                    )
            elif page_total != total_count:
                return _incomplete(
                    artifacts, observed_at, raw_row_count,
                    "PROVIDER_PAGE_INVALID", workspace,
                )
            total_bytes += len(content)
            if total_bytes > _MAX_BUNDLE_BYTES:
                return _incomplete(
                    artifacts, observed_at, raw_row_count,
                    "API_PAGE_TOO_LARGE", workspace,
                )
            artifact_path = workspace.create_file(f"page-{page_number:06d}.json")
            artifact_path.write_bytes(content)
            artifacts.append(
                FileBundleArtifact(
                    f"page-{page_number:06d}", "json", "application/json",
                    artifact_path,
                )
            )
            raw_row_count += len(rows)
            page_number += 1
        if total_count is None or raw_row_count != total_count:
            return _incomplete(
                artifacts, observed_at, raw_row_count,
                "PROVIDER_PAGE_INVALID", workspace,
            )
        prepared = build_deterministic_bundle_file(
            source_id="retail.large-store", endpoint_path=_PATH,
            artifacts=tuple(artifacts), temporal_value=observed_at,
            target=workspace.create_file("bundle.zip"),
        )
        return PreparedLargeStoreBundle(
            prepared, observed_at, len(artifacts), raw_row_count, True, ()
        )

    def _load(self, path: str, service_key: str) -> bytes:
        for attempt in range(2):
            try:
                status, _headers, body = self._requester(
                    path, self._timeout, service_key
                )
            except (OSError, TimeoutError, socket.timeout):
                if attempt == 0:
                    continue
                raise LargeStoreApiError("API_TRANSPORT_FAILED") from None
            if status == 200:
                if len(body) > _MAX_PAGE_BYTES:
                    raise LargeStoreApiError("API_PAGE_TOO_LARGE")
                return body
            if status >= 500 and attempt == 0:
                continue
            raise LargeStoreApiError(_http_reason(status))
        raise LargeStoreApiError("API_TRANSPORT_FAILED")


def _page(content: bytes, expected_page: int) -> tuple[int, list[dict[str, object]]]:
    try:
        value = json.loads(content)
        response = value["response"]
        header = response["header"]
        body = response["body"]
        rows = body["items"]["item"]
        total_count = body["totalCount"]
        if (
            header["resultCode"] != "00"
            or body["dataType"] != "JSON"
            or body["numOfRows"] != _PAGE_SIZE
            or body["pageNo"] != expected_page
            or isinstance(total_count, bool)
            or not isinstance(total_count, int)
            or total_count < 0
            or not isinstance(rows, list)
            or len(rows) > _PAGE_SIZE
            or not all(isinstance(row, dict) for row in rows)
        ):
            raise ValueError
        return total_count, rows
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        raise LargeStoreApiError("PROVIDER_PAGE_INVALID") from None


def _incomplete(
    artifacts: list[FileBundleArtifact], observed_at: datetime,
    raw_row_count: int, reason: str, workspace: SecureTempWorkspace,
) -> PreparedLargeStoreBundle:
    if not artifacts:
        raise LargeStoreApiError(reason)
    prepared = build_deterministic_bundle_file(
        source_id="retail.large-store", endpoint_path=_PATH,
        artifacts=tuple(artifacts), temporal_value=observed_at,
        target=workspace.create_file("bundle.zip"), complete=False,
        reason_codes=(reason,),
    )
    return PreparedLargeStoreBundle(
        prepared, observed_at, len(artifacts), raw_row_count, False, (reason,)
    )


def _request(
    path: str, timeout: float, service_key: str
) -> tuple[int, Mapping[str, str], bytes]:
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

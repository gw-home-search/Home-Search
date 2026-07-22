from __future__ import annotations

import math
import re
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from http.client import HTTPSConnection
from urllib.parse import quote
from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException

from .bundle import BundleArtifact, build_deterministic_bundle
from .childcare import ACQUISITION_PATH


_HOST = "api.childcare.go.kr"
_MAX_REGION_BYTES = 8 * 1024 * 1024
_MAX_BUNDLE_BYTES = 256 * 1024 * 1024
_MAX_REGIONS = 300
_SAFE_REASONS = frozenset(
    {
        "API_AUTHENTICATION_FAILED",
        "API_BAD_REQUEST",
        "API_PAGE_TOO_LARGE",
        "API_RATE_LIMITED",
        "API_REDIRECT_REJECTED",
        "API_SERVER_ERROR",
        "API_TRANSPORT_FAILED",
        "API_XML_INVALID",
    }
)
Requester = Callable[[str, float, str], tuple[int, Mapping[str, str], bytes]]


class ChildcareApiError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASONS:
            raise ValueError("invalid childcare API reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedChildcareBundle:
    content: bytes
    observed_at: datetime
    region_count: int
    raw_row_count: int
    complete: bool
    reason_codes: tuple[str, ...]


class ChildcareApiClient:
    def __init__(
        self,
        *,
        requester: Requester | None = None,
        timeout_seconds: float = 15,
    ) -> None:
        if not math.isfinite(timeout_seconds) or not 1 <= timeout_seconds <= 30:
            raise ValueError("childcare API timeout is outside the supported range")
        self._requester = requester or _request
        self._timeout = float(timeout_seconds)

    def collect(
        self,
        service_key: str,
        *,
        region_codes: tuple[str, ...],
        observed_at: datetime,
    ) -> CollectedChildcareBundle:
        key = service_key.strip()
        regions = tuple(sorted(set(region_codes)))
        if (
            not key
            or len(key) > 1024
            or observed_at.tzinfo is None
            or not regions
            or len(regions) > _MAX_REGIONS
            or len(regions) != len(region_codes)
            or any(re.fullmatch(r"[0-9]{5}", code) is None for code in regions)
        ):
            raise ValueError("childcare API collection configuration is invalid")
        artifacts: list[BundleArtifact] = []
        row_count = 0
        total_bytes = 0
        for region_code in regions:
            path = f"{ACQUISITION_PATH}?arcode={region_code}&stcode="
            try:
                content, media_type = self._load(path, key)
                count = _validate_response(content)
            except ChildcareApiError as exception:
                if not artifacts:
                    raise
                return _incomplete(
                    artifacts,
                    observed_at,
                    row_count,
                    exception.reason_code,
                )
            total_bytes += len(content)
            if total_bytes > _MAX_BUNDLE_BYTES:
                return _incomplete(
                    artifacts,
                    observed_at,
                    row_count,
                    "API_PAGE_TOO_LARGE",
                )
            artifacts.append(
                BundleArtifact(
                    f"region-{region_code}",
                    "xml",
                    media_type,
                    content,
                )
            )
            row_count += count
        bundle = build_deterministic_bundle(
            source_id="childcare.center",
            endpoint_path=ACQUISITION_PATH,
            artifacts=tuple(artifacts),
            temporal_value=observed_at,
        )
        return CollectedChildcareBundle(
            bundle,
            observed_at,
            len(artifacts),
            row_count,
            True,
            (),
        )

    def _load(self, path: str, key: str) -> tuple[bytes, str]:
        for attempt in range(2):
            try:
                status, headers, body = self._requester(path, self._timeout, key)
            except (OSError, TimeoutError, socket.timeout):
                if attempt == 0:
                    continue
                raise ChildcareApiError("API_TRANSPORT_FAILED") from None
            if status == 200:
                if len(body) > _MAX_REGION_BYTES:
                    raise ChildcareApiError("API_PAGE_TOO_LARGE")
                media_type = _media_type(headers)
                if media_type not in {"application/xml", "text/xml"}:
                    raise ChildcareApiError("API_XML_INVALID")
                return body, media_type
            if status >= 500 and attempt == 0:
                continue
            raise ChildcareApiError(_http_reason(status))
        raise ChildcareApiError("API_TRANSPORT_FAILED")


def _incomplete(
    artifacts: list[BundleArtifact],
    observed_at: datetime,
    row_count: int,
    reason: str,
) -> CollectedChildcareBundle:
    if not artifacts:
        raise ChildcareApiError(reason)
    bundle = build_deterministic_bundle(
        source_id="childcare.center",
        endpoint_path=ACQUISITION_PATH,
        artifacts=tuple(artifacts),
        temporal_value=observed_at,
        complete=False,
        reason_codes=(reason,),
    )
    return CollectedChildcareBundle(
        bundle,
        observed_at,
        len(artifacts),
        row_count,
        False,
        (reason,),
    )


def _validate_response(content: bytes) -> int:
    try:
        root = ElementTree.fromstring(content)
    except (DefusedXmlException, ElementTree.ParseError, ValueError):
        raise ChildcareApiError("API_XML_INVALID") from None
    text = " ".join(part.strip() for part in root.itertext() if part.strip())
    error_codes = {
        "INFO-100": "API_AUTHENTICATION_FAILED",
        "INFO-300": "API_RATE_LIMITED",
        "INFO-400": "API_AUTHENTICATION_FAILED",
        "ERROR-100": "API_BAD_REQUEST",
        "ERROR-200": "API_SERVER_ERROR",
    }
    for code, reason in error_codes.items():
        if code in text:
            raise ChildcareApiError(reason)
    if root.tag.casefold() != "response" or any(
        child.tag.casefold() != "item" for child in root
    ):
        raise ChildcareApiError("API_XML_INVALID")
    return len(root)


def _media_type(headers: Mapping[str, str]) -> str:
    return next(
        (
            str(value).split(";", 1)[0].strip().lower()
            for name, value in headers.items()
            if str(name).lower() == "content-type"
        ),
        "",
    )


def _request(path: str, timeout: float, service_key: str):
    keyed_path = f"{path}&key={quote(service_key, safe='')}"
    connection = HTTPSConnection(_HOST, 443, timeout=timeout)
    try:
        connection.request("GET", keyed_path, headers={"Accept": "application/xml"})
        response = connection.getresponse()
        return (
            response.status,
            dict(response.getheaders()),
            response.read(_MAX_REGION_BYTES + 1),
        )
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

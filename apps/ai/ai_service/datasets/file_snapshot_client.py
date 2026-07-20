from __future__ import annotations

import hashlib
import math
import os
import re
import socket
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from http.client import HTTPSConnection
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from .contracts import _is_safe_fixed_query


Requester = Callable[[str, float], tuple[int, Mapping[str, str], bytes]]

_SAFE_REASONS = frozenset(
    {
        "FILE_BAD_RESPONSE",
        "FILE_LENGTH_INVALID",
        "FILE_LENGTH_MISMATCH",
        "FILE_MEDIA_TYPE_INVALID",
        "FILE_REDIRECT_REJECTED",
        "FILE_TOO_LARGE",
        "FILE_TRANSPORT_FAILED",
        "SOURCE_DATE_UNVERIFIED",
    }
)
_DATE_PATTERN = re.compile(r"(?<![0-9])(20[0-9]{2})[-_]?([01][0-9])[-_]?([0-3][0-9])(?![0-9])")


class FileSnapshotError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        if reason_code not in _SAFE_REASONS:
            raise ValueError("invalid file snapshot reason")
        super().__init__()
        self.reason_code = reason_code


@dataclass(frozen=True)
class CollectedFileSnapshot:
    path: Path
    source_date: date
    byte_length: int
    checksum: str
    media_type: str
    endpoint_path: str


class FileSnapshotClient:
    def __init__(
        self,
        *,
        source_id: str,
        url: str,
        allowed_hosts: tuple[str, ...],
        allowed_path_prefixes: tuple[str, ...],
        media_types: tuple[str, ...],
        extension: str,
        maximum_bytes: int,
        allow_one_redirect: bool,
        fixed_query: str = "",
        requester: Requester | None = None,
        timeout_seconds: float = 20,
    ) -> None:
        if (
            not source_id.strip()
            or not allowed_hosts
            or not allowed_path_prefixes
            or not media_types
            or extension not in {"csv", "xlsx"}
            or not 1 <= maximum_bytes <= 512 * 1024 * 1024
            or not math.isfinite(timeout_seconds)
            or not 1 <= timeout_seconds <= 30
        ):
            raise ValueError("file snapshot configuration is invalid")
        self._hosts = allowed_hosts
        self._prefixes = allowed_path_prefixes
        self._validate_url(url)
        if fixed_query and not _is_safe_fixed_query(fixed_query):
            raise ValueError("file snapshot fixed query is invalid")
        if (
            extension == "xlsx"
            and not urlsplit(url).path.lower().endswith(".xlsx")
            and not fixed_query
        ):
            raise ValueError("rail collector requires a fixed release XLSX URL")
        self._source_id = source_id
        self._url = f"{url}?{fixed_query}" if fixed_query else url
        self._media_types = frozenset(value.lower() for value in media_types)
        self._extension = extension
        self._maximum_bytes = maximum_bytes
        self._allow_one_redirect = allow_one_redirect
        self._requester = requester
        self._timeout = float(timeout_seconds)

    def collect(self, *, target: Path) -> CollectedFileSnapshot:
        if target.exists() or target.is_symlink() or not target.parent.is_dir():
            raise ValueError("snapshot target must be a new regular file")
        current_url = self._url
        for hop in range(2):
            status, headers, byte_length = self._load(current_url, target)
            normalized_headers = {str(key).lower(): str(value) for key, value in headers.items()}
            if 300 <= status < 400:
                location = normalized_headers.get("location", "")
                if hop or not self._allow_one_redirect or not location:
                    raise FileSnapshotError("FILE_REDIRECT_REJECTED")
                redirected = urljoin(current_url, location)
                try:
                    self._validate_url(redirected)
                except ValueError:
                    raise FileSnapshotError("FILE_REDIRECT_REJECTED") from None
                current_url = redirected
                continue
            if status != 200:
                raise FileSnapshotError("FILE_BAD_RESPONSE")
            media_type = normalized_headers.get("content-type", "").split(";", 1)[0].strip().lower()
            if media_type not in self._media_types:
                _discard(target)
                raise FileSnapshotError("FILE_MEDIA_TYPE_INVALID")
            expected_length = _content_length(normalized_headers.get("content-length"))
            if expected_length > self._maximum_bytes:
                _discard(target)
                raise FileSnapshotError("FILE_TOO_LARGE")
            if expected_length != byte_length:
                _discard(target)
                raise FileSnapshotError("FILE_LENGTH_MISMATCH")
            source_date = _source_date(
                normalized_headers.get("content-disposition", ""), current_url
            )
            if source_date is None:
                _discard(target)
                raise FileSnapshotError("SOURCE_DATE_UNVERIFIED")
            return CollectedFileSnapshot(
                path=target,
                source_date=source_date,
                byte_length=byte_length,
                checksum=_sha256(target),
                media_type=media_type,
                endpoint_path=urlsplit(current_url).path,
            )
        raise FileSnapshotError("FILE_REDIRECT_REJECTED")

    def _load(self, url: str, target: Path) -> tuple[int, Mapping[str, str], int]:
        if self._requester is not None:
            try:
                status, headers, content = self._requester(url, self._timeout)
            except (OSError, TimeoutError, socket.timeout):
                raise FileSnapshotError("FILE_TRANSPORT_FAILED") from None
            if 300 <= status < 400:
                return status, headers, 0
            if len(content) > self._maximum_bytes:
                raise FileSnapshotError("FILE_TOO_LARGE")
            _write_owner_only(target, content)
            return status, headers, len(content)
        return _stream_request(url, self._timeout, target, self._maximum_bytes)

    def _validate_url(self, value: str) -> None:
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.hostname not in self._hosts
            or not any(parsed.path.startswith(prefix) for prefix in self._prefixes)
        ):
            raise ValueError("file URL is outside the configured allowlist")


def _stream_request(
    url: str, timeout: float, target: Path, maximum_bytes: int
) -> tuple[int, Mapping[str, str], int]:
    parsed = urlsplit(url)
    connection = HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=timeout)
    try:
        request_target = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        connection.request("GET", request_target, headers={"Accept": "*/*"})
        response = connection.getresponse()
        headers = dict(response.getheaders())
        if 300 <= response.status < 400:
            return response.status, headers, 0
        descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        byte_length = 0
        try:
            with os.fdopen(descriptor, "wb") as output:
                while chunk := response.read(1024 * 1024):
                    byte_length += len(chunk)
                    if byte_length > maximum_bytes:
                        raise FileSnapshotError("FILE_TOO_LARGE")
                    output.write(chunk)
        except Exception:
            _discard(target)
            raise
        return response.status, headers, byte_length
    except FileSnapshotError:
        raise
    except (OSError, TimeoutError, socket.timeout):
        _discard(target)
        raise FileSnapshotError("FILE_TRANSPORT_FAILED") from None
    finally:
        connection.close()


def _write_owner_only(path: Path, content: bytes) -> None:
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(content)


def _content_length(value: str | None) -> int:
    try:
        length = int(value) if value is not None else -1
    except ValueError:
        length = -1
    if length < 0:
        raise FileSnapshotError("FILE_LENGTH_INVALID")
    return length


def _source_date(content_disposition: str, url: str) -> date | None:
    candidates = (content_disposition, Path(urlsplit(url).path).name)
    for candidate in candidates:
        match = _DATE_PATTERN.search(candidate)
        if match:
            try:
                return date(*(int(value) for value in match.groups()))
            except ValueError:
                continue
    return None


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _discard(path: Path) -> None:
    if path.exists() and not path.is_symlink() and path.is_file():
        path.unlink()

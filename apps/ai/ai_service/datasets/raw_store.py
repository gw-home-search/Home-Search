from __future__ import annotations

import base64
import hashlib
import re
import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit


class RawObjectIntegrityError(RuntimeError):
    pass


@dataclass(frozen=True)
class StoredRawObject:
    storage_backend: str
    object_key: str
    object_version_id: str | None
    content_type: str
    byte_length: int
    checksum: str


class S3Client(Protocol):
    def put_object(self, **kwargs: object) -> dict[str, object]: ...

    def head_object(self, **kwargs: object) -> dict[str, object]: ...


class S3RawObjectStore:
    def __init__(self, *, client: S3Client, bucket: str, prefix: str) -> None:
        self._client = client
        self._bucket = _clean_segment(bucket, "S3 bucket")
        self._prefix = prefix.strip("/")
        if not self._prefix or any(part in {"", ".", ".."} for part in self._prefix.split("/")):
            raise ValueError("S3 raw prefix is invalid")

    def put_verified(
        self,
        *,
        source_id: str,
        checksum: str,
        content: bytes,
        content_type: str,
    ) -> StoredRawObject:
        _validate_upload_identity(source_id, checksum, content_type)
        actual_checksum = hashlib.sha256(content).hexdigest()
        if actual_checksum != checksum:
            raise RawObjectIntegrityError("raw content checksum does not match")
        return self._upload_and_verify(
            source_id=source_id,
            checksum=checksum,
            body=content,
            byte_length=len(content),
            content_type=content_type,
        )

    def put_verified_file(
        self,
        *,
        source_id: str,
        checksum: str,
        path: Path,
        byte_length: int,
        content_type: str,
    ) -> StoredRawObject:
        _validate_upload_identity(source_id, checksum, content_type)
        try:
            metadata = path.lstat()
        except OSError as exception:
            raise ValueError("raw artifact file is unavailable") from exception
        if not path.is_file() or path.is_symlink() or metadata.st_size != byte_length:
            raise ValueError("raw artifact file metadata is invalid")
        if metadata.st_mode & 0o077:
            raise ValueError("raw artifact file permissions must be owner-only")
        actual_checksum = _file_checksum(path)
        if actual_checksum != checksum:
            raise RawObjectIntegrityError("raw content checksum does not match")
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        try:
            with os.fdopen(descriptor, "rb") as body:
                return self._upload_and_verify(
                    source_id=source_id,
                    checksum=checksum,
                    body=body,
                    byte_length=byte_length,
                    content_type=content_type,
                )
        except Exception:
            try:
                os.close(descriptor)
            except OSError:
                pass
            raise

    def _upload_and_verify(
        self,
        *,
        source_id: str,
        checksum: str,
        body: object,
        byte_length: int,
        content_type: str,
    ) -> StoredRawObject:
        key = f"{self._prefix}/v1/{source_id}/{checksum[:2]}/{checksum}.zip"
        checksum_base64 = base64.b64encode(bytes.fromhex(checksum)).decode("ascii")
        try:
            response = self._client.put_object(
                Bucket=self._bucket,
                Key=key,
                Body=body,
                ContentLength=byte_length,
                ContentType=content_type,
                ChecksumAlgorithm="SHA256",
                ChecksumSHA256=checksum_base64,
                IfNoneMatch="*",
            )
        except Exception as exception:
            if not _is_precondition_failed(exception):
                raise
            response = {}
        version_id = _optional_text(response.get("VersionId"))
        head = self._client.head_object(
            Bucket=self._bucket,
            Key=key,
            **({"VersionId": version_id} if version_id else {}),
            ChecksumMode="ENABLED",
        )
        head_checksum = _optional_text(head.get("ChecksumSHA256"))
        head_length = head.get("ContentLength")
        head_version = _optional_text(head.get("VersionId"))
        if (
            head_checksum != checksum_base64
            or not isinstance(head_length, int)
            or head_length != byte_length
            or (version_id is not None and head_version != version_id)
        ):
            raise RawObjectIntegrityError("S3 raw object verification failed")
        return StoredRawObject(
            storage_backend="S3",
            object_key=key,
            object_version_id=head_version or version_id,
            content_type=content_type,
            byte_length=byte_length,
            checksum=checksum,
        )


def _validate_upload_identity(source_id: str, checksum: str, content_type: str) -> None:
    if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", source_id):
        raise ValueError("source ID is invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("raw checksum must be lowercase SHA-256")
    if not content_type.strip() or len(content_type) > 100:
        raise ValueError("raw content type is invalid")


def _file_checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def s3_raw_store_from_environment(environment: Mapping[str, str]) -> S3RawObjectStore:
    bucket = _required(environment, "HOME_AI_RAW_S3_BUCKET")
    prefix = _required(environment, "HOME_AI_RAW_S3_PREFIX")
    if prefix != "raw":
        raise ValueError("HOME_AI_RAW_S3_PREFIX must use the fixed raw prefix")
    region = _required(environment, "HOME_AI_RAW_S3_REGION")
    endpoint = environment.get("HOME_AI_RAW_S3_ENDPOINT", "").strip() or None
    if endpoint is not None:
        parsed = urlsplit(endpoint)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname not in {"minio", "localhost", "127.0.0.1"}
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("S3 endpoint override is allowed only for local MinIO")
    import boto3
    from botocore.config import Config

    client = boto3.client(
        "s3",
        region_name=region,
        endpoint_url=endpoint,
        config=Config(signature_version="s3v4", retries={"max_attempts": 3, "mode": "standard"}),
    )
    return S3RawObjectStore(client=client, bucket=bucket, prefix=prefix)  # type: ignore[arg-type]


def _clean_segment(value: str, label: str) -> str:
    normalized = value.strip()
    if not normalized or "/" in normalized or any(ord(character) < 33 for character in normalized):
        raise ValueError(f"{label} is invalid")
    return normalized


def _optional_text(value: object) -> str | None:
    return value if isinstance(value, str) and value else None


def _is_precondition_failed(exception: Exception) -> bool:
    response = getattr(exception, "response", None)
    if not isinstance(response, dict):
        return False
    metadata = response.get("ResponseMetadata")
    error = response.get("Error")
    return (
        isinstance(metadata, dict)
        and metadata.get("HTTPStatusCode") == 412
        and isinstance(error, dict)
        and error.get("Code") in {"PreconditionFailed", "412"}
    )


def _required(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    if not value or len(value) > 1024 or any(ord(character) < 32 for character in value):
        raise ValueError(f"{name} is required")
    return value

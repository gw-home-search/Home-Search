from __future__ import annotations

import base64
import hashlib
import re
from collections.abc import Mapping
from dataclasses import dataclass
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
        if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", source_id):
            raise ValueError("source ID is invalid")
        if not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError("raw checksum must be lowercase SHA-256")
        actual_checksum = hashlib.sha256(content).hexdigest()
        if actual_checksum != checksum:
            raise RawObjectIntegrityError("raw content checksum does not match")
        if not content_type.strip() or len(content_type) > 100:
            raise ValueError("raw content type is invalid")

        key = f"{self._prefix}/v1/{source_id}/{checksum[:2]}/{checksum}.zip"
        checksum_base64 = base64.b64encode(bytes.fromhex(checksum)).decode("ascii")
        try:
            response = self._client.put_object(
                Bucket=self._bucket,
                Key=key,
                Body=content,
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
            or head_length != len(content)
            or (version_id is not None and head_version != version_id)
        ):
            raise RawObjectIntegrityError("S3 raw object verification failed")
        return StoredRawObject(
            storage_backend="S3",
            object_key=key,
            object_version_id=head_version or version_id,
            content_type=content_type,
            byte_length=len(content),
            checksum=checksum,
        )


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

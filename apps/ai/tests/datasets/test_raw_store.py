from __future__ import annotations

from hashlib import sha256
from pathlib import Path

import pytest

from ai_service.datasets.raw_store import (
    RawObjectIntegrityError,
    S3RawObjectStore,
    s3_raw_store_from_environment,
)


class FakeS3Client:
    def __init__(self, *, bad_length: bool = False) -> None:
        self.bad_length = bad_length
        self.put_calls: list[dict[str, object]] = []

    def put_object(self, **kwargs):
        self.put_calls.append(kwargs)
        return {"VersionId": "version-1"}

    def head_object(self, **_kwargs):
        body = self.put_calls[0]["Body"]
        assert isinstance(body, bytes)
        return {
            "ContentLength": len(body) + (1 if self.bad_length else 0),
            "ChecksumSHA256": __import__("base64").b64encode(sha256(body).digest()).decode(),
            "VersionId": "version-1",
            "ContentType": "application/zip",
        }


class PreconditionFailed(Exception):
    response = {
        "Error": {"Code": "PreconditionFailed"},
        "ResponseMetadata": {"HTTPStatusCode": 412},
    }


class ExistingS3Client(FakeS3Client):
    def __init__(self, content: bytes) -> None:
        super().__init__()
        self.put_calls.append({"Body": content})

    def put_object(self, **_kwargs):
        raise PreconditionFailed()


def test_s3_store_uses_content_addressed_key_and_verifies_head() -> None:
    client = FakeS3Client()
    content = b"deterministic-bundle"
    checksum = sha256(content).hexdigest()
    store = S3RawObjectStore(client=client, bucket="private-raw", prefix="raw")

    stored = store.put_verified(
        source_id="edu.school-location",
        checksum=checksum,
        content=content,
        content_type="application/zip",
    )

    assert stored.object_key == f"raw/v1/edu.school-location/{checksum[:2]}/{checksum}.zip"
    assert stored.object_version_id == "version-1"
    assert client.put_calls[0]["ChecksumSHA256"]


class StreamingS3Client:
    def __init__(self) -> None:
        self.body_type: type[object] | None = None
        self.content = b""

    def put_object(self, **kwargs):
        body = kwargs["Body"]
        self.body_type = type(body)
        self.content = body.read()
        return {"VersionId": "stream-version"}

    def head_object(self, **_kwargs):
        return {
            "ContentLength": len(self.content),
            "ChecksumSHA256": __import__("base64").b64encode(
                sha256(self.content).digest()
            ).decode(),
            "VersionId": "stream-version",
        }


def test_s3_store_uploads_verified_file_without_materializing_bytes(tmp_path: Path) -> None:
    content = b"streamed-deterministic-bundle" * 1024
    artifact = tmp_path / "bundle.zip"
    artifact.write_bytes(content)
    artifact.chmod(0o600)
    client = StreamingS3Client()
    store = S3RawObjectStore(client=client, bucket="private-raw", prefix="raw")

    stored = store.put_verified_file(
        source_id="edu.school-location",
        checksum=sha256(content).hexdigest(),
        path=artifact,
        byte_length=len(content),
        content_type="application/zip",
    )

    assert client.body_type is not bytes
    assert client.content == content
    assert stored.byte_length == len(content)


def test_s3_store_rejects_unsafe_or_mismatched_file_before_upload(tmp_path: Path) -> None:
    store = S3RawObjectStore(client=StreamingS3Client(), bucket="private-raw", prefix="raw")
    missing = tmp_path / "missing.zip"
    with pytest.raises(ValueError, match="unavailable"):
        store.put_verified_file(
            source_id="fixture.source",
            checksum=sha256(b"").hexdigest(),
            path=missing,
            byte_length=0,
            content_type="application/zip",
        )

    artifact = tmp_path / "bundle.zip"
    artifact.write_bytes(b"bundle")
    artifact.chmod(0o644)
    with pytest.raises(ValueError, match="owner-only"):
        store.put_verified_file(
            source_id="fixture.source",
            checksum=sha256(b"bundle").hexdigest(),
            path=artifact,
            byte_length=len(b"bundle"),
            content_type="application/zip",
        )

    artifact.chmod(0o600)
    with pytest.raises(RawObjectIntegrityError, match="does not match"):
        store.put_verified_file(
            source_id="fixture.source",
            checksum=sha256(b"other").hexdigest(),
            path=artifact,
            byte_length=len(b"bundle"),
            content_type="application/zip",
        )


def test_s3_store_rejects_head_mismatch_before_metadata_can_be_registered() -> None:
    content = b"deterministic-bundle"
    checksum = sha256(content).hexdigest()
    store = S3RawObjectStore(
        client=FakeS3Client(bad_length=True), bucket="private-raw", prefix="raw"
    )

    with pytest.raises(RawObjectIntegrityError):
        store.put_verified(
            source_id="edu.school-location",
            checksum=checksum,
            content=content,
            content_type="application/zip",
        )


def test_s3_store_reuses_a_verified_existing_content_addressed_object() -> None:
    content = b"deterministic-bundle"
    checksum = sha256(content).hexdigest()
    store = S3RawObjectStore(
        client=ExistingS3Client(content), bucket="private-raw", prefix="raw"
    )

    stored = store.put_verified(
        source_id="edu.school-location",
        checksum=checksum,
        content=content,
        content_type="application/zip",
    )

    assert stored.checksum == checksum


def test_s3_store_environment_factory_allows_only_local_endpoint_override(monkeypatch) -> None:
    created: list[dict[str, object]] = []

    def fake_client(_service: str, **kwargs):
        created.append(kwargs)
        return FakeS3Client()

    monkeypatch.setattr("boto3.client", fake_client)
    environment = {
        "HOME_AI_RAW_S3_BUCKET": "private-raw",
        "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
        "HOME_AI_RAW_S3_ENDPOINT": "http://minio:9000",
    }

    store = s3_raw_store_from_environment(environment)

    assert isinstance(store, S3RawObjectStore)
    assert created[0]["endpoint_url"] == "http://minio:9000"

    environment["HOME_AI_RAW_S3_ENDPOINT"] = "https://attacker.invalid"
    with pytest.raises(ValueError, match="local MinIO"):
        s3_raw_store_from_environment(environment)

    environment["HOME_AI_RAW_S3_ENDPOINT"] = ""
    environment["HOME_AI_RAW_S3_PREFIX"] = "other"
    with pytest.raises(ValueError, match="raw prefix"):
        s3_raw_store_from_environment(environment)


def test_s3_store_rejects_invalid_configuration_and_content() -> None:
    with pytest.raises(ValueError):
        S3RawObjectStore(client=FakeS3Client(), bucket="bad/bucket", prefix="raw")
    with pytest.raises(ValueError):
        S3RawObjectStore(client=FakeS3Client(), bucket="private-raw", prefix="../raw")
    with pytest.raises(ValueError, match="HOME_AI_RAW_S3_BUCKET"):
        s3_raw_store_from_environment({})

    store = S3RawObjectStore(client=FakeS3Client(), bucket="private-raw", prefix="raw")
    with pytest.raises(ValueError, match="source ID"):
        store.put_verified(
            source_id="INVALID", checksum="0" * 64, content=b"", content_type="application/zip"
        )
    with pytest.raises(ValueError, match="raw checksum"):
        store.put_verified(
            source_id="fixture.source", checksum="invalid", content=b"", content_type="application/zip"
        )
    with pytest.raises(RawObjectIntegrityError, match="does not match"):
        store.put_verified(
            source_id="fixture.source",
            checksum="0" * 64,
            content=b"different",
            content_type="application/zip",
        )

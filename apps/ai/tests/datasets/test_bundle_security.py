from __future__ import annotations

import io
import json
import zipfile
from datetime import date

import pytest

from ai_service.datasets.bundle import (
    BundleArtifact,
    build_deterministic_bundle,
    read_deterministic_bundle,
)
from ai_service.datasets.validation import RawPayloadError


def _valid_bundle() -> bytes:
    return build_deterministic_bundle(
        source_id="fixture.source",
        endpoint_path="/fixture",
        artifacts=(BundleArtifact("page-000001", "json", "application/json", b"{}"),),
        temporal_value=date(2026, 1, 1),
    )


def _rewrite(
    source: bytes,
    *,
    manifest_mutator=None,
    renamed_artifact: str | None = None,
    artifact_content: bytes | None = None,
) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as original, zipfile.ZipFile(output, "w") as changed:
        manifest = json.loads(original.read("manifest.json"))
        if manifest_mutator is not None:
            manifest_mutator(manifest)
        changed.writestr("manifest.json", json.dumps(manifest).encode())
        original_name = original.namelist()[1]
        changed.writestr(
            renamed_artifact or original_name,
            original.read(original_name) if artifact_content is None else artifact_content,
        )
    return output.getvalue()


def test_bundle_builder_rejects_missing_or_unsafe_artifact_metadata() -> None:
    with pytest.raises(ValueError):
        build_deterministic_bundle(
            source_id="fixture.source",
            endpoint_path="relative",
            artifacts=(),
            temporal_value=None,
        )
    with pytest.raises(ValueError):
        build_deterministic_bundle(
            source_id="fixture.source",
            endpoint_path="/fixture",
            artifacts=(BundleArtifact("../unsafe", "json", "application/json", b"{}"),),
            temporal_value=None,
        )


def test_bundle_reader_rejects_size_and_entry_order_violations() -> None:
    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle(
            _valid_bundle(), expected_source_id="fixture.source", maximum_bytes=1
        )
    assert error.value.reason_code == "BUNDLE_TOO_LARGE"

    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle(
            _rewrite(_valid_bundle(), renamed_artifact="artifacts/0002.json"),
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
        )
    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


@pytest.mark.parametrize(
    "mutator",
    [
        lambda manifest: manifest.update(sourceId="other.source"),
        lambda manifest: manifest.update(complete=False),
        lambda manifest: manifest.update(artifacts=[]),
        lambda manifest: manifest["artifacts"][0].update(extra="forbidden"),
        lambda manifest: manifest.update(sourceDate="not-a-date"),
    ],
)
def test_bundle_reader_rejects_invalid_manifest_shapes(mutator) -> None:
    with pytest.raises(RawPayloadError):
        read_deterministic_bundle(
            _rewrite(_valid_bundle(), manifest_mutator=mutator),
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
        )


def test_bundle_reader_rejects_artifact_checksum_mismatch() -> None:
    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle(
            _rewrite(_valid_bundle(), artifact_content=b"changed"),
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
        )

    assert error.value.reason_code == "BUNDLE_CHECKSUM_MISMATCH"

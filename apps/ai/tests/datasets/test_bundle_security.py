from __future__ import annotations

import io
import hashlib
import json
import stat
import zipfile
from datetime import UTC, date, datetime

import pytest

from ai_service.datasets.bundle import (
    BundleArtifact,
    FileBundleArtifact,
    build_deterministic_bundle,
    build_deterministic_bundle_file,
    extract_single_artifact_bundle_file,
    read_deterministic_bundle,
    read_deterministic_bundle_file,
)
from ai_service.datasets.secure_temp import SecureTempWorkspace
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


def test_file_bundle_matches_bytes_bundle_without_loading_artifact(tmp_path) -> None:
    artifact_content = b'{"rows":[{"id":"one"}]}'
    expected = build_deterministic_bundle(
        source_id="fixture.source",
        endpoint_path="/fixture",
        artifacts=(
            BundleArtifact("page-000001", "json", "application/json", artifact_content),
        ),
        temporal_value=date(2026, 7, 20),
    )
    source = tmp_path / "page.json"
    source.write_bytes(artifact_content)

    with SecureTempWorkspace(required_free_bytes=len(artifact_content) * 2) as workspace:
        target = workspace.create_file("bundle.zip")
        prepared = build_deterministic_bundle_file(
            source_id="fixture.source",
            endpoint_path="/fixture",
            artifacts=(
                FileBundleArtifact(
                    "page-000001", "json", "application/json", source
                ),
            ),
            temporal_value=date(2026, 7, 20),
            target=target,
        )

        assert target.read_bytes() == expected
        assert prepared.byte_length == len(expected)
        assert prepared.checksum == hashlib.sha256(expected).hexdigest()
        assert stat.S_IMODE(target.stat().st_mode) == 0o600


def test_file_bundle_preserves_incomplete_safe_reason_metadata(tmp_path) -> None:
    artifact_content = b'{"partial":true}'
    expected = build_deterministic_bundle(
        source_id="fixture.source",
        endpoint_path="/fixture",
        artifacts=(
            BundleArtifact("page-000001", "json", "application/json", artifact_content),
        ),
        temporal_value=date(2026, 7, 20),
        complete=False,
        reason_codes=("API_SERVER_ERROR",),
    )
    source = tmp_path / "page.json"
    source.write_bytes(artifact_content)

    with SecureTempWorkspace(required_free_bytes=len(artifact_content) * 2) as workspace:
        prepared = build_deterministic_bundle_file(
            source_id="fixture.source",
            endpoint_path="/fixture",
            artifacts=(
                FileBundleArtifact(
                    "page-000001", "json", "application/json", source
                ),
            ),
            temporal_value=date(2026, 7, 20),
            target=workspace.create_file("bundle.zip"),
            complete=False,
            reason_codes=("API_SERVER_ERROR",),
        )

        assert prepared.path.read_bytes() == expected


def test_file_bundle_reader_verifies_artifact_bound_and_checksum_lazily(tmp_path) -> None:
    path = tmp_path / "bundle.zip"
    path.write_bytes(_valid_bundle())

    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle_file(
            path,
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
            maximum_artifact_bytes=1,
        )
    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"

    path.write_bytes(_rewrite(_valid_bundle(), artifact_content=b"[]"))
    bundle = read_deterministic_bundle_file(
        path,
        expected_source_id="fixture.source",
        maximum_bytes=10_000,
        maximum_artifact_bytes=100,
    )
    with pytest.raises(RawPayloadError) as error:
        tuple(bundle.artifacts)
    assert error.value.reason_code == "BUNDLE_CHECKSUM_MISMATCH"


@pytest.mark.parametrize(
    "manifest_mutator",
    [
        lambda manifest: manifest.update(sourceId="other.source"),
        lambda manifest: manifest["artifacts"][0].update(logicalName=1),
        lambda manifest: manifest.update(sourceDate="not-a-date"),
    ],
)
def test_file_bundle_reader_rejects_invalid_manifest_metadata(
    tmp_path, manifest_mutator
) -> None:
    path = tmp_path / "invalid-manifest.zip"
    path.write_bytes(_rewrite(_valid_bundle(), manifest_mutator=manifest_mutator))

    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle_file(
            path,
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
            maximum_artifact_bytes=100,
        )

    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


def test_file_bundle_reader_rejects_entry_order_and_post_inspection_mutation(
    tmp_path,
) -> None:
    path = tmp_path / "mutated-bundle.zip"
    path.write_bytes(
        _rewrite(_valid_bundle(), renamed_artifact="artifacts/0002.json")
    )
    with pytest.raises(RawPayloadError):
        read_deterministic_bundle_file(
            path,
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
            maximum_artifact_bytes=100,
        )

    path.write_bytes(_valid_bundle())
    bundle = read_deterministic_bundle_file(
        path,
        expected_source_id="fixture.source",
        maximum_bytes=10_000,
        maximum_artifact_bytes=100,
    )
    path.write_bytes(b"not-a-zip")
    with pytest.raises(RawPayloadError) as error:
        tuple(bundle.artifacts)
    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


def test_single_artifact_extractor_streams_large_artifact_and_verifies_checksum(
    tmp_path, monkeypatch
) -> None:
    artifact_content = b"a" * (9 * 1024 * 1024)
    artifact = tmp_path / "large.csv"
    artifact.write_bytes(artifact_content)
    bundle_path = tmp_path / "bundle.zip"
    bundle_path.touch(mode=0o600)
    build_deterministic_bundle_file(
        source_id="fixture.source",
        endpoint_path="/fixture",
        artifacts=(
            FileBundleArtifact("large", "csv", "text/csv", artifact),
        ),
        temporal_value=date(2026, 7, 20),
        target=bundle_path,
    )
    original_read = zipfile.ZipFile.read

    def reject_artifact_read(archive, name, *args, **kwargs):
        if archive.filename == str(bundle_path) and str(name).startswith("artifacts/"):
            raise AssertionError("artifact must be copied in chunks")
        return original_read(archive, name, *args, **kwargs)

    monkeypatch.setattr(zipfile.ZipFile, "read", reject_artifact_read)

    with SecureTempWorkspace(required_free_bytes=len(artifact_content)) as workspace:
        target = workspace.create_file("extracted.csv")
        extracted = extract_single_artifact_bundle_file(
            bundle_path,
            target,
            expected_source_id="fixture.source",
            maximum_bytes=16 * 1024 * 1024,
            maximum_artifact_bytes=12 * 1024 * 1024,
        )

        assert extracted.artifact_path == target
        assert target.stat().st_size == len(artifact_content)
        assert stat.S_IMODE(target.stat().st_mode) == 0o600


def test_single_artifact_extractor_rejects_checksum_mismatch_and_unsafe_target(
    tmp_path,
) -> None:
    bundle_path = tmp_path / "bundle.zip"
    bundle_path.write_bytes(_rewrite(_valid_bundle(), artifact_content=b"[]"))

    with SecureTempWorkspace(required_free_bytes=100) as workspace:
        target = workspace.create_file("artifact.json")
        with pytest.raises(RawPayloadError) as error:
            extract_single_artifact_bundle_file(
                bundle_path,
                target,
                expected_source_id="fixture.source",
                maximum_bytes=10_000,
                maximum_artifact_bytes=100,
            )
        assert error.value.reason_code == "BUNDLE_CHECKSUM_MISMATCH"

        unsafe_target = workspace.create_file("unsafe.json")
        unsafe_target.chmod(0o644)
        with pytest.raises(RawPayloadError) as error:
            extract_single_artifact_bundle_file(
                bundle_path,
                unsafe_target,
                expected_source_id="fixture.source",
                maximum_bytes=10_000,
                maximum_artifact_bytes=100,
            )
        assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


def test_single_artifact_extractor_maps_untrusted_bundle_failures_to_safe_reasons(
    tmp_path,
) -> None:
    bundle_path = tmp_path / "bundle.zip"

    with SecureTempWorkspace(required_free_bytes=100) as workspace:
        target = workspace.create_file("artifact.json")

        bundle_path.write_bytes(_valid_bundle())
        with pytest.raises(RawPayloadError) as error:
            extract_single_artifact_bundle_file(
                bundle_path,
                target,
                expected_source_id="fixture.source",
                maximum_bytes=1,
                maximum_artifact_bytes=1,
            )
        assert error.value.reason_code == "BUNDLE_TOO_LARGE"

        invalid_bundles = (
            _rewrite(_valid_bundle(), renamed_artifact="artifacts/0002.json"),
            _rewrite(
                _valid_bundle(),
                manifest_mutator=lambda manifest: manifest.update(sourceId="other.source"),
            ),
            _rewrite(
                _valid_bundle(),
                manifest_mutator=lambda manifest: manifest.update(
                    endpointPath="relative"
                ),
            ),
            _rewrite(
                _valid_bundle(),
                manifest_mutator=lambda manifest: manifest["artifacts"][0].update(
                    sha256="invalid"
                ),
            ),
            _rewrite(
                _valid_bundle(),
                manifest_mutator=lambda manifest: manifest["artifacts"][0].update(
                    logicalName="../unsafe"
                ),
            ),
            b"not-a-zip",
        )
        for invalid_bundle in invalid_bundles:
            bundle_path.write_bytes(invalid_bundle)
            with pytest.raises(RawPayloadError) as error:
                extract_single_artifact_bundle_file(
                    bundle_path,
                    target,
                    expected_source_id="fixture.source",
                    maximum_bytes=10_000,
                    maximum_artifact_bytes=100,
                )
            assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"


def test_single_artifact_extractor_preserves_observed_at_metadata(tmp_path) -> None:
    observed_at = datetime(2026, 7, 20, 12, 30, tzinfo=UTC)
    bundle_path = tmp_path / "observed-bundle.zip"
    bundle_path.write_bytes(
        build_deterministic_bundle(
            source_id="fixture.source",
            endpoint_path="/fixture",
            artifacts=(
                BundleArtifact("page-000001", "json", "application/json", b"{}"),
            ),
            temporal_value=observed_at,
        )
    )

    with SecureTempWorkspace(required_free_bytes=100) as workspace:
        extracted = extract_single_artifact_bundle_file(
            bundle_path,
            workspace.create_file("artifact.json"),
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
            maximum_artifact_bytes=100,
        )

        assert extracted.temporal_value == observed_at


def test_file_bundle_reader_rejects_nested_artifact_entry(tmp_path) -> None:
    path = tmp_path / "nested-entry.zip"
    path.write_bytes(
        _rewrite(_valid_bundle(), renamed_artifact="artifacts/0001.json/nested")
    )

    with pytest.raises(RawPayloadError) as error:
        read_deterministic_bundle_file(
            path,
            expected_source_id="fixture.source",
            maximum_bytes=10_000,
            maximum_artifact_bytes=100,
        )

    assert error.value.reason_code == "BUNDLE_MANIFEST_INVALID"

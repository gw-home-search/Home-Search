from __future__ import annotations

import hashlib
import io
import json
import re
import shutil
import zipfile
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

from .validation import RawPayloadError


_FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


@dataclass(frozen=True)
class BundleArtifact:
    logical_name: str
    extension: str
    media_type: str
    content: bytes


@dataclass(frozen=True)
class FileBundleArtifact:
    logical_name: str
    extension: str
    media_type: str
    path: Path


@dataclass(frozen=True)
class PreparedBundle:
    path: Path
    byte_length: int
    checksum: str


@dataclass(frozen=True)
class ReadArtifact:
    logical_name: str
    media_type: str
    content: bytes


@dataclass(frozen=True)
class ReadBundle:
    source_id: str
    complete: bool
    endpoint_path: str
    temporal_value: date | datetime | None
    artifacts: tuple[ReadArtifact, ...]


def build_deterministic_bundle(
    *,
    source_id: str,
    endpoint_path: str,
    artifacts: tuple[BundleArtifact, ...],
    temporal_value: date | datetime | None,
    complete: bool = True,
    reason_codes: tuple[str, ...] = (),
) -> bytes:
    if not artifacts or not endpoint_path.startswith("/"):
        raise ValueError("bundle artifacts and endpoint path are required")
    if complete and reason_codes:
        raise ValueError("complete bundle must not contain failure reasons")
    if not complete and (not reason_codes or any(not code.strip() for code in reason_codes)):
        raise ValueError("incomplete bundle requires safe reason codes")
    manifest: dict[str, object] = {
        "bundleSchemaVersion": 1,
        "sourceId": source_id,
        "complete": complete,
        "endpointPath": endpoint_path,
        "artifacts": [],
    }
    if temporal_value is not None:
        key = "observedAt" if isinstance(temporal_value, datetime) else "sourceDate"
        manifest[key] = temporal_value.isoformat()
    if reason_codes:
        manifest["reasonCodes"] = list(dict.fromkeys(reason_codes))
    entries: list[tuple[str, bytes]] = []
    for index, artifact in enumerate(artifacts, start=1):
        if (
            not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,99}", artifact.logical_name)
            or not re.fullmatch(r"[a-z0-9]{1,10}", artifact.extension)
            or not artifact.media_type.strip()
        ):
            raise ValueError("bundle artifact metadata is invalid")
        entry_name = f"artifacts/{index:04d}.{artifact.extension}"
        entries.append((entry_name, artifact.content))
        manifest["artifacts"].append(  # type: ignore[union-attr]
            {
                "logicalName": artifact.logical_name,
                "mediaType": artifact.media_type,
                "byteLength": len(artifact.content),
                "sha256": hashlib.sha256(artifact.content).hexdigest(),
            }
        )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        _write(
            archive,
            "manifest.json",
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(),
        )
        for name, content in entries:
            _write(archive, name, content)
    return output.getvalue()


def build_deterministic_bundle_file(
    *,
    source_id: str,
    endpoint_path: str,
    artifacts: tuple[FileBundleArtifact, ...],
    temporal_value: date | datetime | None,
    target: Path,
    complete: bool = True,
) -> PreparedBundle:
    if not artifacts or not endpoint_path.startswith("/"):
        raise ValueError("bundle artifacts and endpoint path are required")
    target_metadata = target.lstat()
    if target.is_symlink() or not target.is_file() or target_metadata.st_mode & 0o077:
        raise ValueError("bundle target must be an owner-only regular file")
    manifest: dict[str, object] = {
        "bundleSchemaVersion": 1,
        "sourceId": source_id,
        "complete": complete,
        "endpointPath": endpoint_path,
        "artifacts": [],
    }
    if temporal_value is not None:
        key = "observedAt" if isinstance(temporal_value, datetime) else "sourceDate"
        manifest[key] = temporal_value.isoformat()
    entries: list[tuple[str, Path]] = []
    for index, artifact in enumerate(artifacts, start=1):
        _validate_artifact_metadata(
            artifact.logical_name, artifact.extension, artifact.media_type
        )
        metadata = artifact.path.lstat()
        if artifact.path.is_symlink() or not artifact.path.is_file():
            raise ValueError("bundle artifact must be a regular file")
        checksum = _file_sha256(artifact.path)
        entry_name = f"artifacts/{index:04d}.{artifact.extension}"
        entries.append((entry_name, artifact.path))
        manifest["artifacts"].append(  # type: ignore[union-attr]
            {
                "logicalName": artifact.logical_name,
                "mediaType": artifact.media_type,
                "byteLength": metadata.st_size,
                "sha256": checksum,
            }
        )
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
        _write(
            archive,
            "manifest.json",
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(),
        )
        for name, path in entries:
            info = _zip_info(name)
            with path.open("rb") as source, archive.open(info, "w") as destination:
                shutil.copyfileobj(source, destination, length=1024 * 1024)
    return PreparedBundle(
        path=target,
        byte_length=target.stat().st_size,
        checksum=_file_sha256(target),
    )


def read_deterministic_bundle(
    content: bytes,
    *,
    expected_source_id: str,
    maximum_bytes: int,
) -> ReadBundle:
    if len(content) > maximum_bytes:
        raise RawPayloadError("bundle exceeds configured size", "BUNDLE_TOO_LARGE")
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if (
                not names
                or names[0] != "manifest.json"
                or len(names) != len(set(names))
                or any(info.is_dir() or info.file_size > maximum_bytes for info in infos)
                or sum(info.file_size for info in infos) > maximum_bytes
            ):
                raise RawPayloadError("bundle entries are invalid", "BUNDLE_MANIFEST_INVALID")
            expected_names = ["manifest.json"] + [
                f"artifacts/{index:04d}.{name.rsplit('.', 1)[-1]}"
                for index, name in enumerate(names[1:], start=1)
            ]
            if names != expected_names:
                raise RawPayloadError("bundle entry order is invalid", "BUNDLE_MANIFEST_INVALID")
            manifest = json.loads(archive.read("manifest.json"))
            if not isinstance(manifest, dict):
                raise ValueError
            if (
                manifest.get("bundleSchemaVersion") != 1
                or manifest.get("sourceId") != expected_source_id
                or manifest.get("complete") is not True
                or not isinstance(manifest.get("endpointPath"), str)
                or not isinstance(manifest.get("artifacts"), list)
                or len(manifest["artifacts"]) != len(names) - 1
            ):
                raise RawPayloadError("bundle manifest is invalid", "BUNDLE_MANIFEST_INVALID")
            artifacts: list[ReadArtifact] = []
            for metadata, name in zip(manifest["artifacts"], names[1:], strict=True):
                if not isinstance(metadata, dict) or set(metadata) != {
                    "logicalName", "mediaType", "byteLength", "sha256"
                }:
                    raise RawPayloadError("artifact metadata is invalid", "BUNDLE_MANIFEST_INVALID")
                artifact_content = archive.read(name)
                if (
                    metadata["byteLength"] != len(artifact_content)
                    or metadata["sha256"] != hashlib.sha256(artifact_content).hexdigest()
                ):
                    raise RawPayloadError("artifact checksum mismatch", "BUNDLE_CHECKSUM_MISMATCH")
                artifacts.append(
                    ReadArtifact(
                        logical_name=str(metadata["logicalName"]),
                        media_type=str(metadata["mediaType"]),
                        content=artifact_content,
                    )
                )
            temporal_value: date | datetime | None = None
            if isinstance(manifest.get("sourceDate"), str):
                temporal_value = date.fromisoformat(manifest["sourceDate"])
            elif isinstance(manifest.get("observedAt"), str):
                temporal_value = datetime.fromisoformat(manifest["observedAt"])
            return ReadBundle(
                source_id=expected_source_id,
                complete=True,
                endpoint_path=manifest["endpointPath"],
                temporal_value=temporal_value,
                artifacts=tuple(artifacts),
            )
    except RawPayloadError:
        raise
    except (KeyError, ValueError, json.JSONDecodeError, zipfile.BadZipFile):
        raise RawPayloadError("bundle is invalid", "BUNDLE_MANIFEST_INVALID") from None


def _write(archive: zipfile.ZipFile, name: str, content: bytes) -> None:
    info = _zip_info(name)
    archive.writestr(info, content, compress_type=zipfile.ZIP_STORED)


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=_FIXED_TIMESTAMP)
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o600 << 16
    return info


def _validate_artifact_metadata(logical_name: str, extension: str, media_type: str) -> None:
    if (
        not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,99}", logical_name)
        or not re.fullmatch(r"[a-z0-9]{1,10}", extension)
        or not media_type.strip()
    ):
        raise ValueError("bundle artifact metadata is invalid")


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

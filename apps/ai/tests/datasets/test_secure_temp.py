from __future__ import annotations

import stat
from types import SimpleNamespace

import pytest

from ai_service.datasets.secure_temp import (
    InsufficientEphemeralStorageError,
    SecureTempWorkspace,
)


def test_secure_workspace_uses_owner_only_permissions_and_cleans_up() -> None:
    with SecureTempWorkspace(required_free_bytes=1) as workspace:
        root = workspace.path
        artifact = workspace.create_file("bundle.zip")
        artifact.write_bytes(b"bundle")

        assert stat.S_IMODE(root.stat().st_mode) == 0o700
        assert stat.S_IMODE(artifact.stat().st_mode) == 0o600

    assert not root.exists()


def test_secure_workspace_rejects_invalid_state_and_storage(monkeypatch, tmp_path) -> None:
    with pytest.raises(ValueError, match="must not be negative"):
        SecureTempWorkspace(required_free_bytes=-1)

    workspace = SecureTempWorkspace(required_free_bytes=1, parent=tmp_path)
    with pytest.raises(RuntimeError, match="not open"):
        _ = workspace.path

    monkeypatch.setattr(
        "ai_service.datasets.secure_temp.shutil.disk_usage",
        lambda _path: SimpleNamespace(free=0),
    )
    with pytest.raises(InsufficientEphemeralStorageError):
        with workspace:
            raise AssertionError("unreachable")


def test_secure_workspace_rejects_unsafe_artifact_names() -> None:
    with SecureTempWorkspace(required_free_bytes=1) as workspace:
        for name in ("", "..", "nested/file", "bad name"):
            with pytest.raises(ValueError, match="name is invalid"):
                workspace.create_file(name)

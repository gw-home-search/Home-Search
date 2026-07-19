from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path


class InsufficientEphemeralStorageError(RuntimeError):
    pass


class SecureTempWorkspace:
    """Owner-only temporary workspace for untrusted provider artifacts."""

    def __init__(
        self,
        *,
        required_free_bytes: int,
        parent: Path | None = None,
    ) -> None:
        if required_free_bytes < 0:
            raise ValueError("required free bytes must not be negative")
        self._required_free_bytes = required_free_bytes
        self._parent = parent
        self._path: Path | None = None

    @property
    def path(self) -> Path:
        if self._path is None:
            raise RuntimeError("secure workspace is not open")
        return self._path

    def __enter__(self) -> SecureTempWorkspace:
        parent = self._parent or Path(tempfile.gettempdir())
        available = shutil.disk_usage(parent).free
        if available < self._required_free_bytes:
            raise InsufficientEphemeralStorageError(
                "ephemeral storage is below the required safe bound"
            )
        previous_umask = os.umask(0o077)
        try:
            self._path = Path(tempfile.mkdtemp(prefix="home-ai-reference-", dir=parent))
        finally:
            os.umask(previous_umask)
        self._path.chmod(0o700)
        return self

    def create_file(self, name: str) -> Path:
        if (
            not name
            or name in {".", ".."}
            or Path(name).name != name
            or any(ord(character) < 33 for character in name)
        ):
            raise ValueError("temporary artifact name is invalid")
        path = self.path / name
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        return path

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        path = self._path
        self._path = None
        if path is not None:
            shutil.rmtree(path)

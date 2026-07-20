from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from pathlib import Path
from typing import BinaryIO

from .checksum import canonical_json_bytes
from .models import StagedRow


class NormalizedRowSpool:
    """NDJSON spool that retains only accepted row hashes in memory."""

    def __init__(
        self,
        path: Path,
        *,
        semantic_hash_excluded_fields: frozenset[str] = frozenset(),
    ) -> None:
        metadata = path.lstat()
        if path.is_symlink() or not path.is_file() or metadata.st_mode & 0o077:
            raise ValueError("normalized spool must be an owner-only regular file")
        self.path = path
        self._stream: BinaryIO | None = path.open("wb", buffering=1024 * 1024)
        self._semantic_hash_excluded_fields = semantic_hash_excluded_fields
        self._accepted_row_hashes: list[str] = []
        self._row_count = 0

    @property
    def row_count(self) -> int:
        return self._row_count

    @property
    def accepted_row_hashes(self) -> tuple[str, ...]:
        return tuple(self._accepted_row_hashes)

    def append(self, row: StagedRow) -> None:
        if self._stream is None:
            raise RuntimeError("normalized spool is closed for writes")
        document = {
            "rowNumber": row.row_number,
            "rowData": row.row_data,
            "accepted": row.accepted,
            "rejectionCodes": list(row.rejection_codes),
            "sourceKey": row.source_key,
        }
        self._stream.write(canonical_json_bytes(document))
        self._stream.write(b"\n")
        self._row_count += 1
        if row.accepted:
            semantic_row = {
                key: value
                for key, value in row.row_data.items()
                if key not in self._semantic_hash_excluded_fields
            }
            self._accepted_row_hashes.append(
                hashlib.sha256(canonical_json_bytes(semantic_row)).hexdigest()
            )

    def iter_rows(self) -> Iterator[StagedRow]:
        self._finish_writes()
        with self.path.open("rb") as stream:
            for line in stream:
                value = json.loads(line)
                yield StagedRow(
                    row_number=value["rowNumber"],
                    row_data=value["rowData"],
                    accepted=value["accepted"],
                    rejection_codes=tuple(value["rejectionCodes"]),
                    source_key=value["sourceKey"],
                )

    def close(self) -> None:
        self._finish_writes()

    def _finish_writes(self) -> None:
        if self._stream is not None:
            self._stream.flush()
            self._stream.close()
            self._stream = None


def iter_spooled_rows(path: Path) -> Iterator[StagedRow]:
    with path.open("rb") as stream:
        for line in stream:
            value = json.loads(line)
            yield StagedRow(
                row_number=value["rowNumber"],
                row_data=value["rowData"],
                accepted=value["accepted"],
                rejection_codes=tuple(value["rejectionCodes"]),
                source_key=value["sourceKey"],
            )

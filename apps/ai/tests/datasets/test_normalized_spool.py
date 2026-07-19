from __future__ import annotations

import pytest

from ai_service.datasets.models import StagedRow
from ai_service.datasets.normalized_spool import NormalizedRowSpool
from ai_service.datasets.secure_temp import SecureTempWorkspace


def test_normalized_spool_round_trips_rows_without_retaining_row_dicts() -> None:
    with SecureTempWorkspace(required_free_bytes=1) as workspace:
        spool = NormalizedRowSpool(workspace.create_file("normalized.ndjson"))
        for index in range(10_000):
            spool.append(
                StagedRow(
                    row_number=index + 1,
                    row_data={"id": f"row-{index}", "value": index},
                    accepted=True,
                    rejection_codes=(),
                    source_key=f"row-{index}",
                )
            )

        assert spool.row_count == 10_000
        assert len(spool.accepted_row_hashes) == 10_000
        assert next(spool.iter_rows()).row_data == {"id": "row-0", "value": 0}
        with pytest.raises(RuntimeError, match="closed"):
            spool.append(
                StagedRow(10_001, {"id": "late"}, True, (), "late")
            )


def test_normalized_spool_rejects_non_private_file(tmp_path) -> None:
    path = tmp_path / "spool.ndjson"
    path.touch(mode=0o644)
    path.chmod(0o644)

    with pytest.raises(ValueError, match="owner-only"):
        NormalizedRowSpool(path)

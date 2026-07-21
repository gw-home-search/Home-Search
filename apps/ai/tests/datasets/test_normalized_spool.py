from __future__ import annotations

import tracemalloc
from datetime import UTC, date, datetime

import pytest

from ai_service.datasets.models import StagedRow
from ai_service.datasets.normalized_spool import NormalizedRowSpool
from ai_service.datasets.secure_temp import SecureTempWorkspace
from ai_service.datasets.validation import validate_rows
from tests.datasets.test_dataset_lifecycle import source_contract


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


def test_semantic_hash_can_exclude_observation_time_without_changing_spooled_row(
    tmp_path,
) -> None:
    paths = [tmp_path / "first.ndjson", tmp_path / "second.ndjson"]
    for path in paths:
        path.touch(mode=0o600)
        path.chmod(0o600)

    first = NormalizedRowSpool(
        paths[0], semantic_hash_excluded_fields=frozenset({"observed_at"})
    )
    second = NormalizedRowSpool(
        paths[1], semantic_hash_excluded_fields=frozenset({"observed_at"})
    )
    first.append(
        StagedRow(
            1,
            {"id": "academy-1", "observed_at": "2026-07-20T01:00:00Z"},
            True,
            (),
            "academy-1",
        )
    )
    second.append(
        StagedRow(
            1,
            {"id": "academy-1", "observed_at": "2026-07-20T23:00:00Z"},
            True,
            (),
            "academy-1",
        )
    )

    assert first.accepted_row_hashes == second.accepted_row_hashes
    assert next(first.iter_rows()).row_data["observed_at"] == "2026-07-20T01:00:00Z"
    assert next(second.iter_rows()).row_data["observed_at"] == "2026-07-20T23:00:00Z"


def test_300k_normalized_candidates_spool_with_bounded_memory() -> None:
    row_count = 300_000
    contract = source_contract(
        expected_min_rows=row_count, expected_max_rows=row_count
    )

    def rows():
        for index in range(row_count):
            yield {
                "station_id": f"station-{index}", "name": "Fixture Station",
                "latitude": 37.5, "longitude": 127.0,
            }

    with SecureTempWorkspace(required_free_bytes=128 * 1024 * 1024) as workspace:
        spool = NormalizedRowSpool(workspace.create_file("normalized.ndjson"))
        tracemalloc.start()
        outcome = validate_rows(
            contract, rows(), None, source_date=date(2026, 7, 15),
            collected_at=datetime(2026, 7, 16, tzinfo=UTC),
            row_sink=spool.append, retain_staged_rows=False,
        )
        _current, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        spool.close()

        assert outcome.accepted_row_count == row_count
        assert outcome.staged_rows == ()
        assert spool.row_count == row_count
        assert peak < 256 * 1024 * 1024

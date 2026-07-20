from __future__ import annotations

import pytest
from datetime import UTC, date, datetime
from uuid import UUID

from ai_service.datasets import reference_inspection
from ai_service.datasets.reference_inspection import (
    ReferenceInspectionConfigurationError,
    _runtime_dsn,
    audit_run,
    status_run,
)


def test_inspection_requires_dedicated_runtime_database_and_role() -> None:
    dsn = _runtime_dsn(
        {"HOME_AI_REFERENCE_RUNTIME_DSN": "dbname=home_search_ai user=home_search_ai_runtime"}
    )
    assert "home_search_ai_runtime" in dsn


@pytest.mark.parametrize(
    "dsn",
    [
        "",
        "dbname=wrong user=home_search_ai_runtime",
        "dbname=home_search_ai user=home_search_ai_importer",
        "not a dsn",
    ],
)
def test_inspection_rejects_missing_or_privileged_dsn(dsn: str) -> None:
    with pytest.raises(ReferenceInspectionConfigurationError):
        _runtime_dsn({"HOME_AI_REFERENCE_RUNTIME_DSN": dsn})


class _Cursor:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class _Connection:
    def __init__(self, rows):
        self.rows = rows
        self.info = type("Info", (), {"dbname": "home_search_ai", "user": "home_search_ai_runtime"})()
        self.query = ""
        self.parameters = ()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, query, parameters):
        self.query = query
        self.parameters = parameters
        return _Cursor(self.rows)


def _environment():
    return {"HOME_AI_REFERENCE_RUNTIME_DSN": "dbname=home_search_ai user=home_search_ai_runtime"}


def test_status_reports_active_and_partial_sources_without_sensitive_fields(monkeypatch, capsys) -> None:
    connection = _Connection(
        [
            {
                "source_id": "edu.academy-registry", "dataset_version": "v1",
                "temporal_basis": "OBSERVED_AT", "source_date": None,
                "observed_at": datetime(2026, 7, 20, tzinfo=UTC),
                "published_at": datetime(2026, 7, 20, tzinfo=UTC),
            },
            {
                "source_id": "transport.rail-station", "dataset_version": None,
                "temporal_basis": None, "source_date": None,
                "observed_at": None, "published_at": None,
            },
        ]
    )
    monkeypatch.setattr(reference_inspection.psycopg, "connect", lambda *_args, **_kwargs: connection)

    assert status_run([], _environment()) == 0
    output = capsys.readouterr().out
    assert "상태: Pass" in output
    assert "상태: Partial" in output
    assert "dataAsOf: 2026-07-20T00:00:00+00:00" in output
    assert "DSN" not in output
    assert "%s::text IS NULL" in connection.query


def test_status_unknown_source_is_safe_failure(monkeypatch, capsys) -> None:
    monkeypatch.setattr(
        reference_inspection.psycopg, "connect",
        lambda *_args, **_kwargs: _Connection([]),
    )
    assert status_run(["--source", "missing.source"], _environment()) == 1
    assert "reasonCodes: SOURCE_NOT_FOUND" in capsys.readouterr().out


def test_audit_is_bounded_and_reports_only_safe_evidence(monkeypatch, capsys) -> None:
    connection = _Connection(
        [{
            "acquisition_id": UUID("00000000-0000-0000-0000-000000000001"),
            "status": "PUBLISHED", "raw_row_count": 10,
            "accepted_row_count": 10, "rejected_row_count": 0,
            "collected_at": datetime(2026, 7, 20, tzinfo=UTC),
            "reason_codes": [],
        }]
    )
    monkeypatch.setattr(reference_inspection.psycopg, "connect", lambda *_args, **_kwargs: connection)

    assert audit_run(["--source", "edu.academy-registry", "--limit", "1"], _environment()) == 0
    output = capsys.readouterr().out
    assert "상태: PUBLISHED" in output
    assert "rawRowCount: 10" in output
    assert connection.parameters == ("edu.academy-registry", 1)

    with pytest.raises(ReferenceInspectionConfigurationError):
        audit_run(["--source", "edu.academy-registry", "--limit", "101"], _environment())


def test_audit_reports_pre_acquisition_failure_without_none_identifier(
    monkeypatch, capsys
) -> None:
    connection = _Connection(
        [{
            "acquisition_id": None,
            "status": "FAIL",
            "raw_row_count": 0,
            "accepted_row_count": 0,
            "rejected_row_count": 0,
            "collected_at": datetime(2026, 7, 20, tzinfo=UTC),
            "reason_codes": ["API_SERVER_ERROR"],
        }]
    )
    monkeypatch.setattr(
        reference_inspection.psycopg,
        "connect",
        lambda *_args, **_kwargs: connection,
    )

    assert audit_run(["--source", "edu.academy-registry"], _environment()) == 0
    output = capsys.readouterr().out
    assert "상태: FAIL" in output
    assert "reasonCodes: API_SERVER_ERROR" in output
    assert "acquisitionId: \n" in output
    assert "acquisitionId: None" not in output


def test_cli_mains_map_success_and_configuration_failure_to_bounded_exit(monkeypatch, capsys) -> None:
    monkeypatch.setattr(reference_inspection, "status_run", lambda *_args: 0)
    with pytest.raises(SystemExit) as status_exit:
        reference_inspection.status_main()
    assert status_exit.value.code == 0

    monkeypatch.setattr(
        reference_inspection, "audit_run",
        lambda *_args: (_ for _ in ()).throw(ReferenceInspectionConfigurationError()),
    )
    with pytest.raises(SystemExit) as audit_exit:
        reference_inspection.audit_main()
    assert audit_exit.value.code == 2
    assert "INSPECTION_UNAVAILABLE" in capsys.readouterr().out

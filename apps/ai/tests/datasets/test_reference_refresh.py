from __future__ import annotations

from ai_service.datasets.reference_refresh import run
from ai_service.datasets.school_location_ingest import SchoolLocationIngestReport
from tests.datasets.test_school_location_ingest import _result


def test_pending_source_fails_configuration_before_collector(capsys) -> None:
    called = False

    def school_refresh(_environment):
        nonlocal called
        called = True
        raise AssertionError

    exit_code = run(
        ["--source", "retail.large-store"], {}, school_refresh=school_refresh
    )

    output = capsys.readouterr().out
    assert exit_code == 2
    assert called is False
    assert "sourceId: retail.large-store" in output
    assert "reasonCodes: CONFIGURATION_INVALID" in output


def test_school_source_uses_wrapper_and_prints_bounded_report(capsys) -> None:
    exit_code = run(
        ["--source", "edu.school-location"],
        {},
        school_refresh=lambda _environment: SchoolLocationIngestReport(
            result=_result(), page_count=17, raw_row_count=17
        ),
    )

    output = capsys.readouterr().out
    assert exit_code == 0
    assert "상태: Pass" in output
    assert "temporalBasis: SOURCE_DATE" in output
    assert "dataAsOf: 2026-03-20" in output


def test_priority_family_continues_after_each_unready_source(capsys) -> None:
    exit_code = run(
        ["--family", "priority"],
        {},
        school_refresh=lambda _environment: SchoolLocationIngestReport(
            result=_result(), page_count=17, raw_row_count=17
        ),
    )

    output = capsys.readouterr().out
    assert exit_code == 2
    assert output.count("sourceId:") == 5
    assert "sourceId: edu.academy-registry" in output
    assert "sourceId: place.sbiz-academy" in output
    assert "sourceId: transport.rail-station" in output


def test_school_refresher_receives_only_its_required_secret_and_shared_runtime(capsys) -> None:
    received = {}

    def school_refresh(environment):
        received.update(environment)
        return SchoolLocationIngestReport(result=_result(), page_count=17, raw_row_count=17)

    exit_code = run(
        ["--source", "edu.school-location"],
        {
            "HOME_AI_DATA_GO_KR_SERVICE_KEY": "school-key",
            "HOME_AI_NEIS_SERVICE_KEY": "must-not-leak",
            "UNRELATED_SECRET": "must-not-leak",
            "HOME_AI_IMPORTER_DSN": "fixture-dsn",
        },
        school_refresh=school_refresh,
    )

    assert exit_code == 0
    assert received == {
        "HOME_AI_DATA_GO_KR_SERVICE_KEY": "school-key",
        "HOME_AI_IMPORTER_DSN": "fixture-dsn",
    }
    assert "상태: Pass" in capsys.readouterr().out

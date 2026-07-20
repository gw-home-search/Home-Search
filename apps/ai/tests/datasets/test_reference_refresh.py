from __future__ import annotations

from dataclasses import replace

from ai_service.datasets import reference_refresh
from ai_service.datasets.reference_refresh import _SOURCE_DEFINITIONS, run
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


def test_static_source_composition_matches_fixed_priority_order() -> None:
    assert tuple(definition.source_id for definition in _SOURCE_DEFINITIONS) == (
        "edu.school-location",
        "edu.academy-registry",
        "place.sbiz-academy",
        "retail.large-store",
        "transport.rail-station",
    )


def test_retail_source_receives_data_go_kr_key_and_not_neis_key() -> None:
    assert reference_refresh._source_environment(
        "retail.large-store",
        {
            "HOME_AI_DATA_GO_KR_SERVICE_KEY": "data-key",
            "HOME_AI_NEIS_SERVICE_KEY": "must-not-leak",
            "HOME_AI_IMPORTER_DSN": "dsn",
        },
    ) == {
        "HOME_AI_DATA_GO_KR_SERVICE_KEY": "data-key",
        "HOME_AI_IMPORTER_DSN": "dsn",
    }


def test_approved_neis_source_uses_static_override_and_only_neis_secret(monkeypatch, capsys) -> None:
    class Catalog:
        source_ids = ("edu.academy-registry",)

        def approved(self, source_id):
            assert source_id == "edu.academy-registry"
            return object()

    monkeypatch.setattr(reference_refresh, "load_reference_source_catalog", lambda _path: Catalog())
    received = {}

    def refresh(environment):
        received.update(environment)
        return SchoolLocationIngestReport(
            result=replace(
                _result(), source_id="edu.academy-registry", source_date=None,
                observed_at=_result().collected_at, temporal_basis="OBSERVED_AT",
            ),
            page_count=17, raw_row_count=17,
        )

    exit_code = run(
        ["--source", "edu.academy-registry"],
        {
            "HOME_AI_NEIS_SERVICE_KEY": "neis-key",
            "HOME_AI_DATA_GO_KR_SERVICE_KEY": "must-not-leak",
            "HOME_AI_IMPORTER_DSN": "dsn",
        },
        refreshers={"edu.academy-registry": refresh},
    )

    assert exit_code == 0
    assert received == {
        "HOME_AI_NEIS_SERVICE_KEY": "neis-key", "HOME_AI_IMPORTER_DSN": "dsn"
    }
    assert "sourceId: edu.academy-registry" in capsys.readouterr().out


def test_approved_source_runtime_failure_is_safe_and_non_configuration(monkeypatch, capsys) -> None:
    class Catalog:
        source_ids = ("retail.large-store",)

        def approved(self, _source_id):
            return object()

    monkeypatch.setattr(reference_refresh, "load_reference_source_catalog", lambda _path: Catalog())
    exit_code = run(
        ["--source", "retail.large-store"], {},
        refreshers={
            "retail.large-store": lambda _environment: (_ for _ in ()).throw(
                RuntimeError("provider secret body")
            )
        },
    )
    assert exit_code == 1
    output = capsys.readouterr().out
    assert "reasonCodes: REFRESH_FAILED" in output
    assert "provider secret body" not in output

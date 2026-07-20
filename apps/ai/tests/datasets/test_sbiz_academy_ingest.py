from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, date, datetime
from hashlib import sha256
import json
from pathlib import Path
from uuid import UUID

import pytest

from ai_service.datasets import sbiz_academy_ingest
from ai_service.datasets.contracts import (
    ReferenceSourceCatalog,
    load_reference_source_catalog,
)
from ai_service.datasets.models import AcquisitionRecord, LifecycleResult
from ai_service.datasets.raw_store import StoredRawObject
from ai_service.datasets.bundle import PreparedBundle
from ai_service.datasets.sbiz_academy_client import (
    PreparedSbizAcademyBundle, SbizAcademyApiError,
)
from tests.datasets.test_sbiz_academy_adapter import TAXONOMY, _bundle, _taxonomy


def _catalog():
    source = load_reference_source_catalog(sbiz_academy_ingest._CONFIG_PATH).get(
        "place.sbiz-academy"
    )
    source = replace(
        source,
        license=replace(
            source.license, status="APPROVED", terms_fingerprint="c" * 64,
            reviewed_on=date(2026, 7, 20), reviewed_by="test",
            attribution_text="출처: Sbiz", raw_private_storage_allowed=True,
            internal_derivative_allowed=True,
        ),
    )
    return type("Catalog", (), {"approved": lambda self, _source_id: source})()


def _environment():
    return {
        "HOME_AI_IMPORTER_DSN": "postgresql://home_search_ai_importer@db/home_search_ai",
        "HOME_AI_DATA_GO_KR_SERVICE_KEY": "key",
        "HOME_AI_RAW_S3_BUCKET": "private", "HOME_AI_RAW_S3_PREFIX": "raw",
        "HOME_AI_RAW_S3_REGION": "ap-northeast-2",
    }


def _result():
    return LifecycleResult(
        status="Pass", source_id="place.sbiz-academy",
        acquisition_id=UUID(int=1), publication_id=UUID(int=2),
        dataset_version="2026-07-20-abc", checksum="0" * 64,
        source_date=None, observed_at=datetime(2026, 7, 20, tzinfo=UTC),
        temporal_basis="OBSERVED_AT", collected_at=datetime(2026, 7, 20, tzinfo=UTC),
        raw_row_count=1, accepted_row_count=1, rejected_row_count=0,
        issue_codes=(), idempotent=True,
    )


class Repository:
    def __init__(self): self.finished = None; self.closed = False
    def start_refresh_run(self, **_kwargs): return UUID(int=20)
    def finish_refresh_run(self, **kwargs): self.finished = kwargs
    def source_lock(self, _source_id): return nullcontext()
    def register_source_contract(self, *_args): return UUID(int=10)
    def acquire_stored_raw(self, *_args): return AcquisitionRecord(UUID(int=1), False)
    def result(self, _id, *, idempotent): assert idempotent; return _result()
    def close(self): self.closed = True


class Client:
    def collect_prepared(self, key, *, observed_at, workspace):
        assert key == "key"
        content = _bundle()
        path = workspace.create_file("fixture-bundle.zip")
        path.write_bytes(content)
        return PreparedSbizAcademyBundle(
            PreparedBundle(path, len(content), sha256(content).hexdigest()),
            observed_at, 1, 1, True, (),
        )


class RawStore:
    def put_verified(self, **_kwargs):
        raise AssertionError("API refresh must use file upload")

    def put_verified_file(self, *, source_id, checksum, path, byte_length, content_type):
        return StoredRawObject(
            "S3", f"raw/{checksum}.zip", "v1", content_type, byte_length, checksum
        )


def test_sbiz_refresh_uses_tracked_taxonomy_and_static_orchestration(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(sbiz_academy_ingest, "load_reference_source_catalog", lambda _path: _catalog())
    report = sbiz_academy_ingest.ingest_from_environment(
        _environment(), repository_factory=lambda _dsn: repository,
        client_factory=lambda _taxonomy, _artifacts: Client(),
        raw_store_factory=lambda _environment: RawStore(),
        taxonomy_loader=lambda: (_taxonomy(), TAXONOMY),
        today=lambda: date(2026, 7, 20),
        clock=lambda: datetime(2026, 7, 20, tzinfo=UTC),
    )
    assert report.result.status == "Pass"
    assert repository.finished["status"] == "PASS"
    assert repository.closed is True


def test_sbiz_first_page_failure_records_safe_reason(monkeypatch) -> None:
    repository = Repository()
    monkeypatch.setattr(sbiz_academy_ingest, "load_reference_source_catalog", lambda _path: _catalog())

    class Failed:
        def collect_prepared(self, _key, *, observed_at, workspace):
            raise SbizAcademyApiError("API_TRANSPORT_FAILED")

    with pytest.raises(SbizAcademyApiError):
        sbiz_academy_ingest.ingest_from_environment(
            _environment(), repository_factory=lambda _dsn: repository,
            client_factory=lambda *_args: Failed(),
            raw_store_factory=lambda _environment: RawStore(),
            taxonomy_loader=lambda: (_taxonomy(), TAXONOMY),
            today=lambda: date(2026, 7, 20),
        )
    assert repository.finished["reason_codes"] == ("API_TRANSPORT_FAILED",)
    assert repository.closed is True


def test_pending_license_stops_before_loading_taxonomy_file(monkeypatch) -> None:
    source = load_reference_source_catalog(sbiz_academy_ingest._CONFIG_PATH).get(
        "place.sbiz-academy"
    )
    pending = replace(
        source,
        license=replace(
            source.license,
            status="PENDING",
            terms_fingerprint="",
            reviewed_on=None,
            reviewed_by="",
            attribution_text="",
            raw_private_storage_allowed=False,
            internal_derivative_allowed=False,
        ),
    )
    monkeypatch.setattr(
        sbiz_academy_ingest,
        "load_reference_source_catalog",
        lambda _path: ReferenceSourceCatalog((pending,)),
    )
    taxonomy_loaded = False

    def load_taxonomy():
        nonlocal taxonomy_loaded
        taxonomy_loaded = True
        raise AssertionError("pending license must stop before taxonomy loading")

    with pytest.raises(sbiz_academy_ingest.SchoolLocationConfigurationError):
        sbiz_academy_ingest.ingest_from_environment(
            _environment(), taxonomy_loader=load_taxonomy
        )

    assert taxonomy_loaded is False


def test_tracked_official_taxonomy_loads_all_247_categories_and_education_allowlist() -> None:
    taxonomy, artifacts = sbiz_academy_ingest._load_taxonomy()

    assert {name: len(rows) for name, rows in artifacts.items()} == {
        "taxonomy-large": 10,
        "taxonomy-middle": 75,
        "taxonomy-small": 247,
    }
    assert taxonomy.fingerprint == (
        "1ffabae679945e7151dd62d463100d760a168f5806cd18af8eb570bde04fabfc"
    )
    assert taxonomy.allowed_small_categories == {
        "P10501": "입시·교과학원",
        "P10601": "태권도/무술학원",
        "P10603": "요가/필라테스 학원",
        "P10605": "레크리에이션 교육기관",
        "P10607": "청소년 수련시설",
        "P10609": "음악학원",
        "P10611": "미술학원",
        "P10613": "기타 예술/스포츠 교육기관",
        "P10615": "외국어학원",
        "P10617": "전문자격/고시학원",
        "P10619": "사회교육시설",
        "P10621": "직원 훈련기관",
        "P10623": "운전학원",
        "P10625": "기타 기술/직업 훈련학원",
        "P10627": "컴퓨터 학원",
        "P10629": "그 외 기타 교육기관",
        "P10701": "교육컨설팅업",
        "P10799": "기타 교육지원 서비스업",
    }


def test_tracked_official_taxonomy_rejects_source_checksum_change(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = json.loads(sbiz_academy_ingest._TAXONOMY_PATH.read_text(encoding="utf-8"))
    tracked_source = (
        sbiz_academy_ingest._TAXONOMY_PATH.parent
        / config["source"]["trackedFile"]
    )
    changed_source = tmp_path / "taxonomy.csv"
    changed_source.write_bytes(tracked_source.read_bytes() + b"\n")
    config["source"]["trackedFile"] = changed_source.name
    config_path = tmp_path / "taxonomy.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    monkeypatch.setattr(sbiz_academy_ingest, "_TAXONOMY_PATH", config_path)

    with pytest.raises(ValueError, match="checksum changed"):
        sbiz_academy_ingest._load_taxonomy()

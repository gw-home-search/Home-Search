from __future__ import annotations

import json
from datetime import UTC, date, datetime

import pytest

from ai_service.datasets import academy_registry
from ai_service.datasets.academy_registry import AcademyRegistryAdapter
from ai_service.datasets.bundle import (
    BundleArtifact,
    ReadArtifact,
    ReadBundle,
    build_deterministic_bundle,
)
from ai_service.datasets.models import DatasetSourceContract
from ai_service.datasets.validation import RawPayloadError


OFFICES = (
    "B10", "C10", "D10", "E10", "F10", "G10", "H10", "I10", "J10",
    "K10", "M10", "N10", "P10", "Q10", "R10", "S10", "T10",
)


def _contract() -> DatasetSourceContract:
    return DatasetSourceContract(
        source_id="edu.academy-registry",
        provider="교육부·17개 시도교육청",
        landing_url="https://www.data.go.kr/data/15096277/standard.do",
        acquisition_url="https://open.neis.go.kr/hub/acaInsTiInfo",
        license_terms="검토용",
        attribution_requirements="출처 표시",
        license_reviewed_on=date(2026, 7, 19),
        refresh_frequency="월간",
        freshness_days=45,
        file_format="JSON",
        encoding="UTF-8",
        schema_version="academy-registry-v1",
        coordinate_system="NONE",
        unique_key_fields=("academy_id",),
        required_fields=(
            "academy_id",
            "education_office_code",
            "academy_type",
            "academy_name",
            "status",
            "observed_at",
        ),
        expected_min_rows=17,
        expected_max_rows=17,
        maximum_row_change_ratio=0.1,
        maximum_rejected_ratio=0,
        contains_personal_data=False,
        owner="apps/ai",
        temporal_basis="OBSERVED_AT",
    )


def _row(code: str) -> dict[str, object]:
    return {
        "ATPT_OFCDC_SC_CODE": code,
        "ATPT_OFCDC_SC_NM": f"{code}교육청",
        "ADMST_ZONE_NM": "송파구",
        "ACA_INSTI_SC_NM": "학원",
        "ACA_ASNUM": f"{code}-001",
        "ACA_NM": "  가나다   학원 ",
        "REG_STTUS_NM": "운영",
        "REG_YMD": "20200101",
        "CAA_BEGIN_YMD": "",
        "CAA_END_YMD": "",
        "TOFOR_SMTOT": 120,
        "REALM_SC_NM": "입시.검정 및 보습",
        "LE_ORD_NM": "보습",
        "LE_CRSE_NM": "보습",
        "FA_RDNMA": "서울특별시 송파구 올림픽로 300",
        "FA_RDNZC": "05551",
        "LOAD_DTM": "20260719010000",
        "TELNO": "02-000-0000",
        "TUITION": "500000",
    }


def _bundle(*, mutate=None, total_override: dict[str, int] | None = None) -> bytes:
    observed_at = datetime(2026, 7, 19, 1, tzinfo=UTC)
    artifacts = []
    for code in OFFICES:
        row = _row(code)
        if mutate is not None:
            mutate(code, row)
        page = {
            "acaInsTiInfo": [
                {"head": [{"list_total_count": (total_override or {}).get(code, 1)}, {"RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다."}}]},
                {"row": [row]},
            ]
        }
        artifacts.append(
            BundleArtifact(
                logical_name=f"{code.lower()}-page-000001",
                extension="json",
                media_type="application/json",
                content=json.dumps(page, ensure_ascii=False).encode(),
            )
        )
    return build_deterministic_bundle(
        source_id="edu.academy-registry",
        endpoint_path="/hub/acaInsTiInfo",
        artifacts=tuple(artifacts),
        temporal_value=observed_at,
    )


def test_neis_snapshot_requires_all_17_offices_and_excludes_sensitive_fields() -> None:
    parsed = AcademyRegistryAdapter().parse(_bundle(), _contract(), source_date=None)

    assert len(parsed.rows) == 17
    assert {row["education_office_code"] for row in parsed.rows} == set(OFFICES)
    first = parsed.rows[0]
    assert first["academy_name"] == "가나다 학원"
    assert first["normalized_name_key"] == "가나다 학원"
    assert first["normalized_address_key"] == "서울특별시 송파구 올림픽로 300"
    assert "TELNO" not in first
    assert "TUITION" not in first
    assert parsed.row_rejections == {}


def test_unknown_registry_status_blocks_the_row() -> None:
    def mutate(code: str, row: dict[str, object]) -> None:
        if code == "B10":
            row["REG_STTUS_NM"] = "새상태"

    parsed = AcademyRegistryAdapter().parse(
        _bundle(mutate=mutate), _contract(), source_date=None
    )

    assert parsed.row_rejections == {1: ("ACADEMY_STATUS_UNKNOWN",)}


def test_office_total_count_mismatch_is_blocking() -> None:
    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(
            _bundle(total_override={"B10": 2}), _contract(), source_date=None
        )

    assert error.value.reason_code == "PROVIDER_TOTAL_COUNT_MISMATCH"


def test_observed_source_rejects_source_date_argument() -> None:
    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(
            _bundle(), _contract(), source_date=date(2026, 7, 19)
        )
    assert error.value.reason_code == "SOURCE_CONTRACT_MISMATCH"


@pytest.mark.parametrize(
    ("bundle", "reason_code"),
    [
        (
            ReadBundle(
                source_id="edu.academy-registry",
                complete=True,
                endpoint_path="/hub/acaInsTiInfo",
                temporal_value=date(2026, 7, 19),
                artifacts=(),
            ),
            "BUNDLE_MANIFEST_INVALID",
        ),
        (
            ReadBundle(
                source_id="edu.academy-registry",
                complete=True,
                endpoint_path="/hub/acaInsTiInfo",
                temporal_value=datetime(2026, 7, 19, 1, tzinfo=UTC),
                artifacts=(ReadArtifact("bad-name", "text/plain", b"{}"),),
            ),
            "BUNDLE_MANIFEST_INVALID",
        ),
        (
            ReadBundle(
                source_id="edu.academy-registry",
                complete=True,
                endpoint_path="/hub/acaInsTiInfo",
                temporal_value=datetime(2026, 7, 19, 1, tzinfo=UTC),
                artifacts=(ReadArtifact("l10-page-000001", "application/json", b"{}"),),
            ),
            "PROVIDER_PAGE_INVALID",
        ),
    ],
)
def test_bundle_metadata_fails_closed(monkeypatch, bundle: ReadBundle, reason_code: str) -> None:
    monkeypatch.setattr(academy_registry, "read_deterministic_bundle", lambda *_args, **_kwargs: bundle)
    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(b"raw", _contract(), source_date=None)
    assert error.value.reason_code == reason_code


def test_incomplete_office_coverage_fails_closed(monkeypatch) -> None:
    page = json.dumps(
        {"acaInsTiInfo": [{"head": [{"list_total_count": 1}, {"RESULT": {"CODE": "INFO-000"}}]}, {"row": [_row("B10")]}]}
    ).encode()
    bundle = ReadBundle(
        source_id="edu.academy-registry",
        complete=True,
        endpoint_path="/hub/acaInsTiInfo",
        temporal_value=datetime(2026, 7, 19, 1, tzinfo=UTC),
        artifacts=(ReadArtifact("b10-page-000001", "application/json", page),),
    )
    monkeypatch.setattr(academy_registry, "read_deterministic_bundle", lambda *_args, **_kwargs: bundle)

    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(b"raw", _contract(), source_date=None)

    assert error.value.reason_code == "PROVIDER_COVERAGE_INCOMPLETE"


def test_invalid_identity_type_capacity_and_dates_are_safely_normalized() -> None:
    def mutate(code: str, row: dict[str, object]) -> None:
        if code == "B10":
            row["ACA_ASNUM"] = ""
            row["ACA_INSTI_SC_NM"] = "미상"
            row["TOFOR_SMTOT"] = "not-a-number"
            row["REG_YMD"] = "invalid"

    parsed = AcademyRegistryAdapter().parse(
        _bundle(mutate=mutate), _contract(), source_date=None
    )

    assert parsed.row_rejections[1] == (
        "ACADEMY_IDENTITY_REQUIRED",
        "ACADEMY_TYPE_UNKNOWN",
    )
    assert parsed.rows[0]["capacity"] is None
    assert parsed.rows[0]["registration_date"] is None


def test_invalid_provider_json_fails_closed(monkeypatch) -> None:
    bundle = ReadBundle(
        source_id="edu.academy-registry",
        complete=True,
        endpoint_path="/hub/acaInsTiInfo",
        temporal_value=datetime(2026, 7, 19, 1, tzinfo=UTC),
        artifacts=(ReadArtifact("b10-page-000001", "application/json", b"not-json"),),
    )
    monkeypatch.setattr(academy_registry, "read_deterministic_bundle", lambda *_args, **_kwargs: bundle)

    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(b"raw", _contract(), source_date=None)

    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"


def _provider_page(code: str, *, total: int = 1, result_code: str = "INFO-000") -> bytes:
    return json.dumps(
        {
            "acaInsTiInfo": [
                {"head": [{"list_total_count": total}, {"RESULT": {"CODE": result_code}}]},
                {"row": [_row(code)]},
            ]
        }
    ).encode()


@pytest.mark.parametrize(
    ("artifacts", "reason_code"),
    [
        (
            (
                ReadArtifact("b10-page-000001", "application/json", _provider_page("B10")),
                ReadArtifact("b10-page-000001", "application/json", _provider_page("B10")),
            ),
            "PROVIDER_PAGE_INVALID",
        ),
        (
            (
                ReadArtifact("b10-page-000001", "application/json", _provider_page("B10", total=2)),
                ReadArtifact("b10-page-000002", "application/json", _provider_page("B10", total=3)),
            ),
            "PROVIDER_TOTAL_COUNT_MISMATCH",
        ),
        (
            (ReadArtifact("b10-page-000001", "application/json", _provider_page("C10")),),
            "PROVIDER_PAGE_INVALID",
        ),
        (
            (ReadArtifact("b10-page-000001", "application/json", _provider_page("B10", result_code="ERROR")),),
            "PROVIDER_PAGE_INVALID",
        ),
    ],
)
def test_provider_page_identity_and_totals_fail_closed(
    monkeypatch, artifacts: tuple[ReadArtifact, ...], reason_code: str
) -> None:
    bundle = ReadBundle(
        source_id="edu.academy-registry",
        complete=True,
        endpoint_path="/hub/acaInsTiInfo",
        temporal_value=datetime(2026, 7, 19, 1, tzinfo=UTC),
        artifacts=artifacts,
    )
    monkeypatch.setattr(academy_registry, "read_deterministic_bundle", lambda *_args, **_kwargs: bundle)

    with pytest.raises(RawPayloadError) as error:
        AcademyRegistryAdapter().parse(b"raw", _contract(), source_date=None)

    assert error.value.reason_code == reason_code


def test_negative_capacity_is_not_persisted() -> None:
    def mutate(code: str, row: dict[str, object]) -> None:
        if code == "B10":
            row["TOFOR_SMTOT"] = -1

    parsed = AcademyRegistryAdapter().parse(
        _bundle(mutate=mutate), _contract(), source_date=None
    )

    assert parsed.rows[0]["capacity"] is None

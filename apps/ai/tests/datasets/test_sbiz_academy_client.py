from __future__ import annotations

import json
import stat
from datetime import UTC, datetime

import pytest

from ai_service.datasets.sbiz_academy import (
    SbizAcademyAdapter, SbizTaxonomyContract, taxonomy_fingerprint,
)
from ai_service.datasets import sbiz_academy_client
from ai_service.datasets.sbiz_academy_client import SbizAcademyApiClient, SbizAcademyApiError
from ai_service.datasets.secure_temp import SecureTempWorkspace
from tests.datasets.test_sbiz_academy_adapter import _contract, _rows, _taxonomy, TAXONOMY


def _page() -> bytes:
    return json.dumps(
        {
            "body": {
                "totalCount": 1,
                "numOfRows": 1000,
                "items": [{
                    "bizesId": "store-1", "bizesNm": "가나다 학원",
                    "indsSclsCd": "P10101", "indsSclsNm": "fixture 학원",
                    "rdnmAdr": "서울특별시 송파구 올림픽로 300",
                    "lnoAdr": "서울특별시 송파구", "newZipcd": "05551",
                    "adongCd": "11710566", "lat": "37.51", "lon": "127.10",
                }],
            }
        }
    ).encode()


def test_sbiz_collector_partitions_only_allowlisted_taxonomy_without_key_in_path() -> None:
    paths = []

    def request(path, _timeout, key):
        paths.append(path)
        assert key == "secret"
        return 200, {}, _page()

    observed_at = datetime(2026, 7, 20, tzinfo=UTC)
    collected = SbizAcademyApiClient(
        taxonomy=_taxonomy(), taxonomy_artifacts=TAXONOMY,
        requester=request,
    ).collect("secret", observed_at=observed_at)

    parsed = SbizAcademyAdapter(_taxonomy()).parse(
        collected.content, _contract(), source_date=None
    )
    rows = _rows(parsed)
    assert collected.complete is True
    assert collected.raw_row_count == 1
    assert len(rows) == 1
    assert all("secret" not in path for path in paths)


def test_sbiz_collects_into_owner_only_prepared_bundle() -> None:
    observed_at = datetime(2026, 7, 20, tzinfo=UTC)
    client = SbizAcademyApiClient(
        taxonomy=_taxonomy(),
        taxonomy_artifacts=TAXONOMY,
        requester=lambda *_args: (200, {}, _page()),
    )

    with SecureTempWorkspace(required_free_bytes=1024 * 1024) as workspace:
        collected = client.collect_prepared(
            "secret", observed_at=observed_at, workspace=workspace
        )

        assert collected.prepared.path.is_file()
        assert stat.S_IMODE(collected.prepared.path.stat().st_mode) == 0o600
        assert collected.prepared.byte_length == collected.prepared.path.stat().st_size
        assert collected.complete is True


def test_sbiz_mid_collection_failure_preserves_incomplete_bundle() -> None:
    taxonomy_artifacts = {
        **TAXONOMY,
        "taxonomy-small": [
            {"code": "P10101", "name": "fixture 학원"},
            {"code": "P10102", "name": "fixture 교습소"},
        ],
    }
    taxonomy = SbizTaxonomyContract(
        taxonomy_fingerprint(taxonomy_artifacts),
        {"P10101": "fixture 학원", "P10102": "fixture 교습소"},
    )
    calls = 0

    def request(_path, _timeout, _key):
        nonlocal calls
        calls += 1
        return (200, {}, _page()) if calls == 1 else (500, {}, b"provider body")

    collected = SbizAcademyApiClient(
        taxonomy=taxonomy, taxonomy_artifacts=taxonomy_artifacts,
        requester=request,
    ).collect("secret", observed_at=datetime(2026, 7, 20, tzinfo=UTC))

    assert collected.complete is False
    assert collected.reason_codes == ("API_SERVER_ERROR",)
    assert b"provider body" not in collected.content


def test_sbiz_invalid_evidence_configuration_and_first_payload_fail_closed() -> None:
    with pytest.raises(ValueError):
        SbizAcademyApiClient(
            taxonomy=_taxonomy(), taxonomy_artifacts={}, requester=lambda *_args: (200, {}, b"{}")
        )
    with pytest.raises(ValueError):
        SbizAcademyApiError("secret")
    client = SbizAcademyApiClient(
        taxonomy=_taxonomy(), taxonomy_artifacts=TAXONOMY,
        requester=lambda *_args: (200, {}, b"not-json"),
    )
    with pytest.raises(SbizAcademyApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"


@pytest.mark.parametrize(
    ("status", "reason"),
    [(401, "API_AUTHENTICATION_FAILED"), (429, "API_RATE_LIMITED"),
     (302, "API_REDIRECT_REJECTED"), (503, "API_SERVER_ERROR"),
     (400, "API_BAD_REQUEST")],
)
def test_sbiz_http_failures_are_safe(status: int, reason: str) -> None:
    assert sbiz_academy_client._http_reason(status) == reason


def test_sbiz_transport_and_page_size_are_bounded(monkeypatch) -> None:
    client = SbizAcademyApiClient(
        taxonomy=_taxonomy(), taxonomy_artifacts=TAXONOMY,
        requester=lambda *_args: (_ for _ in ()).throw(OSError()),
    )
    with pytest.raises(SbizAcademyApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_TRANSPORT_FAILED"

    monkeypatch.setattr(sbiz_academy_client, "_MAX_PAGE_BYTES", 1)
    client = SbizAcademyApiClient(
        taxonomy=_taxonomy(), taxonomy_artifacts=TAXONOMY,
        requester=lambda *_args: (200, {}, b"too-large"),
    )
    with pytest.raises(SbizAcademyApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_PAGE_TOO_LARGE"


def test_sbiz_default_https_request_encodes_key_and_closes(monkeypatch) -> None:
    state = {"path": "", "closed": False}

    class Response:
        status = 200

        def getheaders(self):
            return []

        def read(self, _size):
            return b"{}"

    class Connection:
        def __init__(self, host, port, timeout):
            assert (host, port, timeout) == ("apis.data.go.kr", 443, 3)

        def request(self, _method, path, headers):
            state["path"] = path
            assert headers == {"Accept": "application/json"}

        def getresponse(self):
            return Response()

        def close(self):
            state["closed"] = True

    monkeypatch.setattr(sbiz_academy_client, "HTTPSConnection", Connection)
    assert sbiz_academy_client._request("/path?x=1", 3, "a b") == (200, {}, b"{}")
    assert "serviceKey=a%20b" in state["path"]
    assert state["closed"] is True

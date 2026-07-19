from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest

from ai_service.datasets.academy_registry_client import (
    AcademyRegistryApiClient,
    AcademyRegistryApiError,
)
from ai_service.datasets import academy_registry_client
from ai_service.datasets.bundle import read_deterministic_bundle


OFFICES = (
    "B10", "C10", "D10", "E10", "F10", "G10", "H10", "I10", "J10",
    "K10", "M10", "N10", "P10", "Q10", "R10", "S10", "T10",
)


def _page(office: str) -> bytes:
    return json.dumps(
        {
            "acaInsTiInfo": [
                {"head": [{"list_total_count": 1}, {"RESULT": {"CODE": "INFO-000"}}]},
                {"row": [{"ATPT_OFCDC_SC_CODE": office, "ACA_ASNUM": f"{office}-1"}]},
            ]
        }
    ).encode()


def test_collects_all_offices_with_fixed_page_size_and_observed_time() -> None:
    paths: list[str] = []

    def requester(path: str, _timeout: float, _service_key: str):
        paths.append(path)
        office = next(code for code in OFFICES if f"ATPT_OFCDC_SC_CODE={code}" in path)
        return 200, {}, _page(office)

    observed_at = datetime(2026, 7, 20, 3, tzinfo=UTC)
    collected = AcademyRegistryApiClient(requester=requester).collect(
        "private-key", observed_at=observed_at
    )

    assert collected.complete is True
    assert collected.page_count == 17
    assert collected.raw_row_count == 17
    assert all("pSize=1000" in path and "Type=json" in path for path in paths)
    assert all("private-key" not in path for path in paths)
    bundle = read_deterministic_bundle(
        collected.content,
        expected_source_id="edu.academy-registry",
        maximum_bytes=1024 * 1024,
    )
    assert bundle.temporal_value == observed_at
    assert len(bundle.artifacts) == 17


def test_first_page_failure_does_not_create_raw_bundle() -> None:
    client = AcademyRegistryApiClient(requester=lambda *_args: (503, {}, b"provider body"))

    with pytest.raises(AcademyRegistryApiError) as error:
        client.collect("private-key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))

    assert error.value.reason_code == "API_SERVER_ERROR"


def test_middle_failure_preserves_only_safe_incomplete_evidence() -> None:
    calls = 0

    def requester(path: str, _timeout: float, _service_key: str):
        nonlocal calls
        calls += 1
        if calls == 2:
            return 429, {}, b"secret provider body"
        return 200, {}, _page("B10")

    collected = AcademyRegistryApiClient(requester=requester).collect(
        "private-key", observed_at=datetime(2026, 7, 20, tzinfo=UTC)
    )

    assert collected.complete is False
    assert collected.reason_codes == ("API_RATE_LIMITED",)
    assert collected.page_count == 1
    assert b"secret provider body" not in collected.content
    assert b"private-key" not in collected.content


@pytest.mark.parametrize(
    ("status", "reason"),
    [(401, "API_AUTHENTICATION_FAILED"), (429, "API_RATE_LIMITED"),
     (302, "API_REDIRECT_REJECTED"), (503, "API_SERVER_ERROR"),
     (400, "API_BAD_REQUEST")],
)
def test_http_failures_map_to_safe_reasons(status: int, reason: str) -> None:
    assert academy_registry_client._http_reason(status) == reason


def test_invalid_configuration_and_provider_payload_fail_before_bundle() -> None:
    with pytest.raises(ValueError):
        AcademyRegistryApiClient(timeout_seconds=31)
    with pytest.raises(ValueError):
        AcademyRegistryApiError("provider-secret")
    client = AcademyRegistryApiClient(requester=lambda *_args: (200, {}, b"not-json"))
    with pytest.raises(AcademyRegistryApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"


def test_transport_retry_and_page_size_are_bounded(monkeypatch) -> None:
    client = AcademyRegistryApiClient(requester=lambda *_args: (_ for _ in ()).throw(OSError()))
    with pytest.raises(AcademyRegistryApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_TRANSPORT_FAILED"

    monkeypatch.setattr(academy_registry_client, "_MAX_PAGE_BYTES", 1)
    client = AcademyRegistryApiClient(requester=lambda *_args: (200, {}, b"too-large"))
    with pytest.raises(AcademyRegistryApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_PAGE_TOO_LARGE"


def test_default_https_request_encodes_key_and_closes_connection(monkeypatch) -> None:
    state = {"closed": False, "path": ""}

    class Response:
        status = 200

        def getheaders(self):
            return [("content-type", "application/json")]

        def read(self, _size):
            return b"{}"

    class Connection:
        def __init__(self, host, port, timeout):
            assert (host, port, timeout) == ("open.neis.go.kr", 443, 2)

        def request(self, _method, path, headers):
            state["path"] = path
            assert headers == {"Accept": "application/json"}

        def getresponse(self):
            return Response()

        def close(self):
            state["closed"] = True

    monkeypatch.setattr(academy_registry_client, "HTTPSConnection", Connection)
    status, headers, body = academy_registry_client._request("/hub/test?x=1", 2, "a b")
    assert (status, headers, body) == (200, {"content-type": "application/json"}, b"{}")
    assert "KEY=a%20b" in state["path"]
    assert state["closed"] is True

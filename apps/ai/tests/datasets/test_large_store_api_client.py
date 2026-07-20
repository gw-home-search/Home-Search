from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest

from ai_service.datasets.bundle import read_deterministic_bundle
from ai_service.datasets.large_store_client import LargeStoreApiClient, LargeStoreApiError
from ai_service.datasets import large_store_client


def _page(page_no: int, total_count: int, rows: list[dict[str, str]]) -> bytes:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                "body": {
                    "dataType": "JSON", "numOfRows": 100, "pageNo": page_no,
                    "totalCount": total_count, "items": {"item": rows},
                },
            }
        }
    ).encode()


def test_collects_all_pages_without_key_in_raw_bundle() -> None:
    paths: list[str] = []

    def requester(path: str, _timeout: float, service_key: str):
        paths.append(path)
        assert service_key == "private-key"
        page_no = 1 if "pageNo=1" in path else 2
        row_count = 100 if page_no == 1 else 1
        return 200, {}, _page(page_no, 101, [{"MNG_NO": str(i)} for i in range(row_count)])

    observed_at = datetime(2026, 7, 20, 5, tzinfo=UTC)
    collected = LargeStoreApiClient(requester=requester).collect(
        "private-key", observed_at=observed_at
    )

    assert collected.complete is True
    assert collected.page_count == 2
    assert collected.raw_row_count == 101
    assert all("numOfRows=100" in path and "returnType=JSON" in path for path in paths)
    assert all("private-key" not in path for path in paths)
    assert b"private-key" not in collected.content
    bundle = read_deterministic_bundle(
        collected.content, expected_source_id="retail.large-store",
        maximum_bytes=1024 * 1024,
    )
    assert bundle.temporal_value == observed_at
    assert [artifact.logical_name for artifact in bundle.artifacts] == [
        "page-000001", "page-000002"
    ]


def test_first_page_failure_has_only_safe_reason() -> None:
    client = LargeStoreApiClient(requester=lambda *_args: (401, {}, b"secret body"))
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("private-key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_AUTHENTICATION_FAILED"


def test_middle_failure_preserves_incomplete_pages_without_provider_body() -> None:
    calls = 0

    def requester(_path: str, _timeout: float, _service_key: str):
        nonlocal calls
        calls += 1
        if calls >= 2:
            return 503, {}, b"secret provider body"
        return 200, {}, _page(1, 101, [{"MNG_NO": str(i)} for i in range(100)])

    collected = LargeStoreApiClient(requester=requester).collect(
        "private-key", observed_at=datetime(2026, 7, 20, tzinfo=UTC)
    )
    assert collected.complete is False
    assert collected.reason_codes == ("API_SERVER_ERROR",)
    assert collected.page_count == 1
    assert b"secret provider body" not in collected.content


def test_rejects_changed_envelope_and_pagination() -> None:
    client = LargeStoreApiClient(requester=lambda *_args: (200, {}, b"{}"))
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"


def test_rejects_invalid_configuration_and_bounds(monkeypatch) -> None:
    with pytest.raises(ValueError):
        LargeStoreApiClient(timeout_seconds=31)
    with pytest.raises(ValueError):
        LargeStoreApiError("provider-secret")
    client = LargeStoreApiClient(requester=lambda *_args: (200, {}, _page(1, 0, [])))
    with pytest.raises(ValueError):
        client.collect(" ", observed_at=datetime(2026, 7, 20, tzinfo=UTC))

    monkeypatch.setattr(large_store_client, "_MAX_PAGES", 0)
    client = LargeStoreApiClient(
        requester=lambda *_args: (200, {}, _page(1, 1, [{"MNG_NO": "1"}]))
    )
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"


def test_total_change_and_missing_rows_preserve_safe_incomplete_bundle() -> None:
    calls = 0

    def changed_total(_path: str, _timeout: float, _service_key: str):
        nonlocal calls
        calls += 1
        if calls == 1:
            return 200, {}, _page(1, 101, [{"MNG_NO": str(i)} for i in range(100)])
        return 200, {}, _page(2, 102, [{"MNG_NO": "101"}])

    collected = LargeStoreApiClient(requester=changed_total).collect(
        "key", observed_at=datetime(2026, 7, 20, tzinfo=UTC)
    )
    assert collected.complete is False
    assert collected.reason_codes == ("PROVIDER_PAGE_INVALID",)

    collected = LargeStoreApiClient(
        requester=lambda *_args: (200, {}, _page(1, 1, []))
    ).collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert collected.complete is False
    assert collected.reason_codes == ("PROVIDER_PAGE_INVALID",)


def test_transport_retry_page_size_and_http_reasons(monkeypatch) -> None:
    client = LargeStoreApiClient(
        requester=lambda *_args: (_ for _ in ()).throw(OSError())
    )
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_TRANSPORT_FAILED"

    monkeypatch.setattr(large_store_client, "_MAX_PAGE_BYTES", 1)
    client = LargeStoreApiClient(requester=lambda *_args: (200, {}, b"too-large"))
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "API_PAGE_TOO_LARGE"

    assert large_store_client._http_reason(401) == "API_AUTHENTICATION_FAILED"
    assert large_store_client._http_reason(429) == "API_RATE_LIMITED"
    assert large_store_client._http_reason(302) == "API_REDIRECT_REJECTED"
    assert large_store_client._http_reason(503) == "API_SERVER_ERROR"
    assert large_store_client._http_reason(400) == "API_BAD_REQUEST"


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
            assert (host, port, timeout) == ("apis.data.go.kr", 443, 2)

        def request(self, _method, path, headers):
            state["path"] = path
            assert headers == {"Accept": "application/json"}

        def getresponse(self):
            return Response()

        def close(self):
            state["closed"] = True

    monkeypatch.setattr(large_store_client, "HTTPSConnection", Connection)
    status, headers, body = large_store_client._request("/info?x=1", 2, "a b")
    assert (status, headers, body) == (
        200, {"content-type": "application/json"}, b"{}"
    )
    assert "serviceKey=a%20b" in state["path"]
    assert state["closed"] is True

    client = LargeStoreApiClient(
        requester=lambda *_args: (200, {}, _page(2, 1, [{"MNG_NO": "1"}]))
    )
    with pytest.raises(LargeStoreApiError) as error:
        client.collect("key", observed_at=datetime(2026, 7, 20, tzinfo=UTC))
    assert error.value.reason_code == "PROVIDER_PAGE_INVALID"

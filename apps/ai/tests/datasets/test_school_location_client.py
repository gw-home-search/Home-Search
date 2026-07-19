from __future__ import annotations

import json

import pytest

from ai_service.datasets.school_location_client import (
    SchoolLocationApiClient,
    SchoolLocationApiError,
    _http_reason,
    _request,
)


def _page(page_no: int, total_count: int, rows: list[dict[str, object]]) -> bytes:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                "body": {
                    "items": rows,
                    "pageNo": page_no,
                    "numOfRows": 1000,
                    "totalCount": total_count,
                },
            }
        },
        separators=(",", ":"),
    ).encode()


def _row(index: int) -> dict[str, object]:
    return {
        "schoolId": f"B{index:09d}",
        "schoolNm": "학교",
        "schoolSe": "초등학교",
        "operSttus": "운영",
        "lnmadr": "서울특별시 주소",
        "rdnmadr": "",
        "cddcCode": "7010000",
        "cddcNm": "서울특별시교육청",
        "latitude": "37.5",
        "longitude": "127.0",
        "referenceDate": "2026-03-20",
    }


def test_client_encodes_decoding_key_once_and_uses_fixed_query() -> None:
    paths: list[str] = []

    def requester(path: str, _timeout: float):
        paths.append(path)
        return 200, {}, _page(1, 1, [_row(1)])

    result = SchoolLocationApiClient(requester=requester).collect("decoded+/= key")

    assert result.complete is True
    assert result.source_date.isoformat() == "2026-03-20"  # type: ignore[union-attr]
    assert "serviceKey=decoded%2B%2F%3D%20key" in paths[0]
    assert "numOfRows=1000&type=json" in paths[0]


def test_client_retries_one_5xx_but_not_429() -> None:
    calls = 0

    def transient(_path: str, _timeout: float):
        nonlocal calls
        calls += 1
        if calls == 1:
            return 503, {}, b"discarded"
        return 200, {}, _page(1, 1, [_row(1)])

    assert SchoolLocationApiClient(requester=transient).collect("key").complete is True
    assert calls == 2

    rate_calls = 0

    def rate_limited(_path: str, _timeout: float):
        nonlocal rate_calls
        rate_calls += 1
        return 429, {}, b"must-not-be-persisted"

    with pytest.raises(SchoolLocationApiError) as error:
        SchoolLocationApiClient(requester=rate_limited).collect("key")
    assert error.value.reason_code == "API_RATE_LIMITED"
    assert rate_calls == 1


def test_client_does_not_retry_non_timeout_transport_failure() -> None:
    calls = 0

    def requester(_path: str, _timeout: float):
        nonlocal calls
        calls += 1
        raise OSError("connection refused")

    with pytest.raises(SchoolLocationApiError) as error:
        SchoolLocationApiClient(requester=requester).collect("key")

    assert error.value.reason_code == "API_TRANSPORT_FAILED"
    assert calls == 1


def test_middle_page_failure_returns_explainable_incomplete_bundle() -> None:
    first_rows = [_row(index) for index in range(1, 1001)]

    def requester(path: str, _timeout: float):
        if "pageNo=1" in path:
            return 200, {}, _page(1, 1001, first_rows)
        return 503, {}, b"discarded"

    result = SchoolLocationApiClient(requester=requester).collect("key")

    assert result.complete is False
    assert result.page_count == 1
    assert result.raw_row_count == 1000
    assert result.reason_codes == ("API_SERVER_ERROR",)


def test_malformed_first_page_is_not_persisted() -> None:
    client = SchoolLocationApiClient(requester=lambda _path, _timeout: (200, {}, b"not-json"))

    with pytest.raises(SchoolLocationApiError) as error:
        client.collect("key")

    assert error.value.reason_code == "API_ENVELOPE_INVALID"


def test_malformed_middle_page_preserves_only_previously_valid_pages() -> None:
    first_rows = [_row(index) for index in range(1, 1001)]

    def requester(path: str, _timeout: float):
        if "pageNo=1" in path:
            return 200, {}, _page(1, 1001, first_rows)
        return 200, {}, b'{"serviceKey":"must-not-be-persisted"}'

    result = SchoolLocationApiClient(requester=requester).collect("key")

    assert result.complete is False
    assert result.page_count == 1
    assert result.raw_row_count == 1000
    assert b"must-not-be-persisted" not in result.content
    assert result.reason_codes == ("API_ENVELOPE_INVALID",)


@pytest.mark.parametrize(
    ("result_code", "expected_reason"),
    [
        ("30", "API_AUTHENTICATION_FAILED"),
        ("22", "API_QUOTA_EXCEEDED"),
    ],
)
def test_provider_rejection_body_is_not_persisted(
    result_code: str,
    expected_reason: str,
) -> None:
    body = json.dumps(
        {
            "response": {
                "header": {
                    "resultCode": result_code,
                    "resultMsg": "SERVICE KEY IS NOT REGISTERED ERROR",
                },
                "body": {"serviceKey": "must-not-be-persisted"},
            }
        }
    ).encode()

    with pytest.raises(SchoolLocationApiError) as error:
        SchoolLocationApiClient(
            requester=lambda _path, _timeout: (200, {}, body)
        ).collect("key")

    assert error.value.reason_code == expected_reason


def test_cumulative_page_limit_preserves_only_prior_valid_pages(monkeypatch) -> None:
    first_rows = [_row(index) for index in range(1, 1001)]
    first_page = _page(1, 1001, first_rows)
    second_page = _page(2, 1001, [_row(1001)])
    monkeypatch.setattr(
        "ai_service.datasets.school_location_client._MAX_COLLECTED_PAGE_BYTES",
        len(first_page) + len(second_page) - 1,
    )

    def requester(path: str, _timeout: float):
        return (200, {}, first_page) if "pageNo=1" in path else (200, {}, second_page)

    result = SchoolLocationApiClient(requester=requester).collect("key")

    assert result.complete is False
    assert result.page_count == 1
    assert result.reason_codes == ("API_BUNDLE_TOO_LARGE",)


@pytest.mark.parametrize("timeout", [0, 31, float("nan")])
def test_client_rejects_invalid_timeout(timeout: float) -> None:
    with pytest.raises(ValueError):
        SchoolLocationApiClient(timeout_seconds=timeout)


def test_client_rejects_invalid_key_and_oversized_page() -> None:
    client = SchoolLocationApiClient(requester=lambda _path, _timeout: (200, {}, b"x" * (4 * 1024 * 1024 + 1)))
    with pytest.raises(ValueError):
        client.collect(" ")
    with pytest.raises(SchoolLocationApiError) as error:
        client.collect("key")
    assert error.value.reason_code == "API_PAGE_TOO_LARGE"


@pytest.mark.parametrize(
    ("status", "reason"),
    [
        (401, "API_AUTHENTICATION_FAILED"),
        (403, "API_AUTHENTICATION_FAILED"),
        (429, "API_RATE_LIMITED"),
        (302, "API_REDIRECT_REJECTED"),
        (408, "API_BAD_REQUEST"),
        (413, "API_QUOTA_EXCEEDED"),
        (503, "API_SERVER_ERROR"),
        (400, "API_BAD_REQUEST"),
    ],
)
def test_http_failures_map_to_safe_reason_codes(status: int, reason: str) -> None:
    assert _http_reason(status) == reason


def test_default_request_rejects_non_allowlisted_path_before_network() -> None:
    with pytest.raises(SchoolLocationApiError) as error:
        _request("/unexpected", 1)
    assert error.value.reason_code == "API_BAD_REQUEST"

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from ai_service.datasets.bundle import read_deterministic_bundle
from ai_service.datasets.childcare_client import ChildcareApiClient, ChildcareApiError


OBSERVED_AT = datetime(2026, 7, 21, 9, 30, tzinfo=UTC)


def test_childcare_client_collects_each_region_without_persisting_keyed_urls() -> None:
    requests: list[tuple[str, str]] = []

    def requester(path: str, _timeout: float, key: str):
        requests.append((path, key))
        return 200, {"Content-Type": "application/xml; charset=UTF-8"}, _xml(path)

    result = ChildcareApiClient(requester=requester).collect(
        "fixture-childcare-key",
        region_codes=("11710", "11680"),
        observed_at=OBSERVED_AT,
    )

    assert result.complete is True
    assert result.region_count == 2
    assert result.raw_row_count == 2
    assert result.reason_codes == ()
    assert requests == [
        ("/mediate/rest/cpmsapi030/cpmsapi030/request?arcode=11680&stcode=", "fixture-childcare-key"),
        ("/mediate/rest/cpmsapi030/cpmsapi030/request?arcode=11710&stcode=", "fixture-childcare-key"),
    ]
    assert b"fixture-childcare-key" not in result.content
    bundle = read_deterministic_bundle(
        result.content,
        expected_source_id="childcare.center",
        maximum_bytes=1024 * 1024,
    )
    expected_by_region = {
        code: _xml(f"?arcode={code}&stcode=") for code in ("11680", "11710")
    }
    assert {
        artifact.logical_name.removeprefix("region-"): artifact.content
        for artifact in bundle.artifacts
    } == expected_by_region


def test_childcare_client_preserves_partial_bundle_without_publication() -> None:
    call_count = 0

    def requester(path: str, _timeout: float, _key: str):
        nonlocal call_count
        call_count += 1
        if call_count >= 2:
            return 503, {}, b"provider internal body"
        return 200, {"Content-Type": "text/xml"}, _xml(path)

    result = ChildcareApiClient(requester=requester).collect(
        "fixture-childcare-key",
        region_codes=("11680", "11710"),
        observed_at=OBSERVED_AT,
    )

    assert result.complete is False
    assert result.region_count == 1
    assert result.raw_row_count == 1
    assert result.reason_codes == ("API_SERVER_ERROR",)
    assert b"provider internal body" not in result.content
    assert b"fixture-childcare-key" not in result.content


def test_childcare_client_retries_timeout_once_without_exposing_key() -> None:
    calls = 0

    def requester(path: str, _timeout: float, key: str):
        nonlocal calls
        calls += 1
        assert key == "fixture-childcare-key"
        if calls == 1:
            raise TimeoutError
        return 200, {"Content-Type": "application/xml"}, _xml(path)

    result = ChildcareApiClient(requester=requester).collect(
        "fixture-childcare-key",
        region_codes=("11710",),
        observed_at=OBSERVED_AT,
    )

    assert result.complete is True
    assert calls == 2


@pytest.mark.parametrize(
    ("status", "reason"),
    (
        (403, "API_AUTHENTICATION_FAILED"),
        (429, "API_RATE_LIMITED"),
        (302, "API_REDIRECT_REJECTED"),
        (400, "API_BAD_REQUEST"),
        (503, "API_SERVER_ERROR"),
    ),
)
def test_childcare_client_maps_http_failure_to_safe_reason(
    status: int, reason: str
) -> None:
    def requester(_path: str, _timeout: float, _key: str):
        return status, {}, b"provider body must not escape"

    with pytest.raises(ChildcareApiError) as error:
        ChildcareApiClient(requester=requester).collect(
            "fixture-childcare-key",
            region_codes=("11710",),
            observed_at=OBSERVED_AT,
        )
    assert error.value.reason_code == reason
    assert "provider body" not in str(error.value)


def test_childcare_client_rejects_provider_auth_body_and_non_xml_media() -> None:
    def auth_body(_path: str, _timeout: float, _key: str):
        return 200, {"Content-Type": "application/xml"}, b"<response>INFO-100</response>"

    with pytest.raises(ChildcareApiError) as auth_error:
        ChildcareApiClient(requester=auth_body).collect(
            "fixture-childcare-key",
            region_codes=("11710",),
            observed_at=OBSERVED_AT,
        )
    assert auth_error.value.reason_code == "API_AUTHENTICATION_FAILED"

    def json_body(_path: str, _timeout: float, _key: str):
        return 200, {"Content-Type": "application/json"}, b"{}"

    with pytest.raises(ChildcareApiError) as media_error:
        ChildcareApiClient(requester=json_body).collect(
            "fixture-childcare-key",
            region_codes=("11710",),
            observed_at=OBSERVED_AT,
        )
    assert media_error.value.reason_code == "API_XML_INVALID"


def test_childcare_client_rejects_invalid_collection_bounds() -> None:
    with pytest.raises(ValueError, match="timeout"):
        ChildcareApiClient(timeout_seconds=0)
    with pytest.raises(ValueError, match="configuration"):
        ChildcareApiClient(requester=lambda *_args: (200, {}, b"")).collect(
            "fixture-childcare-key",
            region_codes=("invalid",),
            observed_at=OBSERVED_AT,
        )


def test_childcare_client_rejects_malformed_xml() -> None:
    def requester(_path: str, _timeout: float, _key: str):
        return 200, {"Content-Type": "text/xml"}, b"<response>"

    with pytest.raises(ChildcareApiError) as error:
        ChildcareApiClient(requester=requester).collect(
            "fixture-childcare-key",
            region_codes=("11710",),
            observed_at=OBSERVED_AT,
        )
    assert error.value.reason_code == "API_XML_INVALID"


def _xml(path: str) -> bytes:
    region_code = path.split("arcode=", 1)[1].split("&", 1)[0]
    return f"""<response><item><stcode>{region_code}000001</stcode>
<crtelno>000-0000-0000</crtelno><crrepname>fixture-name</crrepname>
</item></response>""".encode()

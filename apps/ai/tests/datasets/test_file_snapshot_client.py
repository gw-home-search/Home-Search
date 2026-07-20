from __future__ import annotations

from datetime import date

import pytest

from ai_service.datasets import file_snapshot_client
from ai_service.datasets.file_snapshot_client import (
    FileSnapshotClient,
    FileSnapshotError,
)


def test_large_store_download_accepts_one_allowlisted_redirect_and_filename_date(tmp_path) -> None:
    calls: list[str] = []

    def request(url: str, _timeout: float):
        calls.append(url)
        if len(calls) == 1:
            return 302, {"location": "https://file.localdata.go.kr/file/large_scale_retail_stores/large-store-20260718.csv"}, b""
        body = b"header\nvalue\n"
        return 200, {
            "content-type": "text/csv; charset=utf-8",
            "content-length": str(len(body)),
            "content-disposition": 'attachment; filename="large-store-20260718.csv"',
        }, body

    collected = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",),
        extension="csv",
        maximum_bytes=256 * 1024 * 1024,
        allow_one_redirect=True,
        requester=request,
    ).collect(target=tmp_path / "snapshot.csv")

    assert collected.source_date == date(2026, 7, 18)
    assert collected.byte_length == len(b"header\nvalue\n")
    assert len(calls) == 2


def test_large_store_uses_only_tracked_release_metadata_when_filename_has_no_date(
    tmp_path,
) -> None:
    body = b"header\nvalue\n"
    collected = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/download/large_scale_retail_stores/info",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/download/large_scale_retail_stores/",),
        media_types=("text/csv",),
        extension="csv",
        maximum_bytes=256 * 1024 * 1024,
        allow_one_redirect=True,
        source_date=date(2025, 11, 27),
        requester=lambda *_args: (
            200,
            {
                "content-type": "text/csv;charset=UTF-8",
                "content-length": str(len(body)),
                "content-disposition": "attachment; filename*=UTF-8''retail.csv",
            },
            body,
        ),
    ).collect(target=tmp_path / "snapshot.csv")

    assert collected.source_date == date(2025, 11, 27)


@pytest.mark.parametrize(
    ("headers", "body", "reason"),
    [
        ({"content-type": "text/html", "content-length": "4"}, b"html", "FILE_MEDIA_TYPE_INVALID"),
        ({"content-type": "text/csv", "content-length": "5"}, b"four", "FILE_LENGTH_MISMATCH"),
        ({"content-type": "text/csv", "content-length": "4"}, b"four", "SOURCE_DATE_UNVERIFIED"),
    ],
)
def test_file_snapshot_fails_closed_on_headers_and_unverified_date(
    tmp_path, headers, body, reason
) -> None:
    client = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",),
        extension="csv",
        maximum_bytes=1024,
        allow_one_redirect=True,
        requester=lambda _url, _timeout: (200, headers, body),
    )

    with pytest.raises(FileSnapshotError) as error:
        client.collect(target=tmp_path / "snapshot.csv")
    assert error.value.reason_code == reason


def test_rail_release_requires_a_fixed_xlsx_url() -> None:
    with pytest.raises(ValueError, match="fixed release"):
        FileSnapshotClient(
            source_id="transport.rail-station",
            url="https://www.data.go.kr/data/15013205/standard.do",
            allowed_hosts=("www.data.go.kr",),
            allowed_path_prefixes=("/data/15013205/",),
            media_types=("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",),
            extension="xlsx",
            maximum_bytes=1024,
            allow_one_redirect=True,
            requester=lambda *_args: (500, {}, b""),
        )


def test_kric_release_accepts_only_the_tracked_non_secret_query(tmp_path) -> None:
    calls: list[str] = []
    body = b"fixture-xlsx"

    def request(url: str, _timeout: float):
        calls.append(url)
        return 200, {
            "content-type": "application/octet-stream;charset=UTF-8",
            "content-length": str(len(body)),
            "content-disposition": 'attachment; filename="rail-stations-20260630.xlsx"',
        }, body

    collected = FileSnapshotClient(
        source_id="transport.rail-station",
        url="https://data.kric.go.kr/rips/dataset/download.file",
        fixed_query="type=filedata&id=32&operation=1",
        allowed_hosts=("data.kric.go.kr",),
        allowed_path_prefixes=("/rips/dataset/download.file",),
        media_types=("application/octet-stream",),
        extension="xlsx",
        maximum_bytes=1024,
        allow_one_redirect=True,
        requester=request,
    ).collect(target=tmp_path / "rail.xlsx")

    assert calls == [
        "https://data.kric.go.kr/rips/dataset/download.file"
        "?type=filedata&id=32&operation=1"
    ]
    assert collected.source_date == date(2026, 6, 30)


def test_default_transport_preserves_validated_fixed_query(monkeypatch, tmp_path) -> None:
    body_chunks = iter((b"xlsx", b""))

    class Response:
        status = 200

        def getheaders(self):
            return [
                ("content-type", "application/octet-stream"),
                ("content-length", "4"),
                ("content-disposition", 'filename="rail-20260630.xlsx"'),
            ]

        def read(self, _size):
            return next(body_chunks)

    class Connection:
        def __init__(self, host, port, timeout):
            assert (host, port, timeout) == ("data.kric.go.kr", 443, 20.0)

        def request(self, method, path, headers):
            assert method == "GET"
            assert path == "/rips/dataset/download.file?type=filedata&id=32&operation=1"
            assert headers == {
                "Accept": "*/*",
                "User-Agent": "HomeSearchReferenceImporter/1.0",
            }

        def getresponse(self):
            return Response()

        def close(self):
            pass

    monkeypatch.setattr(file_snapshot_client, "HTTPSConnection", Connection)
    collected = FileSnapshotClient(
        source_id="transport.rail-station",
        url="https://data.kric.go.kr/rips/dataset/download.file",
        fixed_query="type=filedata&id=32&operation=1",
        allowed_hosts=("data.kric.go.kr",),
        allowed_path_prefixes=("/rips/dataset/download.file",),
        media_types=("application/octet-stream",), extension="xlsx",
        maximum_bytes=1024, allow_one_redirect=True,
    ).collect(target=tmp_path / "rail.xlsx")

    assert collected.source_date == date(2026, 6, 30)


def test_redirect_cannot_escape_allowlist_or_chain(tmp_path) -> None:
    client = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",),
        extension="csv",
        maximum_bytes=1024,
        allow_one_redirect=True,
        requester=lambda *_args: (302, {"location": "https://evil.invalid/snapshot.csv"}, b""),
    )

    with pytest.raises(FileSnapshotError) as error:
        client.collect(target=tmp_path / "snapshot.csv")
    assert error.value.reason_code == "FILE_REDIRECT_REJECTED"


def test_default_transport_streams_chunks_to_owner_only_file(monkeypatch, tmp_path) -> None:
    body_chunks = iter((b"header\n", b"value\n", b""))

    class Response:
        status = 200

        def getheaders(self):
            return [
                ("content-type", "text/csv"),
                ("content-length", "13"),
                ("content-disposition", 'attachment; filename="snapshot-20260718.csv"'),
            ]

        def read(self, _size):
            return next(body_chunks)

    class Connection:
        def __init__(self, host, port, timeout):
            assert (host, port, timeout) == ("file.localdata.go.kr", 443, 20.0)

        def request(self, method, path, headers):
            assert method == "GET"
            assert path.startswith("/file/download/large_scale_retail_stores/")
            assert headers == {
                "Accept": "*/*",
                "Referer": "https://file.localdata.go.kr/file/large_scale_retail_stores/info",
                "User-Agent": "HomeSearchReferenceImporter/1.0",
            }

        def getresponse(self):
            return Response()

        def close(self):
            pass

    monkeypatch.setattr(file_snapshot_client, "HTTPSConnection", Connection)
    target = tmp_path / "snapshot.csv"
    collected = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/download/large_scale_retail_stores/info",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/download/large_scale_retail_stores/",),
        media_types=("text/csv",), extension="csv", maximum_bytes=1024,
        allow_one_redirect=True,
        referer_url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
    ).collect(target=target)

    assert collected.byte_length == 13
    assert target.read_bytes() == b"header\nvalue\n"
    assert target.stat().st_mode & 0o077 == 0


def test_file_client_rejects_invalid_configuration_target_and_transport(tmp_path) -> None:
    with pytest.raises(ValueError):
        FileSnapshotError("secret")
    with pytest.raises(ValueError):
        FileSnapshotClient(
            source_id="", url="https://file.localdata.go.kr/file/x.csv",
            allowed_hosts=("file.localdata.go.kr",), allowed_path_prefixes=("/file/",),
            media_types=("text/csv",), extension="csv", maximum_bytes=1,
            allow_one_redirect=False,
        )
    existing = tmp_path / "exists.csv"
    existing.write_bytes(b"")
    client = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/snapshot-20260718.csv",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",), extension="csv", maximum_bytes=4,
        allow_one_redirect=False, requester=lambda *_args: (200, {}, b"five!"),
    )
    with pytest.raises(ValueError):
        client.collect(target=existing)
    with pytest.raises(FileSnapshotError) as error:
        client.collect(target=tmp_path / "large.csv")
    assert error.value.reason_code == "FILE_TOO_LARGE"

    failing = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/snapshot-20260718.csv",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",), extension="csv", maximum_bytes=10,
        allow_one_redirect=False,
        requester=lambda *_args: (_ for _ in ()).throw(OSError()),
    )
    with pytest.raises(FileSnapshotError) as error:
        failing.collect(target=tmp_path / "transport.csv")
    assert error.value.reason_code == "FILE_TRANSPORT_FAILED"


def test_file_response_rejects_bad_status_oversized_header_and_redirect_chain(tmp_path) -> None:
    def client(requester, *, maximum=10, redirect=True):
        return FileSnapshotClient(
            source_id="retail.large-store",
            url="https://file.localdata.go.kr/file/large_scale_retail_stores/info",
            allowed_hosts=("file.localdata.go.kr",),
            allowed_path_prefixes=("/file/large_scale_retail_stores/",),
            media_types=("text/csv",), extension="csv", maximum_bytes=maximum,
            allow_one_redirect=redirect, requester=requester,
        )

    with pytest.raises(FileSnapshotError) as error:
        client(lambda *_args: (404, {}, b"no")).collect(target=tmp_path / "bad.csv")
    assert error.value.reason_code == "FILE_BAD_RESPONSE"

    headers = {
        "content-type": "text/csv", "content-length": "11",
        "content-disposition": 'filename="snapshot-20260718.csv"',
    }
    with pytest.raises(FileSnapshotError) as error:
        client(lambda *_args: (200, headers, b"four"), maximum=10).collect(
            target=tmp_path / "header.csv"
        )
    assert error.value.reason_code == "FILE_TOO_LARGE"

    with pytest.raises(FileSnapshotError) as error:
        client(
            lambda *_args: (302, {"location": "/file/large_scale_retail_stores/next"}, b""),
            redirect=False,
        ).collect(target=tmp_path / "redirect.csv")
    assert error.value.reason_code == "FILE_REDIRECT_REJECTED"


def test_invalid_content_length_and_stream_failures_cleanup_partial_file(monkeypatch, tmp_path) -> None:
    invalid_length = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/snapshot-20260718.csv",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",), extension="csv", maximum_bytes=10,
        allow_one_redirect=False,
        requester=lambda *_args: (200, {"content-type": "text/csv", "content-length": "NaN"}, b"x"),
    )
    with pytest.raises(FileSnapshotError) as error:
        invalid_length.collect(target=tmp_path / "invalid.csv")
    assert error.value.reason_code == "FILE_LENGTH_INVALID"

    class Response:
        status = 200

        def getheaders(self):
            return [("content-type", "text/csv"), ("content-length", "20")]

        def read(self, _size):
            return b"too-large-stream"

    class Connection:
        def __init__(self, *_args, **_kwargs):
            pass

        def request(self, *_args, **_kwargs):
            pass

        def getresponse(self):
            return Response()

        def close(self):
            pass

    monkeypatch.setattr(file_snapshot_client, "HTTPSConnection", Connection)
    streaming = FileSnapshotClient(
        source_id="retail.large-store",
        url="https://file.localdata.go.kr/file/large_scale_retail_stores/snapshot-20260718.csv",
        allowed_hosts=("file.localdata.go.kr",),
        allowed_path_prefixes=("/file/large_scale_retail_stores/",),
        media_types=("text/csv",), extension="csv", maximum_bytes=4,
        allow_one_redirect=False,
    )
    target = tmp_path / "partial.csv"
    with pytest.raises(FileSnapshotError) as error:
        streaming.collect(target=target)
    assert error.value.reason_code == "FILE_TOO_LARGE"
    assert not target.exists()

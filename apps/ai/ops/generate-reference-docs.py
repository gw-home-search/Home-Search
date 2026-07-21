#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path

PRIORITY_SOURCES = (
    "edu.school-location", "edu.academy-registry", "place.sbiz-academy",
    "retail.large-store", "transport.rail-station",
)
API_PARAMETERS = {
    "edu.school-location": ("serviceKey", "pageNo", "numOfRows", "type"),
    "edu.academy-registry": ("KEY", "Type", "pIndex", "pSize", "ATPT_OFCDC_SC_CODE"),
    "place.sbiz-academy": ("serviceKey", "divId", "key", "indsSclsCd", "pageNo", "numOfRows", "type"),
}
NORMALIZATION_FIELDS = {
    "edu.school-location": ("school_id", "school_name", "school_level", "operating_status", "latitude", "longitude"),
    "edu.academy-registry": ("academy_id", "education_office_code", "district_name", "academy_type", "status"),
    "place.sbiz-academy": ("store_id", "name", "small_category_code", "road_address", "postal_code", "latitude", "longitude"),
    "retail.large-store": ("facility_id", "subcategory", "status", "road_address", "latitude", "longitude"),
    "transport.rail-station": ("station_occurrence_id", "operator", "line_number", "station_name", "latitude", "longitude"),
}
FAILURE_CODES = {
    "edu.school-location": ("API_TRANSPORT_FAILED", "API_PAGINATION_INVALID", "API_BUNDLE_TOO_LARGE"),
    "edu.academy-registry": ("API_TRANSPORT_FAILED", "PROVIDER_TOTAL_COUNT_MISMATCH", "PROVIDER_COVERAGE_INCOMPLETE"),
    "place.sbiz-academy": ("TAXONOMY_CHANGED", "PROVIDER_TOTAL_COUNT_MISMATCH", "DUPLICATE_STORE_ID"),
    "retail.large-store": ("FILE_TRANSPORT_FAILED", "FILE_MEDIA_TYPE_INVALID", "FILE_LENGTH_MISMATCH", "SOURCE_SCHEMA_MISMATCH", "KOREA_COORDINATE_OUT_OF_RANGE"),
    "transport.rail-station": ("SOURCE_DATE_UNVERIFIED", "XLSX_MACRO_REJECTED", "RAIL_STATION_COORDINATE_REQUIRED"),
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--examples", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.config, args.examples, args.output)


def generate(config_path: Path, examples: Path, output: Path) -> None:
    document = tomllib.loads(config_path.read_text(encoding="utf-8"))
    by_id = {source["id"]: source for source in document["sources"]}
    if set(PRIORITY_SOURCES) - set(by_id):
        raise ValueError("priority source contract is missing")
    _clear_directory(output)
    output.mkdir(parents=True, mode=0o755, exist_ok=True)
    hashes: dict[str, str] = {}
    for source_id in PRIORITY_SOURCES:
        source = by_id[source_id]
        target = output / source_id
        target.mkdir(mode=0o755)
        for name, content in _snippets(source_id, source, examples).items():
            path = target / name
            path.write_text(content, encoding="utf-8", newline="\n")
            hashes[str(path.relative_to(output))] = _sha256(path)
    manifest = {
        "schemaVersion": 1,
        "sources": list(PRIORITY_SOURCES),
        "contractSha256": _sha256(config_path),
        "files": dict(sorted(hashes.items())),
    }
    manifest_path = output.parent / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    lines = [f"{value}  generated-snippets/{name}" for name, value in sorted(hashes.items())]
    lines.append(f"{_sha256(manifest_path)}  manifest.json")
    (output.parent / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _snippets(source_id: str, source: dict[str, object], examples: Path) -> dict[str, str]:
    acquisition = source["acquisition"]
    temporal = source["temporal"]
    assert isinstance(acquisition, dict) and isinstance(temporal, dict)
    mode = acquisition["mode"]
    request_name = "http-request.adoc" if mode == "api" else "download-request.adoc"
    fixed_query = acquisition.get("fixed_query", "")
    request_target = f"GET {acquisition['base_url']}"
    if fixed_query:
        request_target += f"?{fixed_query}"
    referer_url = acquisition.get("referer_url", "")
    if referer_url:
        request_target += f"\nReferer: {referer_url}"
    result = {
        request_name: f"[source,text]\n----\n{request_target}\n----\n",
        "http-response.adoc": f"[source,text]\n----\n{(examples / f'{source_id}.txt').read_text(encoding='utf-8').rstrip()}\n----\n",
        "response-fields.adoc": f"* provider: `{source['provider']}`\n* format: `{acquisition['format']}`\n* temporal basis: `{temporal['basis']}`\n",
        "normalization-fields.adoc": "\n".join(f"* `{field}`" for field in NORMALIZATION_FIELDS[source_id]) + "\n",
        "failure-codes.adoc": "\n".join(f"* `{code}`" for code in FAILURE_CODES[source_id]) + "\n",
        "cli-output.adoc": f"[source,text]\n----\n상태: Pass|NoChange|Fail\nsourceId: {source_id}\nreasonCodes: <safe-codes-only>\n----\n",
    }
    if mode == "api":
        result["request-parameters.adoc"] = "\n".join(
            f"* `{name}`: provider contract parameter" for name in API_PARAMETERS[source_id]
        ) + "\n"
    else:
        result["response-headers.adoc"] = "* `Content-Type`: source contract media type\n* `Content-Length`: configured bound 이하\n"
        result["source-columns.adoc"] = result["normalization-fields.adoc"]
    return result


def _clear_directory(path: Path) -> None:
    if not path.exists():
        return
    for child in sorted(path.rglob("*"), reverse=True):
        child.unlink() if child.is_file() or child.is_symlink() else child.rmdir()
    path.rmdir()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    main()

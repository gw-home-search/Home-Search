from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable, Mapping
from datetime import date, datetime


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def normalized_dataset_checksum(
    *,
    source_id: str,
    normalization_schema_version: str,
    temporal_value: date | datetime,
    rows: Iterable[Mapping[str, object]],
) -> str:
    if not source_id.strip() or not normalization_schema_version.strip():
        raise ValueError("normalized checksum identity is required")
    row_hashes = sorted(
        hashlib.sha256(canonical_json_bytes(dict(row))).hexdigest() for row in rows
    )
    temporal_type = "OBSERVED_AT" if isinstance(temporal_value, datetime) else "SOURCE_DATE"
    document = {
        "sourceId": source_id,
        "normalizationSchemaVersion": normalization_schema_version,
        "temporalBasis": temporal_type,
        "temporalValue": temporal_value.isoformat(),
        "rowCount": len(row_hashes),
        "rowHashes": row_hashes,
    }
    return hashlib.sha256(canonical_json_bytes(document)).hexdigest()

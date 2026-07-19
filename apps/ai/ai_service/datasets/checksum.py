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
    semantic_temporal_value = (
        temporal_value.date().isoformat()
        if isinstance(temporal_value, datetime)
        else temporal_value.isoformat()
    )
    return normalized_dataset_checksum_from_hashes(
        source_id=source_id,
        normalization_schema_version=normalization_schema_version,
        temporal_basis=temporal_type,
        semantic_temporal_value=semantic_temporal_value,
        row_hashes=row_hashes,
    )


def normalized_dataset_checksum_from_hashes(
    *,
    source_id: str,
    normalization_schema_version: str,
    temporal_basis: str,
    semantic_temporal_value: str,
    row_hashes: Iterable[str],
) -> str:
    if temporal_basis not in {"SOURCE_DATE", "OBSERVED_AT"}:
        raise ValueError("normalized checksum temporal basis is invalid")
    sorted_hashes = sorted(row_hashes)
    document = {
        "sourceId": source_id,
        "normalizationSchemaVersion": normalization_schema_version,
        "temporalBasis": temporal_basis,
        "temporalValue": semantic_temporal_value,
        "rowCount": len(sorted_hashes),
        "rowHashes": sorted_hashes,
    }
    return hashlib.sha256(canonical_json_bytes(document)).hexdigest()

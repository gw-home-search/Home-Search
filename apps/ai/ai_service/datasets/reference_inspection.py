from __future__ import annotations

import argparse
import os
from collections.abc import Mapping, Sequence

import psycopg
from psycopg.conninfo import conninfo_to_dict
from psycopg.rows import dict_row


class ReferenceInspectionConfigurationError(RuntimeError):
    pass


def status_run(arguments: Sequence[str], environment: Mapping[str, str]) -> int:
    parser = argparse.ArgumentParser(prog="home-ai-reference-status")
    parser.add_argument("--source")
    parsed = parser.parse_args(arguments)
    dsn = _runtime_dsn(environment)
    with psycopg.connect(
        dsn,
        row_factory=dict_row,
        options="-c default_transaction_read_only=on -c statement_timeout=3000",
    ) as connection:
        _validate_connection(connection)
        rows = connection.execute(
            """
            SELECT source_id, dataset_version, temporal_basis, source_date,
                   observed_at, published_at
            FROM reference_read.source_status
            WHERE (%s::text IS NULL OR source_id = %s)
            ORDER BY source_id
            """,
            (parsed.source, parsed.source),
        ).fetchall()
    if parsed.source and not rows:
        print("상태: Fail")
        print(f"sourceId: {parsed.source}")
        print("reasonCodes: SOURCE_NOT_FOUND")
        return 1
    for row in rows:
        print(f"상태: {'Pass' if row['dataset_version'] else 'Partial'}")
        print(f"sourceId: {row['source_id']}")
        print(f"datasetVersion: {row['dataset_version'] or ''}")
        data_as_of = row["source_date"] or row["observed_at"]
        print(f"dataAsOf: {data_as_of.isoformat() if data_as_of else ''}")
    return 0


def audit_run(arguments: Sequence[str], environment: Mapping[str, str]) -> int:
    parser = argparse.ArgumentParser(prog="home-ai-reference-audit")
    parser.add_argument("--source", required=True)
    parser.add_argument("--limit", type=int, default=20)
    parsed = parser.parse_args(arguments)
    if not 1 <= parsed.limit <= 100:
        raise ReferenceInspectionConfigurationError("audit limit is outside the safe bound")
    dsn = _runtime_dsn(environment)
    with psycopg.connect(
        dsn,
        row_factory=dict_row,
        options="-c default_transaction_read_only=on -c statement_timeout=3000",
    ) as connection:
        _validate_connection(connection)
        rows = connection.execute(
            """
            SELECT acquisition_id, status, raw_row_count, accepted_row_count,
                   rejected_row_count, collected_at, reason_codes
            FROM reference_read.acquisition_audit
            WHERE source_id = %s
            ORDER BY collected_at DESC
            LIMIT %s
            """,
            (parsed.source, parsed.limit),
        ).fetchall()
    for row in rows:
        print(f"acquisitionId: {row['acquisition_id'] or ''}")
        print(f"상태: {row['status']}")
        print(f"rawRowCount: {row['raw_row_count']}")
        print(f"acceptedRowCount: {row['accepted_row_count']}")
        print(f"rejectedRowCount: {row['rejected_row_count']}")
        print(f"reasonCodes: {','.join(row['reason_codes'])}")
    return 0 if rows else 1


def status_main() -> None:
    try:
        code = status_run(os.sys.argv[1:], os.environ)
    except (ReferenceInspectionConfigurationError, psycopg.Error):
        print("상태: Fail")
        print("reasonCodes: INSPECTION_UNAVAILABLE")
        code = 2
    raise SystemExit(code)


def audit_main() -> None:
    try:
        code = audit_run(os.sys.argv[1:], os.environ)
    except (ReferenceInspectionConfigurationError, psycopg.Error):
        print("상태: Fail")
        print("reasonCodes: INSPECTION_UNAVAILABLE")
        code = 2
    raise SystemExit(code)


def _runtime_dsn(environment: Mapping[str, str]) -> str:
    dsn = environment.get("HOME_AI_REFERENCE_RUNTIME_DSN", "").strip()
    try:
        parameters = conninfo_to_dict(dsn)
    except psycopg.Error:
        raise ReferenceInspectionConfigurationError("runtime DSN is invalid") from None
    if (
        not dsn
        or parameters.get("dbname") != "home_search_ai"
        or parameters.get("user") != "home_search_ai_runtime"
    ):
        raise ReferenceInspectionConfigurationError("runtime DSN role boundary is invalid")
    return dsn


def _validate_connection(connection: psycopg.Connection[dict_row]) -> None:
    if connection.info.dbname != "home_search_ai" or connection.info.user != "home_search_ai_runtime":
        raise ReferenceInspectionConfigurationError("runtime connection identity is invalid")

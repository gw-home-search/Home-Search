#!/usr/bin/env python3
"""Resumable, allowlisted Property and Reference data-only migration."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterator


IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]*$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MIGRATION_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}$")
SAFE_FILE = re.compile(r"^[a-z0-9][a-z0-9._-]*[.]csv[.]zst$")
FORBIDDEN_TABLE = re.compile(
    r"(^|_)(user_account|admin_account|session|token|flyway_schema_history|ai_schema_history|batch_job)(_|$)"
)


class MigrationError(RuntimeError):
    pass


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dataset_name(item: dict[str, Any]) -> str:
    return f"{item['logicalDatabase']}:{item['schema']}.{item['table']}"


def validate_identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise MigrationError(f"invalid {label}: {value!r}")
    return value


def validate_catalog(catalog: dict[str, Any]) -> dict[str, Any]:
    if catalog.get("formatVersion") != 1 or not isinstance(catalog.get("datasets"), list):
        raise MigrationError("catalog formatVersion/datasets is invalid")
    if not catalog["datasets"]:
        raise MigrationError("catalog datasets must not be empty")
    orders: set[int] = set()
    names: set[str] = set()
    previous_order = -1
    for item in catalog["datasets"]:
        if not isinstance(item, dict):
            raise MigrationError("catalog dataset must be an object")
        order = item.get("order")
        if not isinstance(order, int) or order <= previous_order or order in orders:
            raise MigrationError("catalog dataset order must be unique and strictly increasing")
        previous_order = order
        orders.add(order)
        logical = item.get("logicalDatabase")
        if logical not in {"property", "reference"}:
            raise MigrationError(f"unsupported logical database: {logical}")
        if item.get("conflictPolicy") not in {"reject", "update"}:
            raise MigrationError(f"invalid conflictPolicy for {logical}:{item.get('table')}")
        schema = validate_identifier(item.get("schema"), "schema")
        table = validate_identifier(item.get("table"), "table")
        if FORBIDDEN_TABLE.search(table):
            raise MigrationError(f"forbidden data-only table: {schema}.{table}")
        name = dataset_name(item)
        if name in names:
            raise MigrationError(f"duplicate catalog dataset: {name}")
        names.add(name)
        columns = item.get("columns")
        keys = item.get("keyColumns")
        if not isinstance(columns, list) or not columns or not isinstance(keys, list) or not keys:
            raise MigrationError(f"columns/keyColumns are required for {name}")
        normalized_columns = [validate_identifier(value, "column") for value in columns]
        normalized_keys = [validate_identifier(value, "key column") for value in keys]
        if len(set(normalized_columns)) != len(normalized_columns) or len(set(normalized_keys)) != len(normalized_keys):
            raise MigrationError(f"duplicate column in {name}")
        if not set(normalized_keys).issubset(normalized_columns):
            raise MigrationError(f"key column is not allowlisted for {name}")
        chunk_key = item.get("chunkKey")
        if chunk_key is not None:
            chunk_key = validate_identifier(chunk_key, "chunk key")
            if chunk_key not in normalized_columns:
                raise MigrationError(f"chunk key is not allowlisted for {name}")
            if item.get("chunkKeyType") not in {"bigint", "date", "text", "uuid"}:
                raise MigrationError(f"invalid chunkKeyType for {name}")
            chunk_rows = item.get("chunkRows")
            if not isinstance(chunk_rows, int) or chunk_rows < 1 or chunk_rows > 5_000_000:
                raise MigrationError(f"invalid chunkRows for {name}")
        elif item.get("chunkRows") is not None:
            raise MigrationError(f"chunkRows requires chunkKey for {name}")
    return catalog


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise MigrationError(f"cannot read catalog: {path}") from exception
    if not isinstance(value, dict):
        raise MigrationError("catalog root must be an object")
    return validate_catalog(value)


def catalog_sha256(path: Path) -> str:
    return hashlib.sha256(canonical_json(load_catalog(path))).hexdigest()


def validate_manifest(manifest: dict[str, Any], allowed_datasets: set[str]) -> dict[str, Any]:
    if manifest.get("formatVersion") != 1 or not MIGRATION_ID.fullmatch(str(manifest.get("migrationId", ""))):
        raise MigrationError("manifest formatVersion/migrationId is invalid")
    if not SHA256.fullmatch(str(manifest.get("catalogSha256", ""))):
        raise MigrationError("manifest catalog checksum is invalid")
    chunks = manifest.get("chunks")
    datasets = manifest.get("datasets")
    if not isinstance(chunks, list) or not isinstance(datasets, list):
        raise MigrationError("manifest datasets/chunks must be arrays")
    summarized: set[str] = set()
    for summary in datasets:
        if (
            not isinstance(summary, dict)
            or summary.get("dataset") not in allowed_datasets
            or summary.get("dataset") in summarized
            or not isinstance(summary.get("rowCount"), int)
            or summary["rowCount"] < 0
        ):
            raise MigrationError("manifest dataset summary is invalid")
        summarized.add(summary["dataset"])
    if summarized != allowed_datasets:
        raise MigrationError("manifest dataset summary does not equal the allowlist")
    seen_files: set[str] = set()
    chunk_rows = {name: 0 for name in allowed_datasets}
    for chunk in chunks:
        if not isinstance(chunk, dict) or chunk.get("dataset") not in allowed_datasets:
            raise MigrationError("manifest contains an unexpected dataset")
        filename = chunk.get("file")
        if not isinstance(filename, str) or not SAFE_FILE.fullmatch(filename) or filename in seen_files:
            raise MigrationError("manifest chunk file is invalid")
        seen_files.add(filename)
        for key in ("sha256", "csvSha256"):
            if not SHA256.fullmatch(str(chunk.get(key, ""))):
                raise MigrationError(f"manifest {key} is invalid")
        if not isinstance(chunk.get("rowCount"), int) or chunk["rowCount"] < 0:
            raise MigrationError("manifest rowCount is invalid")
        if chunk.get("lowerExclusive") is not None and not isinstance(chunk.get("lowerExclusive"), str):
            raise MigrationError("manifest lowerExclusive is invalid")
        if chunk.get("minKey") is not None and not isinstance(chunk.get("minKey"), str):
            raise MigrationError("manifest minKey is invalid")
        if chunk.get("maxKey") is not None and not isinstance(chunk.get("maxKey"), str):
            raise MigrationError("manifest maxKey is invalid")
        if not isinstance(chunk.get("sourceWatermark"), str) or not chunk["sourceWatermark"]:
            raise MigrationError("manifest sourceWatermark is invalid")
        chunk_rows[chunk["dataset"]] += chunk["rowCount"]
    summarized_rows = {summary["dataset"]: summary["rowCount"] for summary in datasets}
    if chunk_rows != summarized_rows:
        raise MigrationError("manifest chunk row counts do not equal dataset summaries")
    return manifest


def load_manifest(path: Path, allowed_datasets: set[str]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise MigrationError(f"cannot read manifest: {path}") from exception
    if not isinstance(value, dict):
        raise MigrationError("manifest root must be an object")
    return validate_manifest(value, allowed_datasets)


def validate_manifest_artifacts(path: Path, expected_catalog_sha256: str, allowed_datasets: set[str] | None = None) -> dict[str, Any]:
    if allowed_datasets is None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            allowed_datasets = {str(chunk.get("dataset")) for chunk in raw.get("chunks", [])}
        except (OSError, json.JSONDecodeError, AttributeError) as exception:
            raise MigrationError(f"cannot read manifest: {path}") from exception
    manifest = load_manifest(path, allowed_datasets)
    if manifest["catalogSha256"] != expected_catalog_sha256:
        raise MigrationError("catalog checksum mismatch")
    root = path.resolve().parent
    for chunk in manifest["chunks"]:
        artifact = (root / chunk["file"]).resolve()
        if artifact.parent != root or not artifact.is_file():
            raise MigrationError(f"chunk is missing: {chunk['file']}")
        if sha256_file(artifact) != chunk["sha256"]:
            raise MigrationError(f"chunk checksum mismatch: {chunk['file']}")
    return manifest


def quote_identifier(value: str) -> str:
    validate_identifier(value, "SQL identifier")
    return f'"{value}"'


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


class PostgresConnection:
    def __init__(self, logical: str, direction: str):
        prefix = f"HOME_MIGRATION_{logical.upper()}_{direction.upper()}_"
        self.host = required_environment(prefix + "HOST")
        self.port = os.environ.get(prefix + "PORT", "5432")
        self.database = required_environment(prefix + "DATABASE")
        self.user = required_environment(prefix + "USER")
        self.password = required_environment(prefix + "PASSWORD")

    def args(self) -> list[str]:
        return ["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-h", self.host, "-p", self.port, "-U", self.user, "-d", self.database]

    def environment(self) -> dict[str, str]:
        environment = sanitized_environment()
        environment["PGPASSWORD"] = self.password
        return environment


def required_environment(name: str) -> str:
    value = os.environ.get(name)
    if value is None or not value.strip():
        raise MigrationError(f"required environment is missing: {name}")
    return value.strip()


def sanitized_environment() -> dict[str, str]:
    return {
        key: value
        for key, value in os.environ.items()
        if not (key.startswith("HOME_MIGRATION_") and key.endswith("_PASSWORD"))
    }


def zstd_threads(environment: dict[str, str] | None = None) -> str:
    source = os.environ if environment is None else environment
    value = source.get("HOME_MIGRATION_ZSTD_THREADS", "1")
    if not value.isdigit() or not 1 <= int(value) <= 8:
        raise MigrationError("HOME_MIGRATION_ZSTD_THREADS must be an integer from 1 to 8")
    return value


@contextmanager
def exported_snapshot(connection: PostgresConnection) -> Iterator[tuple[str, str]]:
    process = subprocess.Popen(
        connection.args(), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=connection.environment(), text=True, bufsize=1,
    )
    assert process.stdin is not None and process.stdout is not None
    process.stdin.write("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n")
    process.stdin.write("SELECT pg_export_snapshot();\nSELECT pg_current_wal_lsn();\n")
    process.stdin.flush()
    snapshot = process.stdout.readline().strip()
    watermark = process.stdout.readline().strip()
    if not snapshot or not watermark:
        process.terminate()
        stderr = process.stderr.read() if process.stderr else ""
        raise MigrationError(f"failed to export PostgreSQL snapshot: {stderr.strip()}")
    try:
        yield snapshot, watermark
    finally:
        try:
            process.stdin.write("ROLLBACK;\n\\q\n")
            process.stdin.flush()
        except BrokenPipeError:
            pass
        process.wait(timeout=15)
        if process.returncode != 0:
            stderr = process.stderr.read() if process.stderr else ""
            raise MigrationError(f"snapshot holder failed: {stderr.strip()}")


def snapshot_sql(snapshot: str, statement: str) -> str:
    return (
        "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;"
        f"SET TRANSACTION SNAPSHOT {sql_literal(snapshot)};"
        f"{statement};COMMIT;"
    )


def run_psql(connection: PostgresConnection, statement: str) -> str:
    result = subprocess.run(connection.args() + ["-c", statement], env=connection.environment(), check=False, capture_output=True, text=True)
    if result.returncode != 0:
        raise MigrationError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


def actual_columns(connection: PostgresConnection, item: dict[str, Any]) -> list[str]:
    sql = (
        "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns "
        f"WHERE table_schema={sql_literal(item['schema'])} AND table_name={sql_literal(item['table'])}"
    )
    value = run_psql(connection, sql)
    return [] if not value else value.split(",")


def assert_schema_matches(connection: PostgresConnection, item: dict[str, Any]) -> None:
    actual = actual_columns(connection, item)
    if actual != item["columns"]:
        raise MigrationError(
            f"unexpected table/column shape for {dataset_name(item)} "
            f"expected={','.join(item['columns'])} actual={','.join(actual)}"
        )


def predicate_for_item(item: dict[str, Any], lower: str | None, upper: str | None) -> str:
    key = item.get("chunkKey")
    if key is None:
        return "TRUE"
    quoted = chunk_key_expression(item)
    key_type = item["chunkKeyType"]
    clauses = []
    if lower is not None:
        clauses.append(f"{quoted} > {sql_literal(lower)}::{key_type}")
    if upper is not None:
        clauses.append(f"{quoted} <= {sql_literal(upper)}::{key_type}")
    return " AND ".join(clauses) if clauses else "TRUE"


def qualified(item: dict[str, Any]) -> str:
    return f"{quote_identifier(item['schema'])}.{quote_identifier(item['table'])}"


def ordered_columns(item: dict[str, Any]) -> str:
    return ",".join(quote_identifier(column) for column in item["columns"])


def order_clause(item: dict[str, Any]) -> str:
    return ",".join(quote_identifier(column) for column in item["keyColumns"])


def next_boundary(connection: PostgresConnection, snapshot: str, item: dict[str, Any], lower: str | None) -> str | None:
    raw_key = quote_identifier(item["chunkKey"])
    key = chunk_key_expression(item)
    key_type = item["chunkKeyType"]
    lower_sql = "TRUE" if lower is None else f"{key} > {sql_literal(lower)}::{key_type}"
    statement = (
        f"SELECT (array_agg(boundary.chunk_key::text ORDER BY boundary.chunk_key DESC))[1] "
        f"FROM (SELECT {key} AS chunk_key FROM {qualified(item)} WHERE {lower_sql} "
        f"ORDER BY {raw_key} LIMIT {item['chunkRows']}) boundary"
    )
    value = run_psql(connection, snapshot_sql(snapshot, statement))
    return value or None


def chunk_key_expression(item: dict[str, Any]) -> str:
    key = quote_identifier(item["chunkKey"])
    return f"{key}::text" if item["chunkKeyType"] == "text" else key


def copy_query(item: dict[str, Any], lower: str | None, upper: str | None) -> str:
    return (
        f"SELECT {ordered_columns(item)} FROM {qualified(item)} "
        f"WHERE {predicate_for_item(item, lower, upper)} ORDER BY {order_clause(item)}"
    )


def export_csv_zstd(connection: PostgresConnection, snapshot: str, query: str, destination: Path) -> str:
    partial = destination.with_name(destination.name + ".partial")
    if partial.exists():
        raise MigrationError(f"partial chunk already exists: {partial.name}")
    psql = subprocess.Popen(
        connection.args() + ["-c", snapshot_sql(snapshot, f"COPY ({query}) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)")],
        env=connection.environment(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    zstd = subprocess.Popen(
        ["zstd", "-q", f"-T{zstd_threads()}", "-3", "-o", str(partial)],
        stdin=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=sanitized_environment(),
    )
    assert psql.stdout is not None and zstd.stdin is not None
    digest = hashlib.sha256()
    try:
        for block in iter(lambda: psql.stdout.read(1024 * 1024), b""):
            digest.update(block)
            zstd.stdin.write(block)
        zstd.stdin.close()
    except (BrokenPipeError, OSError) as exception:
        psql.stdout.close()
        for process in (psql, zstd):
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        partial.unlink(missing_ok=True)
        raise MigrationError(f"chunk export pipeline failed: {destination.name}") from exception
    psql_error = psql.stderr.read().decode(errors="replace") if psql.stderr else ""
    zstd_error = zstd.stderr.read().decode(errors="replace") if zstd.stderr else ""
    psql_code = psql.wait()
    zstd_code = zstd.wait()
    if psql_code != 0 or zstd_code != 0:
        partial.unlink(missing_ok=True)
        raise MigrationError(f"chunk export failed: {psql_error.strip()} {zstd_error.strip()}")
    partial.replace(destination)
    return digest.hexdigest()


def chunk_row_count(connection: PostgresConnection, snapshot: str, item: dict[str, Any], lower: str | None, upper: str | None) -> int:
    value = run_psql(connection, snapshot_sql(snapshot, f"SELECT count(*) FROM {qualified(item)} WHERE {predicate_for_item(item, lower, upper)}"))
    return int(value)


def chunk_min_key(connection: PostgresConnection, snapshot: str, item: dict[str, Any], lower: str | None, upper: str | None) -> str | None:
    if not item.get("chunkKey"):
        return None
    key = chunk_key_expression(item)
    raw_key = quote_identifier(item["chunkKey"])
    value = run_psql(
        connection,
        snapshot_sql(
            snapshot,
            f"SELECT {key}::text FROM {qualified(item)} WHERE {predicate_for_item(item, lower, upper)} "
            f"ORDER BY {raw_key} LIMIT 1",
        ),
    )
    return value or None


def export_dataset(connection: PostgresConnection, snapshot: str, watermark: str, item: dict[str, Any], output: Path) -> tuple[list[dict[str, Any]], int]:
    assert_schema_matches(connection, item)
    chunks: list[dict[str, Any]] = []
    lower: str | None = None
    index = 1
    while True:
        upper = next_boundary(connection, snapshot, item, lower) if item.get("chunkKey") else None
        if item.get("chunkKey") and upper is None:
            break
        count = chunk_row_count(connection, snapshot, item, lower, upper)
        if count == 0:
            break
        filename = f"{item['logicalDatabase']}-{item['schema']}-{item['table']}-{index:06d}.csv.zst"
        destination = output / filename
        if destination.exists():
            raise MigrationError(f"immutable chunk already exists: {filename}")
        csv_sha = export_csv_zstd(connection, snapshot, copy_query(item, lower, upper), destination)
        chunks.append({
            "dataset": dataset_name(item), "file": filename, "sha256": sha256_file(destination),
            "csvSha256": csv_sha, "rowCount": count,
            "lowerExclusive": lower, "minKey": chunk_min_key(connection, snapshot, item, lower, upper), "maxKey": upper,
            "sourceWatermark": watermark,
        })
        if not item.get("chunkKey"):
            break
        lower = upper
        index += 1
    return chunks, sum(chunk["rowCount"] for chunk in chunks)


def upload_artifacts(paths: list[Path], s3_uri: str, kms_key_id: str) -> None:
    if not s3_uri.startswith("s3://") or ".." in s3_uri:
        raise MigrationError("invalid S3 URI")
    if not kms_key_id:
        raise MigrationError("S3 upload requires a KMS key id")
    for path in paths:
        subprocess.run([
            "aws", "s3", "cp", str(path), f"{s3_uri.rstrip('/')}/{path.name}", "--only-show-errors",
            "--sse", "aws:kms", "--sse-kms-key-id", kms_key_id,
        ], check=True, env=sanitized_environment())


def validate_s3_options(s3_uri: str | None, kms_key_id: str | None) -> None:
    if bool(s3_uri) != bool(kms_key_id):
        raise MigrationError("S3 URI and KMS key id must be provided together")


def export_all(catalog_path: Path, output: Path, s3_uri: str | None, kms_key_id: str | None) -> Path:
    validate_s3_options(s3_uri, kms_key_id)
    catalog = load_catalog(catalog_path)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise MigrationError("export output directory must be empty")
    migration_id = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(8)
    manifest: dict[str, Any] = {
        "formatVersion": 1, "migrationId": migration_id, "createdAt": datetime.now(UTC).isoformat(),
        "catalogSha256": catalog_sha256(catalog_path), "sourceWatermarks": {}, "datasets": [], "chunks": [],
    }
    for logical in ("property", "reference"):
        items = [item for item in catalog["datasets"] if item["logicalDatabase"] == logical]
        if not items:
            continue
        connection = PostgresConnection(logical, "source")
        with exported_snapshot(connection) as (snapshot, watermark):
            manifest["sourceWatermarks"][logical] = watermark
            for item in items:
                chunks, row_count = export_dataset(connection, snapshot, watermark, item, output)
                manifest["chunks"].extend(chunks)
                manifest["datasets"].append({"dataset": dataset_name(item), "rowCount": row_count})
    manifest_path = output / "data-only-manifest.json"
    manifest_path.write_bytes(canonical_json(manifest))
    if s3_uri:
        upload_artifacts([*(output / chunk["file"] for chunk in manifest["chunks"]), manifest_path], s3_uri, kms_key_id or "")
    return manifest_path


def import_chunk(connection: PostgresConnection, item: dict[str, Any], chunk: dict[str, Any], artifact: Path) -> None:
    temp = f"migration_chunk_{secrets.token_hex(6)}"
    columns = ordered_columns(item)
    keys = ",".join(quote_identifier(key) for key in item["keyColumns"])
    join = " AND ".join(f"target.{quote_identifier(key)} = incoming.{quote_identifier(key)}" for key in item["keyColumns"])
    mutable_columns = [column for column in item["columns"] if column not in item["keyColumns"]]
    if item["conflictPolicy"] == "update" and mutable_columns:
        target_table = quote_identifier(item["table"])
        conflict_action = "DO UPDATE SET " + ",".join(
            f"{quote_identifier(column)}=EXCLUDED.{quote_identifier(column)}" for column in mutable_columns
        ) + " WHERE " + " OR ".join(
            f"{target_table}.{quote_identifier(column)} IS DISTINCT FROM EXCLUDED.{quote_identifier(column)}"
            for column in mutable_columns
        )
        reject_existing_mismatch = ""
    else:
        conflict_action = "DO NOTHING"
        reject_existing_mismatch = (
            f"DO $$BEGIN IF EXISTS (SELECT 1 FROM {quote_identifier(temp)} incoming JOIN {qualified(item)} target ON {join} "
            "WHERE to_jsonb(incoming) IS DISTINCT FROM to_jsonb(target)) THEN RAISE EXCEPTION 'existing row differs from migration chunk'; END IF; END$$;"
        )
    prelude = (
        "BEGIN;SET LOCAL lock_timeout='10s';SET LOCAL statement_timeout='0';"
        f"CREATE TEMP TABLE {quote_identifier(temp)} (LIKE {qualified(item)} INCLUDING DEFAULTS INCLUDING GENERATED);"
        f"COPY {quote_identifier(temp)} ({columns}) FROM STDIN WITH (FORMAT CSV, HEADER TRUE);\n"
    )
    postlude = (
        "\\.\n"
        f"{reject_existing_mismatch}"
        f"INSERT INTO {qualified(item)} ({columns}) OVERRIDING SYSTEM VALUE SELECT {columns} FROM {quote_identifier(temp)} "
        f"ON CONFLICT ({keys}) {conflict_action};"
        f"DO $$BEGIN IF (SELECT count(*) FROM {quote_identifier(temp)}) <> {int(chunk['rowCount'])} THEN "
        "RAISE EXCEPTION 'chunk row count mismatch'; END IF; "
        f"IF EXISTS (SELECT 1 FROM {quote_identifier(temp)} incoming JOIN {qualified(item)} target ON {join} "
        "WHERE to_jsonb(incoming) IS DISTINCT FROM to_jsonb(target)) THEN RAISE EXCEPTION 'imported row differs from migration chunk'; "
        f"END IF; IF (SELECT count(*) FROM {quote_identifier(temp)} incoming JOIN {qualified(item)} target ON {join}) "
        f"<> (SELECT count(*) FROM {quote_identifier(temp)}) THEN RAISE EXCEPTION 'imported row is missing'; END IF; END$$;COMMIT;\n"
    )
    psql = subprocess.Popen(connection.args(), env=connection.environment(), stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    zstd = subprocess.Popen(
        ["zstd", "-q", "-dc", str(artifact)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=sanitized_environment(),
    )
    assert psql.stdin is not None and zstd.stdout is not None
    psql.stdin.write(prelude.encode())
    digest = hashlib.sha256()
    for block in iter(lambda: zstd.stdout.read(1024 * 1024), b""):
        digest.update(block)
        psql.stdin.write(block)
    psql.stdin.write(postlude.encode())
    psql.stdin.close()
    zstd_error = zstd.stderr.read().decode(errors="replace") if zstd.stderr else ""
    zstd_code = zstd.wait()
    psql_error = psql.stderr.read().decode(errors="replace") if psql.stderr else ""
    psql_code = psql.wait()
    if digest.hexdigest() != chunk["csvSha256"]:
        raise MigrationError(f"decompressed checksum mismatch: {chunk['file']}")
    if zstd_code != 0 or psql_code != 0:
        raise MigrationError(f"chunk import failed: {chunk['file']}: {zstd_error.strip()} {psql_error.strip()}")


def reset_sequences(connection: PostgresConnection, items: list[dict[str, Any]]) -> None:
    for item in items:
        if "id" not in item["columns"]:
            continue
        relation = f"{item['schema']}.{item['table']}"
        statement = (
            "DO $$DECLARE sequence_name text; maximum bigint; BEGIN "
            f"sequence_name := pg_get_serial_sequence({sql_literal(relation)}, 'id'); "
            f"IF sequence_name IS NOT NULL THEN SELECT max(id) INTO maximum FROM {qualified(item)}; "
            "IF maximum IS NOT NULL THEN PERFORM setval(sequence_name, maximum, true); END IF; END IF; END$$"
        )
        run_psql(connection, statement)


def import_all(catalog_path: Path, manifest_path: Path) -> None:
    catalog = load_catalog(catalog_path)
    by_name = {dataset_name(item): item for item in catalog["datasets"]}
    manifest = validate_manifest_artifacts(manifest_path, catalog_sha256(catalog_path), set(by_name))
    connections = {logical: PostgresConnection(logical, "target") for logical in ("property", "reference")}
    for item in catalog["datasets"]:
        assert_schema_matches(connections[item["logicalDatabase"]], item)
    root = manifest_path.resolve().parent
    chunks_by_dataset: dict[str, list[dict[str, Any]]] = {name: [] for name in by_name}
    for chunk in manifest["chunks"]:
        chunks_by_dataset[chunk["dataset"]].append(chunk)
    for item in catalog["datasets"]:
        for chunk in sorted(chunks_by_dataset[dataset_name(item)], key=lambda value: value["file"]):
            import_chunk(connections[item["logicalDatabase"]], item, chunk, root / chunk["file"])
    for logical, connection in connections.items():
        reset_sequences(connection, [item for item in catalog["datasets"] if item["logicalDatabase"] == logical])


def target_csv_sha256(connection: PostgresConnection, query: str) -> str:
    process = subprocess.Popen(
        connection.args() + ["-c", f"COPY ({query}) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)"],
        env=connection.environment(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    assert process.stdout is not None
    digest = hashlib.sha256()
    for block in iter(lambda: process.stdout.read(1024 * 1024), b""):
        digest.update(block)
    stderr = process.stderr.read().decode(errors="replace") if process.stderr else ""
    if process.wait() != 0:
        raise MigrationError(f"target reconciliation query failed: {stderr.strip()}")
    return digest.hexdigest()


def reconcile(catalog_path: Path, manifest_path: Path, report_path: Path) -> None:
    catalog = load_catalog(catalog_path)
    by_name = {dataset_name(item): item for item in catalog["datasets"]}
    manifest = validate_manifest_artifacts(manifest_path, catalog_sha256(catalog_path), set(by_name))
    connections = {logical: PostgresConnection(logical, "target") for logical in ("property", "reference")}
    findings: list[str] = []
    for chunk in manifest["chunks"]:
        item = by_name[chunk["dataset"]]
        digest = target_csv_sha256(
            connections[item["logicalDatabase"]],
            copy_query(item, chunk.get("lowerExclusive"), chunk.get("maxKey")),
        )
        if digest != chunk["csvSha256"]:
            findings.append(f"checksum mismatch: {chunk['dataset']}:{chunk['file']}")
    for dataset in manifest.get("datasets", []):
        item = by_name.get(dataset.get("dataset"))
        if item is None or not isinstance(dataset.get("rowCount"), int):
            findings.append("invalid dataset summary")
            continue
        actual = int(run_psql(connections[item["logicalDatabase"]], f"SELECT count(*) FROM {qualified(item)}"))
        if actual != dataset["rowCount"]:
            findings.append(f"row count mismatch: {dataset['dataset']} expected={dataset['rowCount']} actual={actual}")

    property_db = connections["property"]
    catalog_names = set(by_name)
    invariants: dict[str, str] = {}
    if {"property:public.trade", "property:public.raw_trade_ingest"}.issubset(catalog_names):
        invariants["normalizedDuplicateCount"] = "SELECT count(*) FROM (SELECT source,source_key,count(*) FROM public.trade GROUP BY source,source_key HAVING count(*)>1) duplicate"
        invariants["normalizedFallbackDuplicateCount"] = "SELECT count(*) FROM (SELECT complex_id,deal_date,floor,excl_area,deal_amount,apt_dong,count(*) FROM public.trade WHERE deleted_at IS NULL AND apt_dong IS NOT NULL GROUP BY complex_id,deal_date,floor,excl_area,deal_amount,apt_dong HAVING count(*)>1) duplicate"
        invariants["rawFirstViolationCount"] = "SELECT count(*) FROM public.trade trade LEFT JOIN public.raw_trade_ingest raw ON raw.id=trade.raw_ingest_id WHERE raw.id IS NULL"
    if "property:public.parcel" in catalog_names:
        invariants["invalidCoordinateCount"] = "SELECT count(*) FROM public.parcel WHERE latitude IS NOT NULL AND (longitude IS NULL OR geom IS NULL OR latitude NOT BETWEEN 33 AND 39 OR longitude NOT BETWEEN 124 AND 132 OR ST_SRID(geom)<>4326)"
    if {"property:public.raw_trade_ingest", "property:public.trade_match_evidence"}.issubset(catalog_names):
        invariants["unqueryableFailedMatchCount"] = "SELECT count(*) FROM public.raw_trade_ingest raw WHERE raw.status='MATCH_FAILED' AND NOT EXISTS (SELECT 1 FROM public.trade_match_evidence evidence WHERE evidence.raw_ingest_id=raw.id)"
    values = {name: int(run_psql(property_db, sql)) for name, sql in invariants.items()}
    reference_values: dict[str, int] = {}
    if {
        "reference:public.dataset_active_snapshot",
        "reference:public.dataset_publication",
        "reference:public.dataset_snapshot_row",
    }.issubset(catalog_names):
        reference_values["activeSnapshotWithoutRowsCount"] = int(run_psql(
            connections["reference"],
            "SELECT count(*) FROM public.dataset_active_snapshot active JOIN public.dataset_publication publication ON publication.publication_id=active.publication_id WHERE NOT EXISTS (SELECT 1 FROM public.dataset_snapshot_row row WHERE row.publication_id=publication.publication_id)",
        ))
        values.update(reference_values)
    for name, value in values.items():
        if value != 0:
            findings.append(f"{name}={value}")
    report = {
        "status": "pass" if not findings else "fail", "migrationId": manifest["migrationId"],
        "checkedAt": datetime.now(UTC).isoformat(), "invariants": values, "findings": findings,
    }
    report_path.write_bytes(canonical_json(report))
    if findings:
        raise MigrationError(f"reconciliation failed: {'; '.join(findings[:5])}; see report")


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--catalog", type=Path, default=Path(__file__).with_name("data-only-allowlist.json"))
    commands = value.add_subparsers(dest="command", required=True)
    commands.add_parser("validate-catalog")
    export = commands.add_parser("export")
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--s3-uri")
    export.add_argument("--kms-key-id")
    importer = commands.add_parser("import")
    importer.add_argument("--manifest", type=Path, required=True)
    check = commands.add_parser("reconcile")
    check.add_argument("--manifest", type=Path, required=True)
    check.add_argument("--report", type=Path, required=True)
    return value


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "validate-catalog":
            load_catalog(arguments.catalog)
        elif arguments.command == "export":
            print(export_all(arguments.catalog, arguments.output, arguments.s3_uri, arguments.kms_key_id))
        elif arguments.command == "import":
            import_all(arguments.catalog, arguments.manifest)
        elif arguments.command == "reconcile":
            reconcile(arguments.catalog, arguments.manifest, arguments.report)
    except (MigrationError, subprocess.SubprocessError, OSError) as exception:
        print(f"상태: Fail - {exception}", file=sys.stderr)
        return 1
    print("상태: Pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

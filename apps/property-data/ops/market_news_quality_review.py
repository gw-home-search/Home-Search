#!/usr/bin/env python3
"""Operate deterministic market-news human review sets without storing article text in Git."""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass


CATEGORIES = (
    "POLICY",
    "FINANCE_LOAN",
    "SUPPLY_SALE",
    "REDEVELOPMENT",
    "TRANSACTION_PRICE",
    "TRANSPORT_DEVELOPMENT",
)
RELATION_MINIMUMS = {"DIRECT_COMPLEX": 60, "SAME_DONG": 40, "SAME_SIGUNGU": 40}
REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
UUID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
REVIEWER_PATTERN = re.compile(r"^[A-Za-z0-9._:@-]{1,100}$")
BOOLEAN_VALUES = {"true": True, "false": False, "": None}


@dataclass(frozen=True)
class QualityMetrics:
    sample_status: str
    source_snapshot_count: int
    elapsed_hours: float
    healthy_general_runs: int
    relevance_reviewed: int
    relevance_correct: int
    category_metrics: dict[str, tuple[int, int]]
    relation_metrics: dict[str, tuple[int, int]]
    challenge_reviewed: int
    challenge_false_direct: int
    url_reviewed: int
    url_opened: int


@dataclass(frozen=True)
class Assessment:
    status: str
    reasons: tuple[str, ...]


def assess(metrics: QualityMetrics, checkpoint: str) -> Assessment:
    if checkpoint not in {"immediate", "24h", "7d"}:
        raise ValueError("checkpoint must be immediate, 24h, or 7d")
    if metrics.sample_status != "READY" or metrics.source_snapshot_count != 18:
        return Assessment("INSUFFICIENT_SAMPLE", ("필수 표본 또는 18개 scope snapshot이 부족합니다.",))

    missing: list[str] = []
    if metrics.relevance_reviewed < 180:
        missing.append("relevance 180")
    for category in CATEGORIES:
        if metrics.category_metrics.get(category, (0, 0))[0] < 30:
            missing.append(f"{category} 30")
    for relation_type, minimum in RELATION_MINIMUMS.items():
        if metrics.relation_metrics.get(relation_type, (0, 0))[0] < minimum:
            missing.append(f"{relation_type} {minimum}")
    if metrics.challenge_reviewed < 50:
        missing.append("COMPLEX_CHALLENGE 50")
    if metrics.url_reviewed < 100:
        missing.append("URL_OPEN 100")
    if missing:
        return Assessment("PENDING_HUMAN_REVIEW", tuple(missing))

    failures: list[str] = []
    if ratio(metrics.relevance_correct, metrics.relevance_reviewed) < 0.90:
        failures.append("relevance precision < 90%")
    for category in CATEGORIES:
        reviewed, correct = metrics.category_metrics[category]
        if ratio(correct, reviewed) < 0.90:
            failures.append(f"{category} precision < 90%")
    for relation_type, threshold in {
        "DIRECT_COMPLEX": 0.95,
        "SAME_DONG": 0.90,
        "SAME_SIGUNGU": 0.90,
    }.items():
        reviewed, correct = metrics.relation_metrics[relation_type]
        if ratio(correct, reviewed) < threshold:
            failures.append(f"{relation_type} precision < {threshold:.0%}")
    if metrics.challenge_false_direct != 0:
        failures.append("COMPLEX_CHALLENGE false direct relation > 0")
    if ratio(metrics.url_opened, metrics.url_reviewed) < 0.99:
        failures.append("URL open success < 99%")
    if failures:
        return Assessment("FAIL", tuple(failures))

    required_hours = {"immediate": 0.0, "24h": 24.0, "7d": 168.0}[checkpoint]
    required_runs = {"immediate": 0, "24h": 4, "7d": 28}[checkpoint]
    if metrics.elapsed_hours < required_hours:
        return Assessment("PENDING_TIME", (f"elapsed {metrics.elapsed_hours:.1f}h / {required_hours:.0f}h",))
    if metrics.healthy_general_runs < required_runs:
        return Assessment(
            "PENDING_OPERATIONAL_RUNS",
            (f"healthy general runs {metrics.healthy_general_runs} / {required_runs}",),
        )
    return Assessment("PASS", ())


def ratio(numerator: int, denominator: int) -> float:
    return 0.0 if denominator == 0 else numerator / denominator


def require_uuid(value: str) -> str:
    normalized = value.strip().lower()
    if not UUID_PATTERN.fullmatch(normalized):
        raise ValueError("reviewSetId는 canonical UUID여야 합니다.")
    return normalized


def database_environment() -> tuple[str, dict[str, str]]:
    dsn = os.environ.get("MARKET_NEWS_REVIEW_PSQL_DSN", "").strip()
    password = os.environ.get("DB_PASSWORD", "")
    if not dsn or not password:
        raise ValueError("MARKET_NEWS_REVIEW_PSQL_DSN과 DB_PASSWORD가 필요합니다.")
    if "password=" in dsn.lower() or re.search(r"://[^/@]+:[^/@]+@", dsn):
        raise ValueError("DSN에 password를 포함하지 말고 DB_PASSWORD를 사용하세요.")
    environment = os.environ.copy()
    environment["PGPASSWORD"] = password
    return dsn, environment


def run_psql(sql: str, variables: dict[str, str] | None = None, input_text: str | None = None) -> str:
    dsn, environment = database_environment()
    command = ["psql", dsn, "-X", "-v", "ON_ERROR_STOP=1", "-q", "-At"]
    for name, value in (variables or {}).items():
        command.extend(["-v", f"{name}={value}"])
    result = subprocess.run(
        command,
        input=sql if input_text is None else input_text,
        text=True,
        capture_output=True,
        env=environment,
        check=False,
    )
    if result.returncode != 0:
        error_lines = [line.strip() for line in result.stderr.splitlines() if line.strip().startswith("ERROR:")]
        diagnostic_lines = [line.strip() for line in result.stderr.splitlines() if line.strip()]
        message = error_lines[-1] if error_lines else diagnostic_lines[-1] if diagnostic_lines else "psql failed"
        raise RuntimeError(message)
    return result.stdout


def ensure_private_output(path: pathlib.Path, overwrite: bool) -> pathlib.Path:
    resolved = path.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("기사 원문 검토 파일은 repository 밖에 저장해야 합니다.")
    if resolved.exists() and not overwrite:
        raise ValueError("출력 파일이 이미 있습니다. --overwrite 없이 덮어쓰지 않습니다.")
    resolved.parent.mkdir(parents=True, exist_ok=True)
    return resolved


def spreadsheet_safe(value: str) -> str:
    if value and value[0] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + value
    return value


def sanitize_export_csv(content: str) -> str:
    source = io.StringIO(content)
    reader = csv.DictReader(source)
    if reader.fieldnames is None:
        return content
    target = io.StringIO()
    writer = csv.DictWriter(target, fieldnames=reader.fieldnames, lineterminator="\n")
    writer.writeheader()
    for row in reader:
        for field in ("title", "description", "note"):
            row[field] = spreadsheet_safe(row.get(field, ""))
        writer.writerow(row)
    return target.getvalue()


def export_review(review_set_id: str, output: pathlib.Path, overwrite: bool) -> None:
    target = ensure_private_output(output, overwrite)
    sql = r"""
COPY (
    SELECT label.article_id,
           label.relation_id,
           label.sample_stratum,
           relation.category,
           relation.relation_type,
           COALESCE(relation.region_code, '') AS region_code,
           COALESCE(relation.complex_id::text, '') AS complex_id,
           article.title,
           article.public_url,
           COALESCE((
               SELECT raw.description_raw
               FROM market_news_raw_item raw
               WHERE raw.article_id = article.article_id
               ORDER BY raw.received_at DESC, raw.work_unit_id
               LIMIT 1
           ), '') AS description,
           COALESCE(label.relevance_correct::text, '') AS relevance_correct,
           COALESCE(label.category_correct::text, '') AS category_correct,
           COALESCE(label.relation_correct::text, '') AS relation_correct,
           COALESCE(label.url_opened::text, '') AS url_opened,
           COALESCE(label.note, '') AS note
    FROM market_news_quality_label label
    JOIN market_news_article article USING (article_id)
    JOIN market_news_relation relation USING (relation_id)
    WHERE label.review_set_id = :'review_set_id'::uuid
    ORDER BY label.sample_stratum, label.article_id, label.relation_id
) TO STDOUT WITH (FORMAT csv, HEADER true);
"""
    content = run_psql(sql, {"review_set_id": review_set_id})
    if not content.strip():
        raise ValueError("검토 표본을 찾지 못했습니다.")
    content = sanitize_export_csv(content)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as temporary:
        temporary.write(content)
        temporary_path = pathlib.Path(temporary.name)
    os.chmod(temporary_path, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary_path, target)
    print(f"완료: private review CSV={target}")


def parse_boolean(value: str, field: str, row_number: int) -> bool | None:
    normalized = value.strip().lower()
    if normalized not in BOOLEAN_VALUES:
        raise ValueError(f"{row_number}행 {field}는 true|false|empty만 허용합니다.")
    return BOOLEAN_VALUES[normalized]


def import_labels(review_set_id: str, reviewer_ref: str, source: pathlib.Path, dry_run: bool) -> None:
    if not REVIEWER_PATTERN.fullmatch(reviewer_ref):
        raise ValueError("reviewerRef는 1~100자의 안전한 식별자여야 합니다.")
    rows: list[tuple[object, ...]] = []
    seen_relations: set[tuple[int, int]] = set()
    with source.expanduser().open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        required = {
            "article_id",
            "relation_id",
            "relevance_correct",
            "category_correct",
            "relation_correct",
            "url_opened",
            "note",
        }
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise ValueError("검토 CSV 필수 column이 없습니다.")
        for row_number, row in enumerate(reader, start=2):
            article_id = int(row["article_id"])
            relation_id = int(row["relation_id"])
            if article_id <= 0 or relation_id <= 0:
                raise ValueError(f"{row_number}행 id는 양수여야 합니다.")
            relation_key = (article_id, relation_id)
            if relation_key in seen_relations:
                raise ValueError(f"{row_number}행 article_id/relation_id가 중복되었습니다.")
            seen_relations.add(relation_key)
            note = row["note"].strip()
            if len(note) > 500 or "\x00" in note:
                raise ValueError(f"{row_number}행 note가 허용 범위를 벗어났습니다.")
            labels = tuple(
                parse_boolean(row[field], field, row_number)
                for field in ("relevance_correct", "category_correct", "relation_correct", "url_opened")
            )
            if all(value is None for value in labels):
                continue
            rows.append((article_id, relation_id, *labels, note))
    if not rows:
        raise ValueError("반영할 검토 label이 없습니다.")

    labels_json = json.dumps(
        [
            {
                "article_id": row[0],
                "relation_id": row[1],
                "relevance_correct": row[2],
                "category_correct": row[3],
                "relation_correct": row[4],
                "url_opened": row[5],
                "note": row[6],
            }
            for row in rows
        ],
        ensure_ascii=False,
        separators=(",", ":"),
    )
    sql = r"""
BEGIN;
WITH review_input AS (
    SELECT *
    FROM jsonb_to_recordset(:'labels_json'::jsonb) AS input(
        article_id bigint,
        relation_id bigint,
        relevance_correct boolean,
        category_correct boolean,
        relation_correct boolean,
        url_opened boolean,
        note varchar(500)
    )
)
SELECT count(*) = (
    SELECT count(*)
    FROM review_input input
    JOIN market_news_quality_label label
      ON label.review_set_id = :'review_set_id'::uuid
     AND label.article_id = input.article_id
     AND label.relation_id = input.relation_id
) AS review_input_valid
FROM review_input
\gset
\if :review_input_valid
WITH review_input AS (
    SELECT *
    FROM jsonb_to_recordset(:'labels_json'::jsonb) AS input(
        article_id bigint,
        relation_id bigint,
        relevance_correct boolean,
        category_correct boolean,
        relation_correct boolean,
        url_opened boolean,
        note varchar(500)
    )
)
UPDATE market_news_quality_label label
SET relevance_correct = input.relevance_correct,
    category_correct = input.category_correct,
    relation_correct = input.relation_correct,
    url_opened = input.url_opened,
    reviewed_at = now(),
    reviewer_ref = :'reviewer_ref',
    note = NULLIF(input.note, '')
FROM review_input input
WHERE label.review_set_id = :'review_set_id'::uuid
  AND label.article_id = input.article_id
  AND label.relation_id = input.relation_id;
""" + ("ROLLBACK;\n" if dry_run else "COMMIT;\n") + r"""
\else
ROLLBACK;
\echo 'review CSV contains rows outside the review set'
\quit 4
\endif
"""
    run_psql(
        "",
        {"review_set_id": review_set_id, "reviewer_ref": reviewer_ref, "labels_json": labels_json},
        sql,
    )
    action = "validated" if dry_run else "imported"
    print(f"완료: {action} labels={len(rows)} reviewSetId={review_set_id}")


def load_metrics(review_set_id: str) -> QualityMetrics:
    sql = r"""
WITH target AS (
    SELECT * FROM market_news_quality_review_set WHERE review_set_id = :'review_set_id'::uuid
), categories(category) AS (
    VALUES ('POLICY'), ('FINANCE_LOAN'), ('SUPPLY_SALE'),
           ('REDEVELOPMENT'), ('TRANSACTION_PRICE'), ('TRANSPORT_DEVELOPMENT')
), relations(relation_type) AS (
    VALUES ('DIRECT_COMPLEX'), ('SAME_DONG'), ('SAME_SIGUNGU')
)
SELECT json_build_object(
    'sample_status', target.status,
    'source_snapshot_count', target.source_snapshot_count,
    'elapsed_hours', EXTRACT(epoch FROM (now() - COALESCE(target.source_snapshot_captured_at, target.sampled_at))) / 3600.0,
    'healthy_general_runs', (
        SELECT count(*) FROM market_news_collection_execution execution
        WHERE execution.scheduled_at >= COALESCE(target.source_snapshot_captured_at, target.sampled_at)
          AND execution.execution_type = 'GENERAL'
          AND execution.state = 'COMPLETED'
          AND execution.truncated_work_unit_count = 0
          AND execution.failed_work_unit_count = 0
          AND execution.skipped_budget_work_unit_count = 0
    ),
    'relevance_reviewed', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND relevance_correct IS NOT NULL),
    'relevance_correct', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND relevance_correct),
    'category_metrics', (
        SELECT json_object_agg(category.category, json_build_array(
            (SELECT count(*) FROM market_news_quality_label label JOIN market_news_relation relation USING (relation_id)
             WHERE label.review_set_id = target.review_set_id AND relation.category = category.category AND label.category_correct IS NOT NULL),
            (SELECT count(*) FROM market_news_quality_label label JOIN market_news_relation relation USING (relation_id)
             WHERE label.review_set_id = target.review_set_id AND relation.category = category.category AND label.category_correct)
        )) FROM categories category
    ),
    'relation_metrics', (
        SELECT json_object_agg(kind.relation_type, json_build_array(
            (SELECT count(*) FROM market_news_quality_label label JOIN market_news_relation relation USING (relation_id)
             WHERE label.review_set_id = target.review_set_id AND relation.relation_type = kind.relation_type AND label.relation_correct IS NOT NULL),
            (SELECT count(*) FROM market_news_quality_label label JOIN market_news_relation relation USING (relation_id)
             WHERE label.review_set_id = target.review_set_id AND relation.relation_type = kind.relation_type AND label.relation_correct)
        )) FROM relations kind
    ),
    'challenge_reviewed', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND sample_stratum = 'COMPLEX_CHALLENGE' AND relation_correct IS NOT NULL),
    'challenge_false_direct', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND sample_stratum = 'COMPLEX_CHALLENGE' AND relation_correct = false),
    'url_reviewed', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND url_opened IS NOT NULL),
    'url_opened', (SELECT count(*) FROM market_news_quality_label WHERE review_set_id = target.review_set_id AND url_opened)
)
FROM target;
"""
    output = run_psql(sql, {"review_set_id": review_set_id}).strip()
    if not output:
        raise ValueError("review set을 찾지 못했습니다.")
    value = json.loads(output)
    return QualityMetrics(
        sample_status=value["sample_status"],
        source_snapshot_count=int(value["source_snapshot_count"]),
        elapsed_hours=float(value["elapsed_hours"]),
        healthy_general_runs=int(value["healthy_general_runs"]),
        relevance_reviewed=int(value["relevance_reviewed"]),
        relevance_correct=int(value["relevance_correct"]),
        category_metrics={key: tuple(map(int, pair)) for key, pair in value["category_metrics"].items()},
        relation_metrics={key: tuple(map(int, pair)) for key, pair in value["relation_metrics"].items()},
        challenge_reviewed=int(value["challenge_reviewed"]),
        challenge_false_direct=int(value["challenge_false_direct"]),
        url_reviewed=int(value["url_reviewed"]),
        url_opened=int(value["url_opened"]),
    )


def report(review_set_id: str, checkpoint: str, output: pathlib.Path | None) -> int:
    metrics = load_metrics(review_set_id)
    result = assess(metrics, checkpoint)
    lines = [
        "# Market news quality review",
        "",
        f"- reviewSetId: `{review_set_id}`",
        f"- checkpoint: `{checkpoint}`",
        f"- status: `{result.status}`",
        f"- sourceSnapshotCount: {metrics.source_snapshot_count}",
        f"- elapsedHours: {metrics.elapsed_hours:.1f}",
        f"- healthyGeneralRuns: {metrics.healthy_general_runs}",
        f"- relevance: {metrics.relevance_correct}/{metrics.relevance_reviewed}",
        f"- challengeFalseDirect: {metrics.challenge_false_direct}/{metrics.challenge_reviewed}",
        f"- urlOpen: {metrics.url_opened}/{metrics.url_reviewed}",
        "",
        "## Categories",
        "",
    ]
    lines.extend(f"- {key}: {correct}/{reviewed}" for key, (reviewed, correct) in metrics.category_metrics.items())
    lines.extend(("", "## Relations", ""))
    lines.extend(f"- {key}: {correct}/{reviewed}" for key, (reviewed, correct) in metrics.relation_metrics.items())
    lines.extend(("", "## Reasons", ""))
    lines.extend(f"- {reason}" for reason in result.reasons)
    content = "\n".join(lines) + "\n"
    if output is None:
        print(content, end="")
    else:
        target = output.expanduser().resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        print(f"완료: aggregate report={target}")
    return 0 if result.status == "PASS" else 1 if result.status == "FAIL" else 3


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Market news quality review operations")
    commands = root.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    export.add_argument("--review-set-id", required=True)
    export.add_argument("--output", type=pathlib.Path, required=True)
    export.add_argument("--overwrite", action="store_true")
    importer = commands.add_parser("import")
    importer.add_argument("--review-set-id", required=True)
    importer.add_argument("--reviewer-ref", required=True)
    importer.add_argument("--input", type=pathlib.Path, required=True)
    importer.add_argument("--dry-run", action="store_true")
    report_parser = commands.add_parser("report")
    report_parser.add_argument("--review-set-id", required=True)
    report_parser.add_argument("--checkpoint", choices=("immediate", "24h", "7d"), required=True)
    report_parser.add_argument("--output", type=pathlib.Path)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        review_set_id = require_uuid(args.review_set_id)
        if args.command == "export":
            export_review(review_set_id, args.output, args.overwrite)
            return 0
        if args.command == "import":
            import_labels(review_set_id, args.reviewer_ref, args.input, args.dry_run)
            return 0
        return report(review_set_id, args.checkpoint, args.output)
    except (ValueError, RuntimeError, OSError) as error:
        print(f"거부됨: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Check that project-facing terminology is not tied to old stage labels."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]

SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".next",
    ".vite",
    "__pycache__",
    "bin",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "target",
}

BANNED_PATTERNS = [
    re.compile(pattern)
    for pattern in (
        r"\bv1-slice-harness\b",
        r"\$v1-slice-harness\b",
        r"\.codex/harness/v1\b",
        r"\bv1_(?:flow|plan|report|pr|integrate)\.py\b",
        r"\bHome Search V1\b",
        r"\bV1 API\b",
        r"\bV1 APIs\b",
        r"\bV1 slice\b",
        r"\bV1 migration\b",
        r"\bV1 MVP\b",
        r"\bV1\b",
        r"\bv1\b",
        r"\bMVP\b",
        r"\bMvp\b",
        r"\bmvp\b",
        r"\bV2\b",
        r"\bBacklog\b",
        r"\bBACKLOG\b",
        r"\bbacklog\b",
        r"public public",
        r"canonical canonical",
        r"\[\[slices\]\]",
        r"slices/backlog\.toml",
        r"sync-backlog",
    )
]

ALLOW_PATTERNS = [
    re.compile(pattern)
    for pattern in (
        r"/api/v1(?:/|\b)",
        r"https://(?:openapi\.naver\.com|api\.openai\.com)/v1(?:/|\b)",
        r"V[0-9]+__.*\.sql",
        r"\b(?:naver-news-search-metadata|naver-title-snippet|news-signal|news-signal-json|test|prompt|schema)-v[0-9]+\b",
        r"\bhome-search:prediction:v[0-9]+\b",
        r"v2/sdk\.js",
        r"kakao\.maps\.load",
        r"sha512-[A-Za-z0-9+/=]*V[0-9][A-Za-z0-9+/=]*",
    )
]


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    text: str
    pattern: str


def should_skip(path: Path) -> bool:
    rel = path.relative_to(REPO_ROOT).as_posix()
    if rel == ".codex/harness/project_terms_check.py":
        return True
    if rel.startswith(".codex/harness/reports/"):
        return True
    if rel == "docs/NEWS_SIGNAL_PIPELINE.md":
        return True
    if rel.startswith("apps/news/"):
        return True
    if path.name.startswith(".env"):
        return True
    return any(part in SKIP_DIRS for part in path.parts)


def candidate_files() -> Iterable[Path]:
    for path in REPO_ROOT.rglob("*"):
        if not path.is_file():
            continue
        if should_skip(path):
            continue
        yield path


def mask_allowed_fragments(line: str) -> str:
    masked = line
    for pattern in ALLOW_PATTERNS:
        masked = pattern.sub("", masked)
    return masked


def scan_text(path: Path, text: str) -> list[Finding]:
    rel = path.relative_to(REPO_ROOT).as_posix()
    findings: list[Finding] = []
    for index, line in enumerate(text.splitlines(), 1):
        scanned_line = mask_allowed_fragments(line)
        for pattern in BANNED_PATTERNS:
            if pattern.search(scanned_line):
                findings.append(Finding(rel, index, line.strip(), pattern.pattern))
                break
    return findings


def scan_repo() -> list[Finding]:
    findings: list[Finding] = []
    for path in candidate_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        findings.extend(scan_text(path, text))
    return findings


def format_findings(findings: Iterable[Finding]) -> str:
    return "\n".join(f"- {item.path}:{item.line}: {item.text} [{item.pattern}]" for item in findings)


def run_self_test() -> int:
    sample = "\n".join(
        [
            "GET /api/v1/map/complexes stays valid",
            "home.news.openai.base-url=https://api.openai.com/v1/responses",
            "HOME_NEWS_OPENAI_EXTRACTION_VERSION=naver-title-snippet-v1",
            "apps/api/src/main/resources/db/migration/V1__initial_schema.sql",
            "Home Search V1 migration",
            "For V1, authentication is outside the path.",
            "$v1-slice-harness mode=run",
            "MVP runtime smoke",
            "sync-backlog --merged",
            "public public API URL",
        ]
    )
    findings = scan_text(REPO_ROOT / "SELF_TEST.txt", sample)
    checks = [
        len(findings) == 6,
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "GET /api/v1/search/complexes"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "home-search:prediction:v1:F37:complex:501"),
        scan_text(REPO_ROOT / "SELF_TEST.txt", "V1 API stays at /api/v1/search/complexes") != [],
        scan_text(REPO_ROOT / "SELF_TEST.txt", "V2 ranking") != [],
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "prompt-version: news-signal-v1"),
        should_skip(REPO_ROOT / "docs/NEWS_SIGNAL_PIPELINE.md"),
        should_skip(REPO_ROOT / "apps/news/src/main/resources/application.yml"),
        should_skip(REPO_ROOT / "apps/news/local-input/region-month-signal-bigkinds.csv.jsonl"),
        should_skip(REPO_ROOT / "apps/news/ops/.env"),
        should_skip(REPO_ROOT / "apps/news/bin/main/application.yml"),
    ]
    if all(checks):
        print("self-test passed: project_terms_check")
        return 0
    print("self-test failed: project_terms_check", file=sys.stderr)
    print(format_findings(findings), file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check Home Search terminology guardrails.")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    findings = scan_repo()
    if findings:
        print("상태: Fail")
        print("용어 위반:")
        print(format_findings(findings))
        return 1
    print("상태: Pass")
    print("용어 위반: none")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Check project terminology guardrails and Korean-first user-facing labels."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

REPO_ROOT = Path(__file__).resolve().parents[2]

SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".next",
    ".vite",
    ".venv",
    ".worktrees",
    "__pycache__",
    "bin",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "runtime-keys",
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
        r"/internal/v1(?:/|\b)",
        r"/v1/responses\b",
        r"/api/AptIdInfoSvc/v1/getAptInfo\b",
        r"https://(?:openapi\.naver\.com|api\.openai\.com)/v1(?:/|\b)",
        r"V[0-9]+__.*\.sql",
        r"\b(?:fixture|naver-news-search-metadata|naver-title-snippet|news-signal|news-signal-json|test|prompt|schema)-v[0-9]+\b",
        r"\bhome-search:prediction:v[0-9]+\b",
        r"\b[a-z][A-Za-z0-9]*(?:[A-Za-z0-9-]*[A-Za-z0-9])?/v[0-9]+\b",
        r"\b[a-z0-9]+(?:-[a-z0-9]+)*-v[0-9]+(?:-[a-z0-9]+)*\b",
        r"\bv[0-9]+\.[0-9]+\b",
        r"v2/sdk\.js",
        r"kakao\.maps\.load",
        r"sha512-[A-Za-z0-9+/=]*V[0-9][A-Za-z0-9+/=]*",
    )
]

MIGRATION_VERSION_PATHS = (
    "apps/property-data/migration/",
    "apps/source-data/README.md",
    "apps/source-data/src/main/java/com/home/sourcedata/migration/",
    "apps/source-data/src/test/java/com/home/sourcedata/migration/",
    "apps/property-data/api/src/test/java/com/home/foundation/CoordinateImportOpsConfigurationTest.java",
)

AI_MACHINE_VERSION_SUFFIXES = {".py", ".sql", ".toml"}
AI_MACHINE_VERSION_RE = re.compile(r"\b(?:[a-z0-9][a-z0-9-]*-)?v[0-9]+\b(?!-slice-harness\b)")

USER_VISIBLE_FILES = [
    ".github/pull_request_template.md",
    ".codex/harness/pr_lint.py",
    ".codex/harness/home_report.py",
    ".codex/hooks/stop_verification_gate.py",
]

PROMPT_FILES = [
    ".codex/harness/prompts/backend_execute.md",
    ".codex/harness/prompts/frontend_execute.md",
    ".codex/harness/prompts/gate_review.md",
    ".codex/harness/prompts/integration_review.md",
    ".codex/harness/prompts/next_slice.md",
    ".codex/harness/prompts/slice_plan.md",
]

REQUIRED_SNIPPETS = {
    ".github/pull_request_template.md": [
        "## TDD 근거",
        "최초 RED:",
        "예상 RED 실패:",
        "최소 GREEN:",
        "## 계약 영향",
    ],
    ".codex/harness/pr_lint.py": [
        "## 사용 skill",
        "## TDD 근거",
        "최초 RED:",
        "예상 RED 실패:",
        "최소 GREEN:",
        "## 계약 영향",
    ],
    ".codex/harness/home_report.py": [
        "# Home Search 작업 보고서",
        "## 사용 skill",
        "## TDD 근거",
        "최초 RED:",
        "예상 RED 실패:",
        "최소 GREEN:",
        "## 계약 영향",
    ],
    ".codex/hooks/stop_verification_gate.py": ["최초 RED", "예상 RED 실패", "최소 GREEN"],
    ".codex/harness/prompts/backend_execute.md": ["Skill routing:", "Final user-facing evidence labels:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/frontend_execute.md": ["Skill routing:", "Final user-facing evidence labels:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/gate_review.md": ["Skill routing:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/integration_review.md": ["Skill routing:", "contract-reviewer: 게이트 결정", "reviewer: 지적사항"],
    ".codex/harness/prompts/next_slice.md": ["Skill routing:", "다음 작업 후보:", "인수 기준:"],
    ".codex/harness/prompts/slice_plan.md": ["Skill routing:", "인수 기준:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
}

FORBIDDEN_USER_BODY_PATTERNS = [
    re.compile(pattern)
    for pattern in (
        r"## TDD Evidence\b",
        r"^First RED:",
        r"^Expected RED failure:",
        r"^Minimum GREEN:",
        r"## Contract 영향\b",
        r"reviewer:\s*Findings\s*=",
        r"contract-reviewer:\s*Gate decision\s*=",
    )
]

LEGACY_COMPAT_FILES = {
    ".codex/harness/pr_lint.py",
    ".codex/hooks/stop_verification_gate.py",
}


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    text: str
    pattern: str


def rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def should_skip(path: Path) -> bool:
    relative = rel(path)
    if relative == ".codex/harness/project_terms_check.py":
        return True
    if relative.startswith(".codex/harness/reports/"):
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


def mask_allowed_fragments(path: Path, line: str) -> str:
    masked = line.replace(r"\/", "/")
    for pattern in ALLOW_PATTERNS:
        masked = pattern.sub("", masked)
    if rel(path).startswith("apps/ai/"):
        masked = re.sub(r"\b[a-z0-9]+(?:-[a-z0-9]+)*-v[0-9]+\b", "", masked)
        masked = re.sub(r'''["']v[0-9]+["']''', "", masked)
        masked = re.sub(r"/v[0-9]+/", "", masked)
    if rel(path).startswith(MIGRATION_VERSION_PATHS):
        masked = re.sub(r"\b[Vv][0-9]+\b", "", masked)
    if rel(path).startswith("apps/ai/") and path.suffix in AI_MACHINE_VERSION_SUFFIXES:
        masked = AI_MACHINE_VERSION_RE.sub("", masked)
    return masked


def scan_text(path: Path, text: str) -> list[Finding]:
    relative = rel(path)
    findings: list[Finding] = []
    for index, line in enumerate(text.splitlines(), 1):
        scanned_line = mask_allowed_fragments(path, line)
        for pattern in BANNED_PATTERNS:
            if pattern.search(scanned_line):
                findings.append(Finding(relative, index, line.strip(), pattern.pattern))
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


def iter_language_files() -> list[Path]:
    files: set[Path] = set()
    for raw in USER_VISIBLE_FILES + PROMPT_FILES:
        path = REPO_ROOT / raw
        if path.exists():
            files.add(path)
    return sorted(files)


def allowed_legacy_line(path: str, line: str) -> bool:
    if path not in LEGACY_COMPAT_FILES:
        return False
    return any(
        marker in line
        for marker in (
            '"First RED:"',
            '"Expected RED failure:"',
            '"Minimum GREEN:"',
            "First RED|",
            "|First RED",
            "Expected RED failure",
            "Minimum GREEN",
            "Gate decision",
            "Findings",
            "## TDD Evidence",
            "## Contract 영향",
            "legacy_body",
        )
    )


def user_body_violations(path: str, text: str) -> list[Finding]:
    violations: list[Finding] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if allowed_legacy_line(path, line):
            continue
        for pattern in FORBIDDEN_USER_BODY_PATTERNS:
            if pattern.search(line.strip()):
                violations.append(Finding(path=path, line=line_number, text=line.strip(), pattern=pattern.pattern))
                break
    return violations


def scan_language(files: Iterable[Path]) -> list[Finding]:
    violations: list[Finding] = []
    for path in files:
        relative = rel(path)
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            violations.append(Finding(path=relative, line=0, text=f"read failed: {exc}", pattern="read"))
            continue
        for snippet in REQUIRED_SNIPPETS.get(relative, []):
            if snippet not in text:
                violations.append(
                    Finding(path=relative, line=0, text=f"missing required user-facing snippet: {snippet}", pattern="required")
                )
        if relative in USER_VISIBLE_FILES:
            violations.extend(user_body_violations(relative, text))
    return violations


def format_findings(findings: Iterable[Finding]) -> str:
    return "\n".join(f"- {item.path}:{item.line}: {item.text} [{item.pattern}]" for item in findings)


def run_self_test() -> int:
    sample = "\n".join(
        [
            "GET /api/v1/map/complexes stays valid",
            "apps/property-data/core/src/main/resources/db/migration/V1__initial_schema.sql",
            "Home Search V1 migration",
            "For V1, authentication is outside the path.",
            "$v1-slice-harness mode=run",
            "MVP runtime smoke",
            "sync-backlog --merged",
            "public public API URL",
        ]
    )
    findings = scan_text(REPO_ROOT / "SELF_TEST.txt", sample)
    bad_language = user_body_violations(".github/pull_request_template.md", "## TDD Evidence\nFirst RED:\n")
    good_language = user_body_violations(".github/pull_request_template.md", "## TDD 근거\n최초 RED:\n")
    legacy_language = user_body_violations(".codex/harness/pr_lint.py", '("최초 RED:", ("최초 RED:", "First RED:")),')
    checks = [
        len(findings) == 6,
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "GET /api/v1/search/complexes"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "GET /internal/v1/admin/coordinates"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "/api/AptIdInfoSvc/v1/getAptInfo"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", 'connection.request("POST", "/v1/responses")'),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", r"/^\/api\/v1\/chatbot\/query$/"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", 'schema_version="fixture-v1"'),
        not scan_text(REPO_ROOT / "apps/ai/tests/example.py", 'dataset_version="rail-v1"'),
        not scan_text(REPO_ROOT / "apps/ai/tests/example.py", 'dataset_version="v1"'),
        not scan_text(REPO_ROOT / "apps/ai/tests/example.py", 'object_key="raw/v1/source/checksum.zip"'),
        not scan_text(REPO_ROOT / "docs/README.md", 'dataset_version="rail-v1"'),
        scan_text(REPO_ROOT / "docs/README.md", 'dataset_version="v1"') != [],
        not scan_text(REPO_ROOT / "docs/README.md", 'object_key="raw/v1/source/checksum.zip"'),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "home-search:prediction:v1:F37:complex:501"),
        scan_text(REPO_ROOT / "SELF_TEST.txt", "V1 API stays at /api/v1/search/complexes") != [],
        scan_text(REPO_ROOT / "SELF_TEST.txt", "V2 ranking") != [],
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "prompt-version: news-signal-v1"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "uiSummary/v1"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "recommendation-policy-v1"),
        not scan_text(REPO_ROOT / "SELF_TEST.txt", "official v1.7 contract"),
        not scan_text(
            REPO_ROOT / "apps/ai/config/reference_sources.toml",
            'normalization_schema_version = "school-zone-v1"',
        ),
        not scan_text(REPO_ROOT / "apps/ai/tests/datasets/test_raw_store.py", 'dataset_version="v1"'),
        not scan_text(REPO_ROOT / "apps/ai/ai_service/datasets/raw_store.py", 'key = f"raw/v1/{checksum}.zip"'),
        scan_text(REPO_ROOT / "apps/ai/ai_service/sample.py", "$v1-slice-harness mode=run") != [],
        scan_text(REPO_ROOT / "docs/README.md", 'dataset_version="v1"') != [],
        not scan_text(REPO_ROOT / "apps/source-data/README.md", "V1 is the fresh database schema"),
        scan_text(REPO_ROOT / "docs/README.md", "V1 is the product milestone") != [],
        should_skip(REPO_ROOT / ".codex/harness/reports/sample.md"),
        should_skip(REPO_ROOT / ".worktrees/example/apps/ai/example.py"),
        should_skip(REPO_ROOT / "apps/ai/.venv/lib/python/site-packages/example.py"),
        should_skip(REPO_ROOT / "runtime-keys/admin-e2e/private.pem"),
        bool(bad_language),
        not good_language,
        not legacy_language,
    ]
    if all(checks):
        print("self-test passed: project_terms_check")
        return 0
    print("self-test failed: project_terms_check", file=sys.stderr)
    print(format_findings(findings), file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check Home Search terminology and user-facing language guardrails.")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    findings = scan_repo()
    language = scan_language(iter_language_files())
    if findings or language:
        print("상태: Fail")
        if findings:
            print("용어 위반:")
            print(format_findings(findings))
        if language:
            print("사용자 노출 언어 위반:")
            print(format_findings(language))
        return 1
    print("상태: Pass")
    print("용어 위반: none")
    print("사용자 노출 언어 위반: none")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

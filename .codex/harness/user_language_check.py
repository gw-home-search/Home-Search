#!/usr/bin/env python3
"""Check Korean-first labels in user-facing generated artifacts."""

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

USER_VISIBLE_FILES = [
    ".github/pull_request_template.md",
    ".codex/harness/pr_body_check.py",
    ".codex/harness/pr_lint.py",
    ".codex/harness/v1_report.py",
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
        "KO 수정 승인:",
        "KO 생성 기준:",
    ],
    ".codex/harness/pr_body_check.py": ["## TDD 근거", "최초 RED:", "예상 RED 실패:", "최소 GREEN:", "## 계약 영향"],
    ".codex/harness/pr_lint.py": [
        "## TDD 근거",
        "최초 RED:",
        "예상 RED 실패:",
        "최소 GREEN:",
        "## 계약 영향",
        "KO 수정 승인:",
        "KO 생성 기준:",
    ],
    ".codex/harness/v1_report.py": ["# V1 Slice 보고서", "## TDD 근거", "최초 RED:", "예상 RED 실패:", "최소 GREEN:", "## 계약 영향"],
    ".codex/hooks/stop_verification_gate.py": ["최초 RED", "예상 RED 실패", "최소 GREEN"],
    ".codex/harness/prompts/backend_execute.md": ["Final user-facing evidence labels:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/frontend_execute.md": ["Final user-facing evidence labels:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/gate_review.md": ["최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
    ".codex/harness/prompts/integration_review.md": ["contract-reviewer: 게이트 결정", "reviewer: 지적사항"],
    ".codex/harness/prompts/next_slice.md": ["다음 slice 후보:", "인수 기준:"],
    ".codex/harness/prompts/slice_plan.md": ["인수 기준:", "최초 RED:", "예상 RED 실패:", "최소 GREEN:"],
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
class Violation:
    path: str
    line: int
    text: str
    pattern: str


def is_ko_path(path: Path) -> bool:
    lowered = path.name.lower()
    return lowered.endswith("_ko.md") or lowered.endswith("_ko.local.md")


def rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def iter_target_files() -> list[Path]:
    files: set[Path] = set()
    for raw in USER_VISIBLE_FILES + PROMPT_FILES:
        path = REPO_ROOT / raw
        if path.exists() and not is_ko_path(path):
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


def user_body_violations(path: str, text: str) -> list[Violation]:
    violations: list[Violation] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if allowed_legacy_line(path, line):
            continue
        for pattern in FORBIDDEN_USER_BODY_PATTERNS:
            if pattern.search(line.strip()):
                violations.append(Violation(path=path, line=line_number, text=line.strip(), pattern=pattern.pattern))
                break
    return violations


def scan(files: Iterable[Path]) -> list[Violation]:
    violations: list[Violation] = []
    for path in files:
        relative = rel(path)
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            violations.append(Violation(path=relative, line=0, text=f"read failed: {exc}", pattern="read"))
            continue
        for snippet in REQUIRED_SNIPPETS.get(relative, []):
            if snippet not in text:
                violations.append(
                    Violation(path=relative, line=0, text=f"missing required user-facing snippet: {snippet}", pattern="required")
                )
        if relative in USER_VISIBLE_FILES:
            violations.extend(user_body_violations(relative, text))
    return violations


def format_violations(violations: Iterable[Violation]) -> str:
    return "\n".join(f"- {item.path}:{item.line}: {item.text} ({item.pattern})" for item in violations)


def run_self_test() -> int:
    bad = user_body_violations(".github/pull_request_template.md", "## TDD Evidence\nFirst RED:\n")
    good = user_body_violations(".github/pull_request_template.md", "## TDD 근거\n최초 RED:\n")
    legacy = user_body_violations(".codex/harness/pr_lint.py", '("최초 RED:", ("최초 RED:", "First RED:")),')
    checks = [bool(bad), not good, not legacy]
    if all(checks):
        print("self-test passed: user_language_check")
        return 0
    print("self-test failed: user_language_check", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check Korean-first labels in user-facing generated artifacts.")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return run_self_test()
    violations = scan(iter_target_files())
    if not violations:
        print("user-facing language check passed")
        return 0
    print("user-facing language check failed", file=sys.stderr)
    print(format_violations(violations), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

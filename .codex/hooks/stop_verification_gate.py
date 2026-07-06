#!/usr/bin/env python3
"""Stop-time verification reminder for Home Search.

Downgraded from a blocking gate to a non-blocking reminder (harness diet R2).
The old gate parsed the conversation transcript with regexes and blocked the
stop unless evidence phrases were present; that coupled the hook to model
wording and produced false blocks. This version only reports which
verification commands the current diff suggests, and never blocks.

Evidence still belongs in the PR body: 검증 근거 확인 명령 결과와 TDD 근거
(최초 RED / 예상 RED 실패 / 최소 GREEN)는 pr_lint가 PR 본문에서 검사한다.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

HARNESS_DIR = Path(__file__).resolve().parents[1] / "harness"
if str(HARNESS_DIR) not in sys.path:
    sys.path.insert(0, str(HARNESS_DIR))

try:
    from pr_evidence import ordered_commands, requirements_for_changed_files
except ImportError:  # pragma: no cover - harness layout changed
    ordered_commands = None
    requirements_for_changed_files = None


def load_payload() -> dict:
    try:
        raw = sys.stdin.read()
    except OSError:
        return {}
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def repo_root(payload: dict) -> Path | None:
    cwd = Path(str(payload.get("cwd") or Path.cwd()))
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=cwd,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    top = result.stdout.strip()
    return Path(top) if result.returncode == 0 and top else None


def changed_files(root: Path) -> list[str]:
    try:
        result = subprocess.run(
            ["git", "status", "--short", "--untracked-files=all"],
            cwd=root,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    files: list[str] = []
    for line in result.stdout.splitlines():
        if len(line) <= 3:
            continue
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        if path:
            files.append(path)
    return files


def reminder_lines(files: list[str]) -> list[str]:
    if not files or requirements_for_changed_files is None or ordered_commands is None:
        return []
    requirements = requirements_for_changed_files(files)
    lines = ["검증 리마인더 (비차단): 현재 변경 기준 권장 검증 명령"]
    for command in ordered_commands(requirements.commands):
        lines.append(f"- {command}")
    for path, reason in requirements.forbidden_paths:
        lines.append(f"- 경고: {path}: {reason}")
    lines.append("검증 결과와 TDD 근거는 PR 본문에 남기세요 (pr_lint가 검사).")
    return lines


def run_self_test() -> int:
    with_changes = reminder_lines(["apps/api/src/main/java/Foo.java"])
    harness_change = reminder_lines([".codex/harness/home_flow.py"])
    empty = reminder_lines([])
    checks = [
        bool(with_changes),
        any("backendQualityCheck" in line for line in with_changes),
        any("git diff --check" in line for line in with_changes),
        any("home_flow.py --self-test" in line for line in harness_change),
        all("decision" not in line for line in with_changes),
        empty == [],
        "비차단" in (with_changes[0] if with_changes else ""),
    ]
    if all(checks):
        print("self-test passed: stop_verification_gate")
        return 0
    print("self-test failed: stop_verification_gate", file=sys.stderr)
    return 1


def main() -> None:
    payload = load_payload()
    root = repo_root(payload)
    if root is None:
        return
    lines = reminder_lines(changed_files(root))
    if lines:
        print("\n".join(lines))


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    main()

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

import subprocess
import sys
from pathlib import Path

from hook_common import load_payload, repo_root_from_payload

HARNESS_DIR = Path(__file__).resolve().parents[1] / "harness"
if str(HARNESS_DIR) not in sys.path:
    sys.path.insert(0, str(HARNESS_DIR))

try:
    from pr_evidence import ordered_commands, parse_git_status, requirements_for_changed_files
except ImportError:  # pragma: no cover - harness layout changed
    ordered_commands = None
    parse_git_status = None
    requirements_for_changed_files = None


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
    if parse_git_status is None:
        return []
    return parse_git_status(result.stdout)


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


def stop_stdout(files: list[str]) -> str:
    """Return Stop hook stdout.

    Codex parses Stop hook stdout as JSON. This hook is intentionally
    non-blocking, so it must stay quiet instead of printing a plain-text
    reminder that would be treated as invalid JSON by the hook runner.
    """
    _ = files
    return ""


def run_self_test() -> int:
    with_changes = reminder_lines(["apps/home-data/src/main/java/Foo.java"])
    harness_change = reminder_lines([".codex/harness/home_flow.py"])
    empty = reminder_lines([])
    quiet_stop = stop_stdout(["apps/home-data/src/main/java/Foo.java"])
    checks = [
        bool(with_changes),
        any("backendQualityCheck" in line for line in with_changes),
        any("git diff --check" in line for line in with_changes),
        any("home_flow.py --self-test" in line for line in harness_change),
        all("decision" not in line for line in with_changes),
        empty == [],
        "비차단" in (with_changes[0] if with_changes else ""),
        quiet_stop == "",
    ]
    if all(checks):
        print("self-test passed: stop_verification_gate")
        return 0
    print("self-test failed: stop_verification_gate", file=sys.stderr)
    return 1


def main() -> None:
    payload = load_payload()
    root = repo_root_from_payload(payload)
    output = stop_stdout(changed_files(root))
    if output:
        print(output)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    main()

#!/usr/bin/env python3
"""Push an integration branch and open a draft GitHub PR."""

from __future__ import annotations

import argparse
import json
import shutil
import shlex
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_lint import PrInput, format_grouped_errors, lint_pr, valid_body


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MAIN = Path("/Users/gwongwangjae/home-search")
FORBIDDEN_BRANCHES = {"main", "master"}


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def printable(command: list[str]) -> str:
    return " ".join(shlex.quote(str(part)) for part in command)


def run_cmd(args: list[str], cwd: Path) -> dict[str, Any]:
    try:
        result = subprocess.run(
            args,
            cwd=cwd,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        return {"status": "fail", "exit_code": None, "stdout": "", "stderr": str(exc), "summary": str(exc)}
    text = result.stderr or result.stdout
    summary = " | ".join(line.strip() for line in text.splitlines() if line.strip())
    return {
        "status": "pass" if result.returncode == 0 else "fail",
        "exit_code": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "summary": summary,
    }


def fail(message: str, code: int = 1) -> int:
    print(f"상태: Fail\n차단 사유: {message}\n다음 행동: 수동 명령을 확인한 뒤 다시 실행하세요.")
    return code


def validate_branch(branch: str) -> None:
    if branch in FORBIDDEN_BRANCHES:
        raise ValueError("main/master branch는 push 또는 PR head로 사용할 수 없습니다")
    if not branch.startswith("feat/") or not branch.endswith("-integration"):
        raise ValueError("integration branch는 feat/*-integration 형식이어야 합니다")


def branch_exists(branch: str, cwd: Path) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"refs/heads/{branch}"],
        cwd=cwd,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def changed_files_for_branch(main: Path, base: str, branch: str) -> tuple[str, ...]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", f"{base}...{branch}"],
        cwd=main,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        message = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
        raise ValueError(f"changed file 목록을 만들 수 없습니다: {message}")
    return tuple(part.decode("utf-8") for part in result.stdout.split(b"\0") if part)


def check_pr_inputs(
    args: argparse.Namespace,
    body_path: Path,
    changed_files: tuple[str, ...],
    *,
    enforce_changed_file_rules: bool,
) -> None:
    body = body_path.read_text(encoding="utf-8")
    result = lint_pr(
        PrInput(
            title=args.title,
            body=body,
            base=args.base,
            head=args.branch,
            draft=bool(args.draft),
            changed_files=changed_files,
        ),
        enforce_changed_file_rules=enforce_changed_file_rules,
    )
    if not result.ok:
        raise ValueError("PR lint 검사 실패\n" + format_grouped_errors(result.errors))


def manual_commands(args: argparse.Namespace) -> str:
    push = ["git", "push", "-u", "origin", args.branch]
    create = [
        "gh",
        "pr",
        "create",
        "--draft",
        "--base",
        args.base,
        "--head",
        args.branch,
        "--title",
        args.title,
        "--body-file",
        args.body_file,
    ]
    return "\n".join([printable(push), printable(create)])


def gh_ready(cwd: Path) -> tuple[bool, str]:
    if shutil.which("gh") is None:
        return False, "gh CLI를 찾을 수 없습니다"
    result = run_cmd(["gh", "auth", "status"], cwd)
    if result["status"] != "pass":
        return False, result["summary"] or "gh auth status 실패"
    return True, ""


def update_payload(path: str | None, pr_url: str, *, dry_run: bool) -> None:
    if not path:
        return
    payload_path = Path(path)
    if dry_run:
        print(f"[DRY-RUN] update payload links.pr_url: {payload_path}")
        return
    try:
        payload = json.loads(payload_path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("payload JSON root is not an object")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"warning: payload 업데이트 실패: {exc}", file=sys.stderr)
        return
    payload.setdefault("links", {})["pr_url"] = pr_url
    payload.setdefault("commands", {})["push_command_suggestion"] = "completed by v1_pr.py"
    payload["next_action"] = "GitHub PR diff/checks/local report를 확인한 뒤 수동 merge 결정"
    events = payload.setdefault("events", [])
    if isinstance(events, list):
        events.append({"at": now_iso(), "event": "draft_pr_created", "url": pr_url})
    payload_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_reporters(args: argparse.Namespace) -> None:
    if not args.payload_json or not (args.notion or args.slack):
        if args.notion or args.slack:
            print("warning: payload JSON이 없어 Notion/Slack PR URL 반영을 건너뜁니다.", file=sys.stderr)
        return
    script = Path(__file__).with_name("v1_report.py")
    command = [
        sys.executable,
        str(script),
        "--input-json",
        args.payload_json,
        "--payload-out",
        args.payload_json,
    ]
    if args.notion:
        command.append("--notion")
    if args.slack:
        command.append("--slack")
    result = run_cmd(command, DEFAULT_MAIN)
    if result["status"] != "pass":
        print(f"warning: report notification failed: {result['summary']}", file=sys.stderr)


def create_pr(args: argparse.Namespace) -> int:
    main = Path(args.main_worktree).resolve()
    body_path = Path(args.body_file).resolve()
    try:
        validate_branch(args.branch)
        if args.dry_run:
            check_pr_inputs(args, body_path, (), enforce_changed_file_rules=False)
        else:
            if not main.exists():
                return fail(f"main worktree not found: {main}")
            if not branch_exists(args.branch, main):
                return fail(f"local integration branch not found: {args.branch}", 2)
            check_pr_inputs(
                args,
                body_path,
                changed_files_for_branch(main, args.base, args.branch),
                enforce_changed_file_rules=True,
            )
    except (OSError, ValueError) as exc:
        return fail(str(exc), 2)

    push_command = ["git", "push", "-u", "origin", args.branch]
    pr_command = [
        "gh",
        "pr",
        "create",
        "--base",
        args.base,
        "--head",
        args.branch,
        "--title",
        args.title,
        "--body-file",
        str(body_path),
    ]
    if args.draft:
        pr_command.insert(3, "--draft")

    if args.dry_run:
        print("상태: dry-run")
        print(f"branch: {args.branch}")
        print(f"body: {body_path}")
        print(f"push: {printable(push_command)}")
        print(f"pr: {printable(pr_command)}")
        update_payload(args.payload_json, "DRY_RUN_PR_URL", dry_run=True)
        return 0

    ready, reason = gh_ready(main)
    if not ready:
        print("수동 실행 명령:", file=sys.stderr)
        print(manual_commands(args), file=sys.stderr)
        return fail(reason, 3)

    push_result = run_cmd(push_command, main)
    if push_result["status"] != "pass":
        return fail(f"git push 실패: {push_result['summary']}", 4)

    pr_result = run_cmd(pr_command, main)
    if pr_result["status"] != "pass":
        print("수동 실행 명령:", file=sys.stderr)
        print(manual_commands(args), file=sys.stderr)
        return fail(f"gh pr create 실패: {pr_result['summary']}", 5)

    output_lines = [line.strip() for line in (pr_result["stdout"] or pr_result["stderr"]).splitlines() if line.strip()]
    if not output_lines:
        return fail("gh pr create succeeded but returned no PR URL", 5)
    pr_url = output_lines[-1]
    update_payload(args.payload_json, pr_url, dry_run=False)
    run_reporters(args)
    print("상태: Pass")
    print(f"pr_url: {pr_url}")
    print("다음 행동: GitHub PR diff와 checks를 확인한 뒤 수동 merge를 결정하세요.")
    return 0


def run_self_test() -> int:
    parser = build_parser()
    args = parser.parse_args(
        [
            "--branch",
            "feat/map-contract-hardening-integration",
            "--title",
            "V1 map contract hardening",
            "--body-file",
            ".codex/harness/reports/self-test-pr-body.md",
            "--dry-run",
        ]
    )
    checks: list[bool] = []
    try:
        validate_branch(args.branch)
        checks.append(True)
    except ValueError:
        checks.append(False)
    try:
        validate_branch("main")
        checks.append(False)
    except ValueError:
        checks.append(True)
    checks.append("--draft" in manual_commands(args))
    checks.append(args.draft is True)
    checks.append(
        lint_pr(
            PrInput(
                title="V1 map-contract-hardening integration",
                body=valid_body(),
                base="main",
                head=args.branch,
                draft=True,
                changed_files=(),
            )
        ).ok
    )
    checks.append(
        not lint_pr(
            PrInput(
                title="V1 map-contract-hardening integration",
                body=valid_body(),
                base="main",
                head=args.branch,
                draft=False,
                changed_files=(),
            )
        ).ok
    )
    if all(checks):
        print("self-test passed: v1_pr")
        return 0
    print("self-test failed: v1_pr", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Push an integration branch and create a draft PR.")
    parser.add_argument("--branch", help="Integration branch, must match feat/*-integration.")
    parser.add_argument("--base", default="main")
    parser.add_argument("--title")
    parser.add_argument("--body-file")
    draft = parser.add_mutually_exclusive_group()
    draft.add_argument("--draft", dest="draft", action="store_true", help="Create a draft PR.")
    draft.add_argument("--no-draft", dest="draft", action="store_false", help="Rejects before PR creation; kept for lint tests.")
    parser.set_defaults(draft=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--notion", action="store_true", help="Best-effort Notion report after PR URL is known.")
    parser.add_argument("--slack", action="store_true", help="Best-effort Slack report after PR URL is known.")
    parser.add_argument("--payload-json", help="Payload JSON to update with links.pr_url.")
    parser.add_argument("--main-worktree", default=str(DEFAULT_MAIN))
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    missing = [name for name in ("branch", "title", "body_file") if not getattr(args, name)]
    if missing:
        parser.error("missing required arguments: " + ", ".join("--" + name.replace("_", "-") for name in missing))
    return create_pr(args)


if __name__ == "__main__":
    raise SystemExit(main())

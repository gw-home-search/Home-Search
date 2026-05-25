#!/usr/bin/env python3
"""Merge prepared Home Search work item branches into an integration branch."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MAIN = Path("/Users/gwongwangjae/home-search")
REPORT_ROOT = DEFAULT_MAIN / ".codex" / "harness" / "reports"


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def run_cmd(args: list[str], cwd: Path, *, dry_run: bool = False) -> dict[str, Any]:
    printable = " ".join(args)
    if dry_run:
        print(f"[DRY-RUN] ({cwd}) {printable}")
        return {"status": "skipped", "exit_code": None, "stdout": "", "stderr": "", "summary": "dry-run"}
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
        return {
            "status": "fail",
            "exit_code": None,
            "stdout": "",
            "stderr": str(exc),
            "summary": str(exc),
        }
    summary = first_lines(result.stderr or result.stdout)
    return {
        "status": "pass" if result.returncode == 0 else "fail",
        "exit_code": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "summary": summary,
    }


def first_lines(text: str, limit: int = 3) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return " | ".join(lines[:limit])


def fail(message: str, code: int = 1) -> int:
    print(f"상태: Fail\n차단 사유: {message}\n다음 행동: 원인을 확인한 뒤 다시 실행하세요.")
    return code


def git(main: Path, *args: str, dry_run: bool = False) -> dict[str, Any]:
    return run_cmd(["git", *args], main, dry_run=dry_run)


def git_output(main: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=main, check=False, text=True, stdout=subprocess.PIPE)
    return result.stdout.strip() if result.returncode == 0 else ""


def git_root(cwd: Path) -> Path | None:
    result = subprocess.run(
        ["git", "-C", str(cwd), "rev-parse", "--show-toplevel"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        return None
    root = result.stdout.strip()
    return Path(root).resolve(strict=False) if root else None


def is_current_main_worktree(main: Path) -> bool:
    current_root = git_root(Path.cwd().resolve(strict=False))
    return current_root == main.resolve(strict=False)


def is_clean(main: Path) -> bool:
    status = git_output(main, "status", "--porcelain", "--untracked-files=all")
    return status == ""


def branch_exists(main: Path, branch: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"refs/heads/{branch}"],
        cwd=main,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def merge_branch(main: Path, branch: str, *, dry_run: bool) -> dict[str, Any]:
    result = git(main, "merge", "--no-ff", "--no-edit", branch, dry_run=dry_run)
    if result["status"] == "fail" and not dry_run:
        git(main, "merge", "--abort")
    return result


def verify(main: Path, *, dry_run: bool) -> dict[str, Any]:
    checks: list[tuple[str, Path, list[str]]] = [
        ("cd apps/api && ./gradlew backendQualityCheck", main / "apps" / "api", ["./gradlew", "backendQualityCheck"]),
        ("cd apps/web && npm run test", main / "apps" / "web", ["npm", "run", "test"]),
        ("cd apps/web && npm run build", main / "apps" / "web", ["npm", "run", "build"]),
        ("git diff --check", main, ["git", "diff", "--check"]),
    ]
    verification: dict[str, Any] = {}
    for label, cwd, args in checks:
        result = run_cmd(args, cwd, dry_run=dry_run)
        verification[label] = {
            "status": result["status"],
            "exit_code": result["exit_code"],
            "summary": result["summary"],
        }
        if result["status"] == "fail":
            raise RuntimeError(f"검증 실패: {label}: {result['summary']}")
    return verification


def payload_path_for(payload: dict[str, Any]) -> Path:
    work_id = str(payload.get("work_id") or payload.get("slice") or "unknown").strip() or "unknown"
    return REPORT_ROOT / f"{work_id}.json"


def call_report(payload: dict[str, Any], *, notion: bool, slack: bool, dry_run: bool) -> None:
    script = Path(__file__).with_name("home_report.py")
    cmd = [sys.executable, str(script), "--input-json", "-", "--payload-out", str(payload_path_for(payload))]
    if notion:
        cmd.append("--notion")
    if slack:
        cmd.append("--slack")
    if dry_run:
        cmd.append("--dry-run")
    subprocess.run(cmd, input=json.dumps(payload), text=True, check=False)


def main_merge_command(main: Path, integration_branch: str) -> str:
    return f"git -C {main} switch main && git -C {main} merge --no-ff {integration_branch}"


def integrate(args: argparse.Namespace) -> int:
    main = Path(args.main_worktree).resolve()
    started = now_iso()

    if args.allow_main_merge:
        return fail("--allow-main-merge is intentionally unsupported by the harness", 1)
    if args.dry_run:
        print("[DRY-RUN] integration preflight")
        print(f"[DRY-RUN] main worktree: {main}")
        print(f"[DRY-RUN] api branch: {args.api_branch}")
        print(f"[DRY-RUN] web branch: {args.web_branch}")
        print(f"[DRY-RUN] integration branch: {args.integration_branch}")
    else:
        if not main.exists():
            return fail(f"main worktree not found: {main}")
        if not is_current_main_worktree(main):
            return fail(f"main worktree에서 실행해야 합니다: {main}")
        if not is_clean(main):
            return fail("main worktree is dirty")
        for branch in (args.api_branch, args.web_branch):
            if not branch_exists(main, branch):
                return fail(f"branch not found: {branch}")

    status = "Pass"
    verification: dict[str, Any] = {}
    merges = {"api": "skipped", "web": "skipped"}

    try:
        if args.verify_only:
            verification = verify(main, dry_run=args.dry_run)
        else:
            if not args.dry_run and branch_exists(main, args.integration_branch):
                return fail(f"integration branch already exists: {args.integration_branch}")
            switch_base = git(main, "switch", args.base_branch, dry_run=args.dry_run)
            if switch_base["status"] == "fail":
                raise RuntimeError(f"base branch switch failed: {switch_base['summary']}")
            create_branch = git(main, "switch", "-c", args.integration_branch, dry_run=args.dry_run)
            if create_branch["status"] == "fail":
                raise RuntimeError(f"integration branch create failed: {create_branch['summary']}")

            api_merge = merge_branch(main, args.api_branch, dry_run=args.dry_run)
            merges["api"] = "merged" if api_merge["status"] != "fail" else "failed"
            if api_merge["status"] == "fail":
                payload = payload_for(args, main, started, "Fail", verification, merges, api_merge["summary"])
                if args.report or args.notion or args.slack:
                    call_report(payload, notion=args.notion, slack=args.slack, dry_run=args.dry_run)
                return fail(f"api merge conflict or failure: {api_merge['summary']}", 2)

            web_merge = merge_branch(main, args.web_branch, dry_run=args.dry_run)
            merges["web"] = "merged" if web_merge["status"] != "fail" else "failed"
            if web_merge["status"] == "fail":
                payload = payload_for(args, main, started, "Fail", verification, merges, web_merge["summary"])
                if args.report or args.notion or args.slack:
                    call_report(payload, notion=args.notion, slack=args.slack, dry_run=args.dry_run)
                return fail(f"web merge conflict or failure: {web_merge['summary']}", 2)

            verification = verify(main, dry_run=args.dry_run)
    except RuntimeError as exc:
        status = "Fail"
        payload = payload_for(args, main, started, status, verification, merges, str(exc))
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=args.dry_run)
        return fail(str(exc), 3)

    payload = payload_for(args, main, started, status, verification, merges, "없음")
    if args.report or args.notion or args.slack:
        call_report(payload, notion=args.notion, slack=args.slack, dry_run=args.dry_run)

    print("상태: Pass")
    print(f"integration branch: {args.integration_branch}")
    print("다음 행동:")
    print(main_merge_command(main, args.integration_branch))
    return 0


def payload_for(
    args: argparse.Namespace,
    main: Path,
    started: str,
    status: str,
    verification: dict[str, Any],
    merges: dict[str, str],
    risk: str,
) -> dict[str, Any]:
    integration_head = "" if args.dry_run else git_output(main, "rev-parse", "--short", "HEAD")
    return {
        "work_id": args.integration_branch.replace("feat/", "").replace("-integration", ""),
        "preset": "integration",
        "status": status,
        "started_at": started,
        "finished_at": now_iso(),
        "branches": {
            "api": args.api_branch,
            "web": args.web_branch,
            "integration": args.integration_branch,
        },
        "worktrees": {"main": str(main), "api": None, "web": None},
        "commits": {"integration_head": integration_head},
        "verification": verification,
        "gate_review": f"merge api={merges['api']}, web={merges['web']}",
        "contract_risks": [],
        "residual_risks": [] if risk == "없음" else [risk],
        "next_action": "integration branch를 눈으로 검토한 뒤 main merge 명령을 수동 실행",
        "commands": {
            "main_merge_command": main_merge_command(main, args.integration_branch),
            "push_command_suggestion": "git push origin <branch>",
        },
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Merge api/web work branches into an integration branch.")
    parser.add_argument("--api-branch")
    parser.add_argument("--web-branch")
    parser.add_argument("--integration-branch")
    parser.add_argument("--main-worktree", default=str(DEFAULT_MAIN))
    parser.add_argument("--base-branch", default="main")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--notion", action="store_true")
    parser.add_argument("--slack", action="store_true")
    parser.add_argument("--allow-main-merge", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser


def run_self_test() -> int:
    parser = build_parser()
    args = parser.parse_args([
        "--api-branch",
        "feat/api-work",
        "--web-branch",
        "feat/web-work",
        "--integration-branch",
        "feat/sample-integration",
        "--dry-run",
        "--verify-only",
    ])
    payload = payload_for(
        args,
        DEFAULT_MAIN,
        "2026-05-25T00:00:00+09:00",
        "Pass",
        {"git diff --check": {"status": "pass"}},
        {"api": "skipped", "web": "skipped"},
        "없음",
    )
    checks = [
        args.dry_run,
        args.verify_only,
        payload["work_id"] == "sample",
        payload["branches"]["api"] == "feat/api-work",
        "git -C" in main_merge_command(DEFAULT_MAIN, "feat/sample-integration"),
    ]
    if all(checks):
        print("self-test passed: home_integrate")
        return 0
    print("self-test failed: home_integrate", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    missing = [
        name for name in ("api_branch", "web_branch", "integration_branch")
        if not getattr(args, name)
    ]
    if missing:
        parser.error("the following arguments are required: " + ", ".join(f"--{name.replace('_', '-')}" for name in missing))
    return integrate(args)


if __name__ == "__main__":
    raise SystemExit(main())

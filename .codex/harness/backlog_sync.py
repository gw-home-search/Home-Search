#!/usr/bin/env python3
"""Synchronize V1 harness backlog status from verified slice completion."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import tempfile
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

HARNESS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = HARNESS_ROOT.parents[1]
BACKLOG_PATH = HARNESS_ROOT / "slices" / "backlog.toml"
VALID_STATUSES = {"candidate", "planned", "running", "done", "blocked"}
AUTO_DONE_STATUSES = {"candidate", "planned", "running"}


@dataclass(frozen=True)
class SliceState:
    id: str
    status: str


@dataclass(frozen=True)
class SyncResult:
    slice_id: str
    status: str
    old_status: str | None
    new_status: str | None
    summary: str
    changed: bool = False
    pr_number: int | None = None
    pr_url: str | None = None


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise ValueError("slice id must contain at least one alphanumeric character")
    return slug


def printable(command: list[str]) -> str:
    return " ".join(shlex.quote(str(part)) for part in command)


def run_cmd(args: list[str], cwd: Path = REPO_ROOT) -> dict[str, Any]:
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


def load_backlog(path: Path = BACKLOG_PATH) -> list[SliceState]:
    try:
        with path.open("rb") as handle:
            payload = tomllib.load(handle)
    except tomllib.TOMLDecodeError as exc:
        raise ValueError(f"backlog TOML parse failed: {exc}") from exc
    raw_slices = payload.get("slices")
    if not isinstance(raw_slices, list):
        raise ValueError("backlog must contain [[slices]] entries")
    output: list[SliceState] = []
    for raw in raw_slices:
        if not isinstance(raw, dict):
            raise ValueError("each backlog slice must be a table")
        slice_id = slugify(str(raw.get("id", "")))
        status = str(raw.get("status", "")).strip()
        if status not in VALID_STATUSES:
            raise ValueError(f"{slice_id} has invalid status: {status}")
        output.append(SliceState(slice_id, status))
    ids = [item.id for item in output]
    duplicates = sorted({item for item in ids if ids.count(item) > 1})
    if duplicates:
        raise ValueError(f"duplicate slice ids: {', '.join(duplicates)}")
    return output


def slice_blocks(text: str) -> Iterable[tuple[int, int, str]]:
    starts = [match.start() for match in re.finditer(r"(?m)^\[\[slices\]\]\s*$", text)]
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(text)
        yield start, end, text[start:end]


def block_id(block: str) -> str | None:
    match = re.search(r'(?m)^id\s*=\s*"([^"]+)"\s*$', block)
    if not match:
        return None
    return slugify(match.group(1))


def block_status(block: str) -> str | None:
    match = re.search(r'(?m)^status\s*=\s*"([^"]+)"\s*$', block)
    if not match:
        return None
    return match.group(1)


def replace_block_status(block: str, new_status: str) -> str:
    updated, count = re.subn(
        r'(?m)^(status\s*=\s*)"[^"]+"(\s*)$',
        rf'\1"{new_status}"\2',
        block,
        count=1,
    )
    if count != 1:
        raise ValueError("slice block status line not found")
    return updated


def plan_status_update(text: str, slice_id: str, new_status: str = "done") -> tuple[str, SyncResult]:
    requested = slugify(slice_id)
    for start, end, block in slice_blocks(text):
        if block_id(block) != requested:
            continue
        old_status = block_status(block)
        if old_status is None:
            return text, SyncResult(requested, "fail", None, new_status, "status line not found")
        if old_status not in VALID_STATUSES:
            return text, SyncResult(requested, "fail", old_status, new_status, f"invalid status: {old_status}")
        if old_status == new_status:
            return text, SyncResult(requested, "skipped", old_status, new_status, "already done")
        if old_status == "blocked":
            return text, SyncResult(requested, "conflict", old_status, new_status, "blocked slice requires manual decision")
        if old_status not in AUTO_DONE_STATUSES:
            return text, SyncResult(requested, "fail", old_status, new_status, f"cannot auto-sync from {old_status}")
        updated_block = replace_block_status(block, new_status)
        updated_text = text[:start] + updated_block + text[end:]
        tomllib.loads(updated_text)
        return updated_text, SyncResult(requested, "pass", old_status, new_status, f"{old_status} -> {new_status}", changed=True)
    return text, SyncResult(requested, "skipped", None, new_status, "slice not in backlog")


def mark_slice_done(path: Path, slice_id: str, *, dry_run: bool = False) -> SyncResult:
    text = path.read_text(encoding="utf-8")
    updated_text, result = plan_status_update(text, slice_id, "done")
    if result.status == "pass" and not dry_run:
        path.write_text(updated_text, encoding="utf-8")
    return result


def expected_head(slice_id: str) -> str:
    return f"feat/{slugify(slice_id)}-integration"


def merged_pr_for_slice(slice_id: str, prs: Iterable[dict[str, Any]], *, base: str) -> dict[str, Any] | None:
    head = expected_head(slice_id)
    for pr in prs:
        if str(pr.get("headRefName") or "") != head:
            continue
        if str(pr.get("baseRefName") or "") != base:
            continue
        if str(pr.get("state") or "").upper() == "MERGED" or pr.get("mergedAt"):
            return pr
    return None


def fetch_prs(*, repo: str | None, limit: int, cwd: Path = REPO_ROOT) -> list[dict[str, Any]]:
    command = [
        "gh",
        "pr",
        "list",
        "--state",
        "all",
        "--limit",
        str(limit),
        "--json",
        "number,state,mergedAt,headRefName,baseRefName,url,title",
    ]
    if repo:
        command.extend(["--repo", repo])
    result = run_cmd(command, cwd)
    if result["status"] != "pass":
        raise RuntimeError(f"gh pr list failed: {result['summary']}")
    try:
        payload = json.loads(result["stdout"])
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"gh pr list returned invalid JSON: {exc}") from exc
    if not isinstance(payload, list):
        raise RuntimeError("gh pr list JSON root must be a list")
    return [item for item in payload if isinstance(item, dict)]


def is_backlog_dirty(path: Path, cwd: Path = REPO_ROOT) -> bool:
    result = run_cmd(["git", "status", "--porcelain", "--untracked-files=all", "--", str(path)], cwd)
    if result["status"] != "pass":
        raise RuntimeError(f"git status failed: {result['summary']}")
    return bool(str(result["stdout"]).strip())


def sync_from_merged_prs(
    path: Path,
    prs: Iterable[dict[str, Any]],
    *,
    base: str,
    slice_id: str | None,
    dry_run: bool,
) -> list[SyncResult]:
    states = load_backlog(path)
    selected = [item for item in states if slice_id is None or item.id == slugify(slice_id)]
    if slice_id and not selected:
        return [SyncResult(slugify(slice_id), "skipped", None, "done", "slice not in backlog")]
    text = path.read_text(encoding="utf-8")
    results: list[SyncResult] = []
    for state in selected:
        pr = merged_pr_for_slice(state.id, prs, base=base)
        if not pr:
            results.append(SyncResult(state.id, "skipped", state.status, "done", "merged PR not found"))
            continue
        text, result = plan_status_update(text, state.id, "done")
        result = SyncResult(
            result.slice_id,
            result.status,
            result.old_status,
            result.new_status,
            result.summary,
            changed=result.changed,
            pr_number=int(pr["number"]) if str(pr.get("number") or "").isdigit() else None,
            pr_url=str(pr.get("url") or "") or None,
        )
        results.append(result)
    if any(item.changed for item in results) and not dry_run:
        path.write_text(text, encoding="utf-8")
    return results


def overall_status(results: list[SyncResult]) -> str:
    if any(item.status == "fail" for item in results):
        return "Fail"
    if any(item.status == "conflict" for item in results):
        return "Partial"
    return "Pass"


def print_results(results: list[SyncResult], *, dry_run: bool) -> None:
    print(f"상태: {overall_status(results)}")
    changed = [item for item in results if item.changed]
    unchanged = [item for item in results if item.status == "skipped"]
    conflicts = [item for item in results if item.status == "conflict"]
    failures = [item for item in results if item.status == "fail"]
    prefix = "동기화 예정" if dry_run else "동기화 대상"
    print(f"{prefix}:")
    if changed:
        for item in changed:
            pr = f" PR #{item.pr_number}" if item.pr_number else ""
            print(f"- {item.slice_id}: {item.old_status} -> {item.new_status}{pr}")
    else:
        print("- none")
    print("변경 없음:")
    if unchanged:
        for item in unchanged:
            print(f"- {item.slice_id}: {item.summary}")
    else:
        print("- none")
    print("conflict:")
    if conflicts:
        for item in conflicts:
            print(f"- {item.slice_id}: {item.summary}")
    else:
        print("- none")
    if failures:
        print("실패:")
        for item in failures:
            print(f"- {item.slice_id}: {item.summary}")
    print("다음 행동:")
    if changed and dry_run:
        print("- dry-run 결과를 확인한 뒤 --dry-run 없이 재실행하세요.")
    elif conflicts or failures:
        print("- conflict/failure slice를 수동으로 확인하세요.")
    else:
        print("- .codex/harness/v1 next 결과를 확인하세요.")


def run_sync(args: argparse.Namespace) -> int:
    path = Path(args.backlog).resolve()
    if not args.merged:
        return fail("--merged 기준 sync만 지원합니다", 2)
    try:
        prs = fetch_prs(repo=args.repo, limit=args.limit)
        if not args.dry_run and is_backlog_dirty(path):
            return fail(f"backlog file is dirty: {path}", 2)
        results = sync_from_merged_prs(path, prs, base=args.base, slice_id=args.slice, dry_run=args.dry_run)
    except (OSError, RuntimeError, ValueError) as exc:
        return fail(str(exc), 2)
    print_results(results, dry_run=args.dry_run)
    return 0 if overall_status(results) == "Pass" else 1


def fail(message: str, code: int = 1) -> int:
    print(f"상태: Fail\n차단 사유: {message}\n다음 행동: backlog sync 입력과 gh 인증 상태를 확인하세요.")
    return code


def run_self_test() -> int:
    sample = """[[slices]]
id = "alpha-slice"
title_ko = "Alpha"
status = "candidate"
priority = 1
targets = "backend"
preset = "contract-hardening"
acceptance_criteria = []
first_red_candidates = []
verification_commands = []
stop_conditions = []
risk_notes = []

[[slices]]
id = "beta-slice"
title_ko = "Beta"
status = "done"
priority = 2
targets = "frontend"
preset = "map-ui-state"
acceptance_criteria = []
first_red_candidates = []
verification_commands = []
stop_conditions = []
risk_notes = []

[[slices]]
id = "blocked-slice"
title_ko = "Blocked"
status = "blocked"
priority = 3
targets = "both"
preset = "runtime-smoke"
acceptance_criteria = []
first_red_candidates = []
verification_commands = []
stop_conditions = []
risk_notes = []
"""
    prs = [
        {
            "number": 101,
            "state": "MERGED",
            "mergedAt": "2026-05-22T00:00:00Z",
            "headRefName": "feat/alpha-slice-integration",
            "baseRefName": "main",
            "url": "https://example.test/pull/101",
            "title": "alpha",
        },
        {
            "number": 102,
            "state": "MERGED",
            "mergedAt": "2026-05-22T00:00:00Z",
            "headRefName": "feat/blocked-slice-integration",
            "baseRefName": "main",
            "url": "https://example.test/pull/102",
            "title": "blocked",
        },
    ]
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "backlog.toml"
        path.write_text(sample, encoding="utf-8")
        dry_results = sync_from_merged_prs(path, prs, base="main", slice_id=None, dry_run=True)
        unchanged_after_dry = path.read_text(encoding="utf-8") == sample
        write_results = sync_from_merged_prs(path, prs, base="main", slice_id=None, dry_run=False)
        written = path.read_text(encoding="utf-8")
        missing = sync_from_merged_prs(path, prs, base="main", slice_id="missing-slice", dry_run=True)
    checks = [
        any(item.slice_id == "alpha-slice" and item.changed for item in dry_results),
        any(item.slice_id == "blocked-slice" and item.status == "conflict" for item in dry_results),
        unchanged_after_dry,
        'id = "alpha-slice"' in written and 'status = "done"' in written,
        any(item.slice_id == "alpha-slice" and item.changed for item in write_results),
        missing[0].status == "skipped",
        merged_pr_for_slice("alpha-slice", prs, base="main") is not None,
        merged_pr_for_slice("beta-slice", prs, base="main") is None,
        overall_status(dry_results) == "Partial",
    ]
    if all(checks):
        print("self-test passed: backlog_sync")
        return 0
    print("self-test failed: backlog_sync", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Synchronize V1 backlog status from merged PRs.")
    parser.add_argument("--merged", action="store_true", help="Use merged GitHub PRs as the sync source.")
    parser.add_argument("--slice", help="Limit sync to one slice id.")
    parser.add_argument("--base", default="main", help="Expected PR base branch.")
    parser.add_argument("--repo", help="Optional gh --repo value.")
    parser.add_argument("--limit", type=int, default=200, help="Maximum PRs to inspect.")
    parser.add_argument("--backlog", default=str(BACKLOG_PATH), help="Backlog TOML path.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    return run_sync(args)


if __name__ == "__main__":
    raise SystemExit(main())

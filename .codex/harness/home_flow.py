#!/usr/bin/env python3
"""Run Home Search work automation from the main worktree."""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import tomllib
import tempfile
from collections.abc import Iterable
from contextlib import suppress
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_evidence import (
    API_QUALITY,
    WORKLOG_SYNC_SELF_TEST,
    DIFF_CHECK,
    DOCKER_COMPOSE_LOCAL_CONFIG,
    POST_TOOL_USE_REVIEW_SELF_TEST,
    PR_CONTEXT_SELF_TEST,
    PROJECT_TERMS_CHECK,
    PROJECT_TERMS_SELF_TEST,
    PR_LINT_SELF_TEST,
    SKILL_ROUTING_SELF_TEST,
    STOP_HOOK_SELF_TEST,
    TEST_DISPLAY_NAME_POLICY,
    HARNESS_FLOW_SELF_TEST,
    HARNESS_LAUNCHER_SELF_TEST,
    HARNESS_PLAN_SELF_TEST,
    HARNESS_PR_SELF_TEST,
    HARNESS_REPORT_SELF_TEST,
    PRE_TOOL_USE_POLICY_SELF_TEST,
    WEB_BUILD,
    WEB_TEST,
    ordered_commands,
    parse_git_status,
    requirements_for_changed_files,
)
from worklog_sync import load_worklog as load_worklog_states, mark_work_item_done
from pr_lint import PrInput, format_grouped_errors, lint_pr
from skill_routing import routing_payload, routing_text
from home_report import render_pr_body


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MAIN = Path("/Users/gwongwangjae/home-search")
DEFAULT_WORKTREE_PARENT = Path("/Users/gwongwangjae")
PRESET_DIR = Path(__file__).with_name("presets")
WORKLOG_PATH = Path(__file__).with_name("worklog.toml")
REPORT_ROOT = DEFAULT_MAIN / ".codex" / "harness" / "reports"
REPORT_KEEP_FILES = {".gitignore", ".gitkeep"}
REPORT_MAX_AGE_DAYS = 30
PR_SCRIPT = Path(__file__).with_name("home_pr.py")
PR_TITLE_TYPES = {"Feat", "Fix", "Chore", "Docs", "Test", "Refactor"}
DEFAULT_TARGETS = {
    "backend": {
        "prompt": "backend_execute.md",
        "allowed_scope": "apps/property-data/**",
        "forbidden_scope": "apps/web/**",
        "verification_commands": [API_QUALITY],
    },
    "frontend": {
        "prompt": "frontend_execute.md",
        "allowed_scope": "apps/web/**",
        "forbidden_scope": "apps/property-data/**",
        "verification_commands": [
            WEB_TEST,
            WEB_BUILD,
        ],
    },
}
TARGET_MODES = {"backend", "frontend", "both", "planning-only"}
PLANNING_MODES = {"standard", "critique", "llm-replan"}
KNOWN_VERIFICATION_COMMANDS = {
    "backend": {
        API_QUALITY: ("apps/property-data", ["./gradlew", "backendQualityCheck"]),
        DOCKER_COMPOSE_LOCAL_CONFIG: (".", ["bash", "infra/test-compose-config.sh"]),
        "bash infra/test-operating-platform-contracts.sh": (
            ".",
            ["bash", "infra/test-operating-platform-contracts.sh"],
        ),
        "cd apps/user/service && ./gradlew userServiceQualityCheck": (
            "apps/user/service",
            ["./gradlew", "userServiceQualityCheck"],
        ),
        "cd apps/property-data && ./gradlew test": ("apps/property-data", ["./gradlew", "test"]),
        DIFF_CHECK: (".", ["git", "diff", "--check", "main...HEAD"]),
        PR_LINT_SELF_TEST: (".", ["python3", ".codex/harness/pr_lint.py", "--self-test"]),
        PR_CONTEXT_SELF_TEST: (".", ["python3", ".codex/harness/pr_context.py", "--self-test"]),
        WORKLOG_SYNC_SELF_TEST: (".", ["python3", ".codex/harness/worklog_sync.py", "--self-test"]),
        HARNESS_PR_SELF_TEST: (".", ["python3", ".codex/harness/home_pr.py", "--self-test"]),
        HARNESS_FLOW_SELF_TEST: (".", ["python3", ".codex/harness/home_flow.py", "--self-test"]),
        HARNESS_PLAN_SELF_TEST: (".", ["python3", ".codex/harness/home_plan.py", "--self-test"]),
        HARNESS_REPORT_SELF_TEST: (".", ["python3", ".codex/harness/home_report.py", "--self-test"]),
        HARNESS_LAUNCHER_SELF_TEST: (".", [".codex/harness/home", "--self-test"]),
        SKILL_ROUTING_SELF_TEST: (".", ["python3", ".codex/harness/skill_routing.py", "--self-test"]),
        PROJECT_TERMS_SELF_TEST: (".", ["python3", ".codex/harness/project_terms_check.py", "--self-test"]),
        PROJECT_TERMS_CHECK: (".", ["python3", ".codex/harness/project_terms_check.py"]),
        TEST_DISPLAY_NAME_POLICY: (".", ["python3", "scripts/" + "check-test-display-names.py"]),
        STOP_HOOK_SELF_TEST: (".", ["python3", ".codex/hooks/stop_verification_gate.py", "--self-test"]),
        PRE_TOOL_USE_POLICY_SELF_TEST: (".", ["python3", ".codex/hooks/pre_tool_use_policy.py", "--self-test"]),
        POST_TOOL_USE_REVIEW_SELF_TEST: (".", ["python3", ".codex/hooks/post_tool_use_review.py", "--self-test"]),
    },
    "frontend": {
        WEB_TEST: ("apps/web", ["npm", "run", "test"]),
        WEB_BUILD: ("apps/web", ["npm", "run", "build"]),
        DOCKER_COMPOSE_LOCAL_CONFIG: (".", ["bash", "infra/test-compose-config.sh"]),
        DIFF_CHECK: (".", ["git", "diff", "--check", "main...HEAD"]),
        PR_LINT_SELF_TEST: (".", ["python3", ".codex/harness/pr_lint.py", "--self-test"]),
        PR_CONTEXT_SELF_TEST: (".", ["python3", ".codex/harness/pr_context.py", "--self-test"]),
        WORKLOG_SYNC_SELF_TEST: (".", ["python3", ".codex/harness/worklog_sync.py", "--self-test"]),
        HARNESS_PR_SELF_TEST: (".", ["python3", ".codex/harness/home_pr.py", "--self-test"]),
        HARNESS_FLOW_SELF_TEST: (".", ["python3", ".codex/harness/home_flow.py", "--self-test"]),
        HARNESS_PLAN_SELF_TEST: (".", ["python3", ".codex/harness/home_plan.py", "--self-test"]),
        HARNESS_REPORT_SELF_TEST: (".", ["python3", ".codex/harness/home_report.py", "--self-test"]),
        HARNESS_LAUNCHER_SELF_TEST: (".", [".codex/harness/home", "--self-test"]),
        SKILL_ROUTING_SELF_TEST: (".", ["python3", ".codex/harness/skill_routing.py", "--self-test"]),
        PROJECT_TERMS_SELF_TEST: (".", ["python3", ".codex/harness/project_terms_check.py", "--self-test"]),
        PROJECT_TERMS_CHECK: (".", ["python3", ".codex/harness/project_terms_check.py"]),
        TEST_DISPLAY_NAME_POLICY: (".", ["python3", "scripts/" + "check-test-display-names.py"]),
        STOP_HOOK_SELF_TEST: (".", ["python3", ".codex/hooks/stop_verification_gate.py", "--self-test"]),
        PRE_TOOL_USE_POLICY_SELF_TEST: (".", ["python3", ".codex/hooks/pre_tool_use_policy.py", "--self-test"]),
        POST_TOOL_USE_REVIEW_SELF_TEST: (".", ["python3", ".codex/hooks/post_tool_use_review.py", "--self-test"]),
    },
}
FORBIDDEN_SAFETY_FLAGS = {
    "allow_main_merge": "main merge",
    "allow_push": "push",
    "allow_open_api_call": "Open API 호출",
    "allow_db_migration_run": "DB migration 실행",
}


class PresetError(ValueError):
    """Raised when a work item cannot be mapped to exactly one safe preset."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise ValueError("--work-id must contain at least one alphanumeric character")
    return slug


def load_presets() -> dict[str, dict[str, Any]]:
    presets: dict[str, dict[str, Any]] = {}
    if not PRESET_DIR.exists():
        return presets
    for path in sorted(PRESET_DIR.glob("*.toml")):
        with path.open("rb") as handle:
            data = tomllib.load(handle)
        preset_id = slugify(str(data.get("id") or path.stem))
        data["id"] = preset_id
        data["_path"] = str(path)
        data.setdefault("aliases", [])
        data.setdefault("slice_patterns", [])
        data.setdefault("targets", {})
        data.setdefault("safety", {})
        validate_safe_preset(data)
        presets[preset_id] = data
    return presets


def validate_safe_preset(preset: dict[str, Any]) -> None:
    safety = preset.get("safety", {})
    if not isinstance(safety, dict):
        raise PresetError(f"invalid safety table in preset: {preset.get('id')}")
    enabled = [label for flag, label in FORBIDDEN_SAFETY_FLAGS.items() if safety.get(flag)]
    if enabled:
        raise PresetError(f"preset {preset.get('id')} enables forbidden automation: {', '.join(enabled)}")


def execution_targets(args: argparse.Namespace) -> list[str]:
    targets = getattr(args, "targets", "both")
    if targets == "planning-only":
        return []
    if targets == "both":
        return ["backend", "frontend"]
    if targets in {"backend", "frontend"}:
        return [targets]
    raise RuntimeError(f"지원하지 않는 target: {targets}")


def branch_for_target(names: dict[str, Any], target: str) -> str:
    return names["api_branch"] if target == "backend" else names["web_branch"]


def worktree_for_target(names: dict[str, Any], target: str) -> Path:
    return names["api_worktree"] if target == "backend" else names["web_worktree"]


def target_payload_key(target: str) -> str:
    return "api" if target == "backend" else "web"


def preset_ids() -> list[str]:
    return sorted(load_presets())


def describe_presets(presets: dict[str, dict[str, Any]] | None = None) -> str:
    current = load_presets() if presets is None else presets
    if not current:
        return "사용 가능한 preset 없음"
    return ", ".join(sorted(current))


def _preset_exact_names(preset: dict[str, Any]) -> set[str]:
    names = {slugify(str(preset["id"]))}
    names.update(slugify(str(alias)) for alias in preset.get("aliases", []))
    return names


def resolve_preset(work_id: str, explicit: str | None = None) -> tuple[str, dict[str, Any]]:
    presets = load_presets()
    if not presets:
        raise PresetError("preset 파일을 찾을 수 없습니다")

    if explicit:
        requested = slugify(explicit)
        exact = [
            (preset_id, preset)
            for preset_id, preset in presets.items()
            if requested in _preset_exact_names(preset)
        ]
        if len(exact) == 1:
            return exact[0]
        if len(exact) > 1:
            raise PresetError(f"preset 옵션이 여러 항목과 일치합니다: {explicit}")
        raise PresetError(f"지원하지 않는 preset: {explicit}. 가능: {describe_presets(presets)}")

    work_slug = slugify(work_id)
    exact_matches = [
        (preset_id, preset)
        for preset_id, preset in presets.items()
        if work_slug in _preset_exact_names(preset)
    ]
    if len(exact_matches) == 1:
        return exact_matches[0]
    if len(exact_matches) > 1:
        raise PresetError(f"work id가 여러 preset alias와 일치합니다: {work_slug}")

    pattern_matches = []
    for preset_id, preset in presets.items():
        patterns = [slugify(str(pattern)) for pattern in preset.get("slice_patterns", [])]
        if any(pattern and pattern in work_slug for pattern in patterns):
            pattern_matches.append((preset_id, preset))
    if len(pattern_matches) == 1:
        return pattern_matches[0]
    if len(pattern_matches) > 1:
        matched = ", ".join(preset_id for preset_id, _ in pattern_matches)
        raise PresetError(f"work id가 여러 preset pattern과 일치합니다: {matched}")
    raise PresetError(f"preset을 결정할 수 없습니다: {work_slug}. 가능: {describe_presets(presets)}")


def target_config(args: argparse.Namespace, target: str) -> dict[str, Any]:
    key = "backend" if target == "backend" else "frontend"
    preset = getattr(args, "preset_config", None) or {}
    configured = preset.get("targets", {}).get(key, {})
    defaults = DEFAULT_TARGETS[key]
    return {
        "prompt": configured.get("prompt") or defaults["prompt"],
        "allowed_scope": configured.get("allowed_scope") or defaults["allowed_scope"],
        "forbidden_scope": configured.get("forbidden_scope") or defaults["forbidden_scope"],
        "verification_commands": configured.get("verification_commands") or defaults["verification_commands"],
    }


def first_lines(text: str, limit: int = 3) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return " | ".join(lines[:limit])


def verification_evidence_text(verification: dict[str, Any]) -> str:
    if not verification:
        return "- `verification` = not run (실행된 검증 명령이 없음)"
    lines = []
    for command, result in verification.items():
        raw_status = str(result.get("status", "skipped"))
        status = {"pass": "pass", "fail": "fail"}.get(raw_status, "not run")
        exit_code = result.get("exit_code")
        if status == "pass":
            reason = f"harness 실행 완료, exit={exit_code}"
        elif status == "fail":
            reason = f"harness 실행 실패, exit={exit_code}"
        else:
            reason = "dry-run 또는 명시적 skip"
        lines.append(f"- `{command}` = {status} ({reason})")
    return "\n".join(lines)


def run_cmd(args: list[str], cwd: Path, *, dry_run: bool = False) -> dict[str, Any]:
    printable = " ".join(str(arg) for arg in args)
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
    return {
        "status": "pass" if result.returncode == 0 else "fail",
        "exit_code": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "summary": first_lines(result.stderr or result.stdout),
    }


def git(cwd: Path, *args: str, dry_run: bool = False) -> dict[str, Any]:
    return run_cmd(["git", *args], cwd, dry_run=dry_run)


def git_output(cwd: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=cwd, check=False, text=True, stdout=subprocess.PIPE)
    return result.stdout.rstrip("\n") if result.returncode == 0 else ""


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


def is_main_worktree(cwd: Path) -> bool:
    root = git_root(cwd.resolve(strict=False))
    return root == DEFAULT_MAIN.resolve(strict=False)


def is_clean(path: Path) -> bool:
    return git_output(path, "status", "--porcelain", "--untracked-files=all") == ""


def branch_exists(main: Path, branch: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"refs/heads/{branch}"],
        cwd=main,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def validate_integration_branch(branch: str) -> None:
    if branch in {"main", "master"}:
        raise RuntimeError("main/master branch는 push 또는 PR head로 사용할 수 없습니다")
    if not branch.startswith("feat/") or not branch.endswith("-integration"):
        raise RuntimeError("integration branch는 feat/*-integration 형식이어야 합니다")


def current_branch(path: Path) -> str:
    return git_output(path, "branch", "--show-current")


def render_prompt(name: str, variables: dict[str, str]) -> str:
    path = Path(__file__).with_name("prompts") / name
    text = path.read_text(encoding="utf-8")
    for key, value in variables.items():
        text = text.replace("{{" + key + "}}", value)
    return text


def default_names(args: argparse.Namespace) -> dict[str, Any]:
    work_id = getattr(args, "work_id", None) or getattr(args, "slice", None)
    slug = slugify(str(work_id))
    return {
        "work_id": slug,
        "api_branch": args.api_branch or f"feat/api-{slug}",
        "web_branch": args.web_branch or f"feat/web-{slug}",
        "integration_branch": args.integration_branch or f"feat/{slug}-integration",
        "api_worktree": Path(args.api_worktree or DEFAULT_WORKTREE_PARENT / f"home-search-api-{slug}-work"),
        "web_worktree": Path(args.web_worktree or DEFAULT_WORKTREE_PARENT / f"home-search-web-{slug}-work"),
    }


def fail(message: str, code: int = 1) -> int:
    print(f"상태: Fail\n차단 사유: {message}\n다음 행동: 원인을 확인한 뒤 다시 실행하세요.")
    return code


def prepare_worktree(
    main: Path,
    path: Path,
    branch: str,
    base_branch: str,
    *,
    dry_run: bool,
) -> None:
    if dry_run:
        print(f"[DRY-RUN] prepare worktree {path} on {branch} from {base_branch}")
        return
    if path.exists():
        if not (path / ".git").exists():
            raise RuntimeError(f"worktree path exists but is not a git worktree: {path}")
        if current_branch(path) != branch:
            raise RuntimeError(f"worktree {path} is on {current_branch(path)}, expected {branch}")
        if not is_clean(path):
            raise RuntimeError(f"worktree is dirty: {path}")
        return
    if branch_exists(main, branch):
        raise RuntimeError(f"branch already exists without expected worktree: {branch}")
    result = git(main, "worktree", "add", "-b", branch, str(path), base_branch)
    if result["status"] == "fail":
        raise RuntimeError(f"git worktree add failed: {result['summary']}")


def verify_target(target: str, worktree: Path, args: argparse.Namespace, *, dry_run: bool) -> dict[str, Any]:
    key = "backend" if target == "backend" else "frontend"
    configured = target_config(args, target)["verification_commands"]
    checks: list[tuple[str, Path, list[str]]] = []
    for label in configured:
        plan = KNOWN_VERIFICATION_COMMANDS[key].get(str(label))
        if plan is None:
            raise RuntimeError(f"{target} preset has unsupported verification command: {label}")
        relative_cwd, command = plan
        checks.append((str(label), worktree / relative_cwd, command))
    results: dict[str, Any] = {}
    for label, cwd, command in checks:
        result = run_cmd(command, cwd, dry_run=dry_run)
        results[label] = {
            "status": result["status"],
            "exit_code": result["exit_code"],
            "summary": result["summary"],
        }
        if result["status"] == "fail":
            raise RuntimeError(f"{target} verification failed: {label}: {result['summary']}")
    return results


def run_codex(
    target: str,
    worktree: Path,
    branch: str,
    args: argparse.Namespace,
    names: dict[str, Any],
    *,
    dry_run: bool,
) -> dict[str, Any]:
    if args.no_codex:
        return {"status": "skipped", "exit_code": None, "summary": "--no-codex"}
    # Fixed name, overwritten per run: assumes one flow at a time (last run wins).
    output = REPORT_ROOT / f"{target}-last.md"
    config = target_config(args, target)
    prompt_name = str(config["prompt"])
    prompt = render_prompt(
        prompt_name,
        {
            "WORK_ID": names["work_id"],
            "SLICE": names["work_id"],
            "PRESET": args.preset,
            "TARGET": target,
            "BRANCH_NAME": branch,
            "ALLOWED_SCOPE": str(config["allowed_scope"]),
            "FORBIDDEN_SCOPE": str(config["forbidden_scope"]),
            "VERIFICATION_COMMANDS": "; ".join(str(command) for command in config["verification_commands"]),
            "SKILL_ROUTING": routing_text("execute", target),
        },
    )
    command = [
        args.codex_bin,
        "exec",
        "--cd",
        str(worktree),
        "--sandbox",
        "workspace-write",
        "--output-last-message",
        str(output),
        prompt,
    ]
    result = run_cmd(command, DEFAULT_MAIN, dry_run=dry_run)
    result["output_path"] = str(output)
    return result


def run_gate_review(
    target: str,
    worktree: Path,
    args: argparse.Namespace,
    names: dict[str, Any],
    verification: dict[str, Any],
    *,
    dry_run: bool,
) -> dict[str, Any]:
    # Fixed name, overwritten per run: assumes one flow at a time (last run wins).
    output = REPORT_ROOT / f"{target}-gate.md"
    prompt = render_prompt(
        "gate_review.md",
        {
            "WORK_ID": names["work_id"],
            "SLICE": names["work_id"],
            "PRESET": args.preset,
            "TARGET": target,
            "BRANCH_NAME": names["api_branch"] if target == "backend" else names["web_branch"],
            "SKILL_ROUTING": routing_text("gate", target),
            "VERIFICATION_EVIDENCE": verification_evidence_text(verification),
            "TDD_EVIDENCE_PATH": f".codex/harness/evidence/{names['work_id']}.md",
        },
    )
    command = [
        args.codex_bin,
        "exec",
        "--cd",
        str(worktree),
        "--sandbox",
        "read-only",
        "--output-last-message",
        str(output),
        prompt,
    ]
    result = run_cmd(command, DEFAULT_MAIN, dry_run=dry_run)
    if result["status"] == "fail":
        raise RuntimeError(f"{target} gate review failed: {result['summary']}")
    if not dry_run and output.exists():
        text = output.read_text(encoding="utf-8", errors="replace")
        if re.search(r"상태:\s*Fail", text):
            raise RuntimeError(f"{target} gate review returned Fail")
    result["output_path"] = str(output)
    return result


def output_text(result: dict[str, Any]) -> str:
    output = result.get("output_path")
    if not output:
        return ""
    path = Path(str(output))
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def evidence_text(results: dict[str, Any]) -> str:
    chunks: list[str] = []
    for target_result in results.values():
        if not isinstance(target_result, dict):
            continue
        for key in ("codex", "gate"):
            item = target_result.get(key)
            if isinstance(item, dict):
                text = output_text(item)
                if text:
                    chunks.append(text)
    return "\n\n".join(chunks)


def required_evidence_payload(files: list[str]) -> dict[str, Any]:
    requirements = requirements_for_changed_files(files)
    return {
        "commands": ordered_commands(requirements.commands),
        "backend_changed": requirements.backend_changed,
        "web_changed": requirements.web_changed,
        "forbidden_paths": [
            {"path": path, "reason": reason}
            for path, reason in requirements.forbidden_paths
        ],
    }


def parse_changed_files(raw: str) -> list[str]:
    return parse_git_status(raw)


def changed_files(worktree: Path) -> list[str]:
    raw = git_output(worktree, "status", "--porcelain", "--untracked-files=all")
    return parse_changed_files(raw)


def changed_files_between(base: str, branch: str) -> list[str]:
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "-z", f"{base}...{branch}"],
            cwd=DEFAULT_MAIN,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return []
    if result.returncode != 0:
        return []
    return [part.decode("utf-8", errors="replace") for part in result.stdout.split(b"\0") if part]


def expected_changed_files_for_targets(targets: list[str], args: argparse.Namespace | None = None) -> list[str]:
    expected: list[str] = []
    if "backend" in targets:
        expected.append("apps/property-data/__expected__")
    if "frontend" in targets:
        expected.append("apps/web/__expected__")
    if targets:
        expected.append(".codex/harness/worklog.toml")
    return expected


def scope_patterns(raw_scope: Any) -> list[str]:
    if isinstance(raw_scope, list):
        raw_items = [str(item) for item in raw_scope]
    else:
        raw_items = re.split(r"[,;\n]+", str(raw_scope))
    return [item.strip() for item in raw_items if item.strip()]


def path_matches_scope(path: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


def git_add_pathspec(pattern: str) -> str:
    if pattern.endswith("/**"):
        return pattern[:-3]
    return pattern


def commit_target(
    target: str,
    worktree: Path,
    branch: str,
    work_id: str,
    args: argparse.Namespace,
    *,
    dry_run: bool,
) -> str | None:
    files = changed_files(worktree) if not dry_run else []
    allowed_patterns = scope_patterns(target_config(args, target)["allowed_scope"])
    add_pathspecs = [git_add_pathspec(pattern) for pattern in allowed_patterns]
    if dry_run:
        print(f"[DRY-RUN] inspect changed files in {worktree}; allowed scope {', '.join(allowed_patterns)}")
        print(f"[DRY-RUN] git add -- {' '.join(add_pathspecs)}")
        print(f"[DRY-RUN] git commit -m 'feat({'api' if target == 'backend' else 'web'}): {work_id}'")
        return None
    if not files:
        return None
    blocked = [path for path in files if not path_matches_scope(path, allowed_patterns)]
    if blocked:
        raise RuntimeError(f"{target} branch has out-of-scope changes: {', '.join(blocked[:5])}")
    git(worktree, "add", "--", *add_pathspecs)
    scope = "api" if target == "backend" else "web"
    result = git(worktree, "commit", "-m", f"feat({scope}): {work_id}")
    if result["status"] == "fail":
        raise RuntimeError(f"{target} commit failed: {result['summary']}")
    return git_output(worktree, "rev-parse", "--short", "HEAD")


def execute_target(
    target: str,
    worktree: Path,
    branch: str,
    args: argparse.Namespace,
    names: dict[str, Any],
    *,
    dry_run: bool,
) -> dict[str, Any]:
    codex_result = run_codex(target, worktree, branch, args, names, dry_run=dry_run)
    if codex_result["status"] == "fail":
        raise RuntimeError(f"{target} codex exec failed: {codex_result['summary']}")
    verification = verify_target(target, worktree, args, dry_run=dry_run)
    gate = run_gate_review(target, worktree, args, names, verification, dry_run=dry_run)
    commit = None
    if args.commit and not args.no_commit:
        commit = commit_target(target, worktree, branch, names["work_id"], args, dry_run=dry_run)
    return {
        "status": "pass",
        "codex": {k: codex_result.get(k) for k in ("status", "exit_code", "summary", "output_path")},
        "verification": verification,
        "gate": {k: gate.get(k) for k in ("status", "exit_code", "summary", "output_path")},
        "commit": commit,
    }


def merge_branch(branch: str, *, dry_run: bool) -> dict[str, Any]:
    result = git(DEFAULT_MAIN, "merge", "--no-ff", "--no-edit", branch, dry_run=dry_run)
    if result["status"] == "fail" and not dry_run:
        git(DEFAULT_MAIN, "merge", "--abort")
    return result


def verify_integration_targets(targets: list[str], args: argparse.Namespace, *, dry_run: bool) -> dict[str, Any]:
    checks: list[tuple[str, Path, list[str]]] = []
    for target in targets:
        key = "backend" if target == "backend" else "frontend"
        configured = target_config(args, target)["verification_commands"]
        for label in configured:
            plan = KNOWN_VERIFICATION_COMMANDS[key].get(str(label))
            if plan is None:
                raise RuntimeError(f"{target} preset has unsupported verification command: {label}")
            relative_cwd, command = plan
            checks.append((str(label), DEFAULT_MAIN / relative_cwd, command))
    if not any(label == DIFF_CHECK for label, _, _ in checks):
        checks.append((DIFF_CHECK, DEFAULT_MAIN, ["git", "diff", "--check"]))

    verification: dict[str, Any] = {}
    for label, cwd, command in checks:
        result = run_cmd(command, cwd, dry_run=dry_run)
        verification[label] = {
            "status": result["status"],
            "exit_code": result["exit_code"],
            "summary": result["summary"],
        }
        if result["status"] == "fail":
            raise RuntimeError(f"integration verification failed: {label}: {result['summary']}")
    return verification


def call_integrate(args: argparse.Namespace, names: dict[str, Any], *, dry_run: bool) -> dict[str, Any]:
    targets = execution_targets(args)
    if not targets:
        return {"status": "skipped", "exit_code": None, "summary": "planning-only"}
    if dry_run:
        print("[DRY-RUN] integration preflight")
        print(f"[DRY-RUN] targets: {', '.join(targets)}")
        print(f"[DRY-RUN] integration branch: {names['integration_branch']}")
    else:
        if branch_exists(DEFAULT_MAIN, names["integration_branch"]):
            raise RuntimeError(f"integration branch already exists: {names['integration_branch']}")
        for target in targets:
            branch = branch_for_target(names, target)
            if not branch_exists(DEFAULT_MAIN, branch):
                raise RuntimeError(f"branch not found: {branch}")

    switch_base = git(DEFAULT_MAIN, "switch", args.base_branch, dry_run=dry_run)
    if switch_base["status"] == "fail":
        raise RuntimeError(f"base branch switch failed: {switch_base['summary']}")
    create_branch = git(DEFAULT_MAIN, "switch", "-c", names["integration_branch"], dry_run=dry_run)
    if create_branch["status"] == "fail":
        raise RuntimeError(f"integration branch create failed: {create_branch['summary']}")

    merged: list[str] = []
    for target in targets:
        branch = branch_for_target(names, target)
        result = merge_branch(branch, dry_run=dry_run)
        if result["status"] == "fail":
            raise RuntimeError(f"{target} merge conflict or failure: {result['summary']}")
        merged.append(target)

    verification = verify_integration_targets(targets, args, dry_run=dry_run)
    return {
        "status": "pass",
        "exit_code": 0,
        "summary": f"merged targets: {', '.join(merged)}",
        "verification": verification,
    }


def call_worklog_sync(args: argparse.Namespace, names: dict[str, Any], *, dry_run: bool) -> dict[str, Any]:
    if not execution_targets(args):
        return {"status": "skipped", "exit_code": None, "summary": "planning-only"}
    if dry_run:
        print(f"[DRY-RUN] mark worklog item done: {names['work_id']}")
    result = mark_work_item_done(WORKLOG_PATH, names["work_id"], dry_run=dry_run)
    payload = {
        "status": result.status,
        "exit_code": 0 if result.status in {"pass", "skipped"} else 1,
        "summary": result.summary,
        "old_status": result.old_status,
        "new_status": result.new_status,
        "commit": None,
    }
    if result.status == "conflict":
        raise RuntimeError(f"worklog sync conflict: {result.summary}")
    if result.status == "fail":
        raise RuntimeError(f"worklog sync failed: {result.summary}")
    if result.status != "pass" or dry_run:
        return payload
    add_result = git(DEFAULT_MAIN, "add", "--", ".codex/harness/worklog.toml")
    if add_result["status"] == "fail":
        raise RuntimeError(f"worklog sync add failed: {add_result['summary']}")
    commit_result = git(DEFAULT_MAIN, "commit", "-m", f"chore(harness): mark {names['work_id']} done")
    if commit_result["status"] == "fail":
        raise RuntimeError(f"worklog sync commit failed: {commit_result['summary']}")
    payload["commit"] = git_output(DEFAULT_MAIN, "rev-parse", "--short", "HEAD") or None
    payload["summary"] = f"{result.summary}; commit={payload['commit']}"
    pruned = prune_reports()
    if pruned:
        payload["summary"] = f"{payload['summary']}; pruned_reports={len(pruned)}"
    return payload


def worklog_item_statuses(worklog_path: Path = WORKLOG_PATH) -> dict[str, str]:
    if not worklog_path.exists():
        return {}
    try:
        states = load_worklog_states(worklog_path)
    except (OSError, ValueError):
        return {}
    return {state.id: state.status.strip().lower() for state in states}


def report_work_id(file_name: str, work_ids: Iterable[str]) -> str | None:
    best: str | None = None
    for work_id in work_ids:
        if file_name == work_id or file_name.startswith(f"{work_id}-") or file_name.startswith(f"{work_id}."):
            if best is None or len(work_id) > len(best):
                best = work_id
    return best


def prune_reports(
    *,
    root: Path = REPORT_ROOT,
    statuses: dict[str, str] | None = None,
    max_age_days: int = REPORT_MAX_AGE_DAYS,
    now: datetime | None = None,
    dry_run: bool = False,
) -> list[str]:
    """Reports are a workbench for in-flight work; merged evidence lives in the GitHub PR.

    Deletes report files whose work item is done, plus orphan files older than
    max_age_days. Files for planned/in-progress work items are always kept.
    """
    if not root.exists():
        return []
    if statuses is None:
        statuses = worklog_item_statuses()
    current = now or datetime.now(timezone.utc)
    removed: list[str] = []
    for path in sorted(root.iterdir()):
        if not path.is_file() or path.name in REPORT_KEEP_FILES:
            continue
        work_id = report_work_id(path.name, statuses)
        if work_id is not None:
            if statuses[work_id] != "done":
                continue
        else:
            try:
                mtime = datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)
            except OSError:
                continue
            if (current - mtime).days < max_age_days:
                continue
        removed.append(path.name)
        if not dry_run:
            with suppress(OSError):
                path.unlink()
    return removed


def payload_path_for(work_id: str) -> Path:
    return REPORT_ROOT / f"{slugify(work_id)}.json"


def write_payload(payload: dict[str, Any], *, dry_run: bool) -> Path:
    path = payload_path_for(str(payload.get("work_id") or payload.get("slice") or "unknown"))
    payload.setdefault("links", {})["payload_json"] = str(path)
    if dry_run:
        print(f"[DRY-RUN] write payload JSON: {path}")
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def call_report(payload: dict[str, Any], *, notion: bool, slack: bool, dry_run: bool) -> None:
    script = Path(__file__).with_name("home_report.py")
    payload_path = payload_path_for(str(payload.get("work_id") or payload.get("slice") or "unknown"))
    command = [sys.executable, str(script), "--input-json", "-", "--payload-out", str(payload_path)]
    if notion:
        command.append("--notion")
    if slack:
        command.append("--slack")
    if dry_run:
        command.append("--dry-run")
    subprocess.run(command, input=json.dumps(payload), text=True, check=False)


def load_payload(path: Path, fallback: dict[str, Any], *, dry_run: bool) -> dict[str, Any]:
    if dry_run or not path.exists():
        return fallback
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return fallback
    return payload if isinstance(payload, dict) else fallback


def pr_body_path_for(work_id: str) -> Path:
    return REPORT_ROOT / f"{slugify(work_id)}-pr-body.md"


def write_pr_body_file(payload: dict[str, Any], *, dry_run: bool) -> tuple[Path, bool]:
    if dry_run:
        handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix="-pr-body.md")
        with handle:
            handle.write(render_pr_body(payload))
        path = Path(handle.name)
        print(f"[DRY-RUN] temporary PR body: {path}")
        return path, True
    path = pr_body_path_for(str(payload.get("work_id") or payload.get("slice") or "unknown"))
    payload.setdefault("links", {})["pr_body"] = str(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_pr_body(payload), encoding="utf-8")
    return path, False


def worklog_pr_metadata(work_id: str) -> tuple[str, str] | None:
    if not WORKLOG_PATH.exists():
        return None
    try:
        with WORKLOG_PATH.open("rb") as handle:
            data = tomllib.load(handle)
    except (OSError, tomllib.TOMLDecodeError):
        return None
    requested = slugify(work_id)
    for item in data.get("items", []):
        if not isinstance(item, dict) or slugify(str(item.get("id", ""))) != requested:
            continue
        title_type = str(item.get("pr_type") or "Feat").strip()
        if title_type not in PR_TITLE_TYPES:
            title_type = "Feat"
        summary = str(item.get("pr_title_ko") or item.get("title_ko") or requested.replace("-", " ")).strip()
        return title_type, summary
    return None


def default_pr_title(work_id: str) -> str:
    metadata = worklog_pr_metadata(work_id)
    if metadata:
        title_type, summary = metadata
    else:
        title_type, summary = "Chore", f"{work_id.replace('-', ' ')} 정리"
    return f"[{title_type}] {summary}"


def pr_title(args: argparse.Namespace, names: dict[str, Any]) -> str:
    return args.pr_title or default_pr_title(names["work_id"])


def call_pr(
    args: argparse.Namespace,
    names: dict[str, Any],
    payload_path: Path,
    body_path: Path,
    *,
    dry_run: bool,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(PR_SCRIPT),
        "--branch",
        names["integration_branch"],
        "--base",
        args.base_branch,
        "--title",
        pr_title(args, names),
        "--body-file",
        str(body_path),
        "--payload-json",
        str(payload_path),
        "--draft",
    ]
    if dry_run:
        command.append("--dry-run")
    if args.notion:
        command.append("--notion")
    if args.slack:
        command.append("--slack")
    result = run_cmd(command, DEFAULT_MAIN, dry_run=False)
    if dry_run and result.get("stdout"):
        print(str(result["stdout"]).rstrip())
    if result["status"] == "fail":
        if result.get("stderr"):
            print(str(result["stderr"]).rstrip(), file=sys.stderr)
        raise RuntimeError(f"draft PR creation failed: {result['summary']}")
    return {k: result[k] for k in ("status", "exit_code", "summary")}


def push_integration_branch(names: dict[str, Any], *, dry_run: bool) -> dict[str, Any]:
    validate_integration_branch(names["integration_branch"])
    result = git(DEFAULT_MAIN, "push", "-u", "origin", names["integration_branch"], dry_run=dry_run)
    if result["status"] == "fail":
        raise RuntimeError(f"integration branch push failed: {result['summary']}")
    return {k: result[k] for k in ("status", "exit_code", "summary")}


def publish_lint_preflight(
    args: argparse.Namespace,
    names: dict[str, Any],
    payload: dict[str, Any],
    *,
    dry_run: bool,
) -> dict[str, Any]:
    changed = (
        tuple(expected_changed_files_for_targets(execution_targets(args), args))
        if dry_run
        else tuple(changed_files_between(args.base_branch, names["integration_branch"]))
    )
    if not dry_run and not changed:
        raise RuntimeError(
            "publish lint preflight failed: integration branch 변경 파일을 찾지 못했습니다; "
            "base/head branch와 diff evidence를 확인하세요."
        )
    payload_for_lint = dict(payload)
    payload_for_lint["changed_files"] = list(changed)
    if dry_run:
        payload_for_lint["changed_files_kind"] = "expected"
    result = lint_pr(
        PrInput(
            title=pr_title(args, names),
            body=render_pr_body(payload_for_lint),
            base=args.base_branch,
            head=names["integration_branch"],
            draft=True,
            changed_files=changed,
        ),
        evidence_policy="feasibility" if dry_run else "strict",
    )
    if result.ok:
        summary = (
            f"dry-run feasibility checked: {len(changed)} expected paths"
            if dry_run
            else f"changed files: {len(changed)}"
        )
        if dry_run:
            print("[DRY-RUN] publish lint preflight")
        return {"status": "pass", "exit_code": 0, "summary": summary}
    raise RuntimeError("publish lint preflight failed: " + format_grouped_errors(result.errors))


def build_payload(
    args: argparse.Namespace,
    names: dict[str, Any],
    started: str,
    results: dict[str, Any],
    integration: dict[str, Any] | None,
    status: str,
    risk: str | None = None,
    worklog_sync: dict[str, Any] | None = None,
) -> dict[str, Any]:
    verification: dict[str, Any] = {}
    targets = execution_targets(args)
    for target_key in targets:
        target_result = results.get(target_key, {})
        verification.update(target_result.get("verification", {}))
    if integration:
        verification.update(integration.get("verification", {}))
    if integration:
        verification["integration"] = {k: integration.get(k) for k in ("status", "exit_code", "summary")}
    if worklog_sync:
        verification["worklog sync"] = {k: worklog_sync.get(k) for k in ("status", "exit_code", "summary")}
    main_merge = "not suggested"
    if integration is not None:
        main_merge = f"git -C {DEFAULT_MAIN} switch main && git -C {DEFAULT_MAIN} merge --no-ff {names['integration_branch']}"
    if getattr(args, "pr", False):
        main_merge = "not suggested; review and merge through GitHub PR manually"
    push_suggestion = "git push origin <branch>"
    if getattr(args, "pr", False):
        push_suggestion = "handled by --pr after integration succeeds"
    elif getattr(args, "push", False):
        push_suggestion = f"git push -u origin {names['integration_branch']}"
    integration_head = None
    if integration is not None and not args.dry_run:
        integration_head = git_output(DEFAULT_MAIN, "rev-parse", "--short", "HEAD") or None
    integration_files = []
    changed_files_kind = "actual"
    if integration is not None and not args.dry_run:
        integration_files = changed_files_between(args.base_branch, names["integration_branch"])
    elif integration is not None and args.dry_run:
        integration_files = expected_changed_files_for_targets(targets, args)
        changed_files_kind = "expected"
    required_evidence = required_evidence_payload(integration_files)
    if targets:
        skill_routing = {
            "execute": routing_payload("execute", targets),
            "gate": routing_payload("gate", targets),
            "recover": routing_payload("recover", targets),
        }
    else:
        skill_routing = {
            "plan": routing_payload("plan", getattr(args, "targets", "planning-only")),
            "recover": routing_payload("recover", getattr(args, "targets", "planning-only")),
        }
    next_action = "integration branch를 눈으로 검토한 뒤 main merge/push 여부를 결정"
    if not targets:
        next_action = "planning-only 결과를 검토한 뒤 별도 실행 work item 여부를 결정"
        main_merge = "not suggested; planning-only"
        push_suggestion = "not suggested; planning-only"
    elif getattr(args, "pr", False):
        next_action = "GitHub draft PR diff/checks/local report를 확인한 뒤 수동 merge 결정"
    elif getattr(args, "push", False):
        next_action = "원격 integration branch를 확인한 뒤 PR 생성 여부를 결정"
    if getattr(args, "dry_run", False):
        if getattr(args, "pr", False):
            next_action = "dry-run 결과와 PR lint preflight를 확인한 뒤 실제 `--pr` 실행 여부를 결정"
        elif getattr(args, "push", False):
            next_action = "dry-run 결과와 lint preflight를 확인한 뒤 실제 `--push` 실행 여부를 결정"
        elif targets:
            next_action = "dry-run 결과를 확인한 뒤 실제 run 실행 여부를 결정"
    return {
        "work_id": names["work_id"],
        "preset": args.preset,
        "targets": getattr(args, "targets", "both"),
        "status": status,
        "started_at": started,
        "finished_at": now_iso(),
        "branches": {
            "api": names["api_branch"] if "backend" in targets else None,
            "web": names["web_branch"] if "frontend" in targets else None,
            "integration": names["integration_branch"],
        },
        "worktrees": {
            "main": str(DEFAULT_MAIN),
            "api": str(names["api_worktree"]) if "backend" in targets else None,
            "web": str(names["web_worktree"]) if "frontend" in targets else None,
        },
        "commits": {
            "api": results.get("backend", {}).get("commit"),
            "web": results.get("frontend", {}).get("commit"),
            "integration_head": integration_head,
            "worklog_sync": (worklog_sync or {}).get("commit"),
        },
        "verification": verification,
        "worklog_sync": worklog_sync or {"status": "skipped", "summary": "not run"},
        "changed_files": integration_files,
        "changed_files_kind": changed_files_kind,
        "required_evidence": required_evidence,
        "planning_mode": getattr(args, "planning_mode", "standard"),
        "lint_policy": "feasibility" if args.dry_run and (args.push or args.pr) else "strict" if (args.push or args.pr) else "not applicable",
        "skill_routing": skill_routing,
        "gate_review": f"{'/'.join(targets)} gate review completed" if results else "planning-only; not run",
        "contract_risks": [],
        "residual_risks": [] if not risk else [risk],
        "security_risks": [],
        "next_action": next_action,
        "commands": {
            "main_merge_command": main_merge,
            "push_command_suggestion": push_suggestion,
        },
        "safety": (getattr(args, "preset_config", None) or {}).get("safety", {}),
    }


def run_flow(args: argparse.Namespace) -> int:
    try:
        preset_id, preset = resolve_preset(args.work_id, args.preset)
    except (PresetError, ValueError) as exc:
        return fail(str(exc))
    args.preset = preset_id
    args.preset_config = preset
    if args.targets not in TARGET_MODES:
        return fail(f"지원하지 않는 target: {args.targets}")
    if args.planning_mode not in PLANNING_MODES:
        return fail(f"지원하지 않는 planning mode: {args.planning_mode}")
    if getattr(args, "draft_pr", False):
        args.pr = True
    if args.pr and args.no_pr:
        return fail("--pr and --no-pr cannot be used together")
    if args.parallel and args.targets != "both":
        return fail("--parallel은 --targets both에서만 사용할 수 있습니다")
    if args.targets == "planning-only" and (args.commit or args.integrate or args.push or args.pr):
        return fail("planning-only target은 commit/integration/push/PR을 실행하지 않습니다")
    if (args.pr or args.push) and args.no_integrate:
        return fail("--pr/--push requires integration; do not use --no-integrate")
    if args.pr or args.push:
        args.integrate = True
    names = default_names(args)
    started = now_iso()
    dry_run = bool(args.dry_run)
    try:
        if args.integrate or args.pr or args.push:
            validate_integration_branch(names["integration_branch"])
    except RuntimeError as exc:
        return fail(str(exc))

    if dry_run:
        print("[DRY-RUN] home_flow run")
        print(f"[DRY-RUN] preset: {args.preset} ({preset.get('description', 'no description')})")
        print(f"[DRY-RUN] targets: {args.targets}")
        print(json.dumps({k: str(v) for k, v in names.items()}, indent=2, sort_keys=True))
    elif args.targets == "planning-only":
        pass
    else:
        if not DEFAULT_MAIN.exists():
            return fail(f"main worktree not found: {DEFAULT_MAIN}")
        if not is_main_worktree(Path.cwd()):
            return fail(f"main worktree에서 실행해야 합니다: {DEFAULT_MAIN}")
        if not is_clean(DEFAULT_MAIN):
            return fail("main worktree is dirty")

    if args.targets == "planning-only":
        payload = build_payload(args, names, started, {}, None, "Pass")
        payload_path = write_payload(payload, dry_run=dry_run)
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
        print("상태: Pass")
        print(f"report: {payload_path}")
        print("다음 행동:")
        print("planning-only 결과를 검토한 뒤 별도 backend/frontend/both work item으로 실행 여부를 결정하세요.")
        return 0

    results: dict[str, Any] = {}
    integration_result: dict[str, Any] | None = None
    worklog_sync_result: dict[str, Any] | None = None
    try:
        selected_targets = execution_targets(args)
        for target in selected_targets:
            prepare_worktree(
                DEFAULT_MAIN,
                worktree_for_target(names, target),
                branch_for_target(names, target),
                args.base_branch,
                dry_run=dry_run,
            )

        if args.parallel:
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures = {
                    "backend": pool.submit(
                        execute_target,
                        "backend",
                        names["api_worktree"],
                        names["api_branch"],
                        args,
                        names,
                        dry_run=dry_run,
                    ),
                    "frontend": pool.submit(
                        execute_target,
                        "frontend",
                        names["web_worktree"],
                        names["web_branch"],
                        args,
                        names,
                        dry_run=dry_run,
                    ),
                }
                for key, future in futures.items():
                    results[key] = future.result()
        else:
            for target in selected_targets:
                results[target] = execute_target(
                    target,
                    worktree_for_target(names, target),
                    branch_for_target(names, target),
                    args,
                    names,
                    dry_run=dry_run,
                )

        if args.integrate and not args.no_integrate:
            integration_result = call_integrate(args, names, dry_run=dry_run)
            if integration_result.get("status") == "pass":
                worklog_sync_result = call_worklog_sync(args, names, dry_run=dry_run)
    except RuntimeError as exc:
        payload = build_payload(args, names, started, results, integration_result, "Fail", str(exc), worklog_sync_result)
        payload_path = write_payload(payload, dry_run=dry_run)
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
        exit_code = fail(str(exc))
        print(f"report: {payload_path}")
        return exit_code

    payload = build_payload(args, names, started, results, integration_result, "Pass", worklog_sync=worklog_sync_result)
    try:
        if args.push or args.pr:
            lint_preflight = publish_lint_preflight(args, names, payload, dry_run=dry_run)
            payload.setdefault("verification", {})["publish lint preflight"] = lint_preflight
            payload["lint_preflight"] = lint_preflight
            payload["publish_action"] = "pr" if args.pr else "push"
    except RuntimeError as exc:
        payload = build_payload(args, names, started, results, integration_result, "Fail", str(exc), worklog_sync_result)
        payload_path = write_payload(payload, dry_run=dry_run)
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
        exit_code = fail(str(exc))
        print(f"report: {payload_path}")
        return exit_code
    payload_path = write_payload(payload, dry_run=dry_run)
    report_before_pr = args.report or args.pr or args.notion or args.slack
    if report_before_pr:
        call_report(payload, notion=(args.notion and not args.pr), slack=(args.slack and not args.pr), dry_run=dry_run)
        payload = load_payload(payload_path, payload, dry_run=dry_run)
    push_result: dict[str, Any] | None = None
    pr_result: dict[str, Any] | None = None
    temp_pr_body: Path | None = None
    try:
        if args.push and not args.pr:
            push_result = push_integration_branch(names, dry_run=dry_run)
        if args.pr:
            body_path, is_temp = write_pr_body_file(payload, dry_run=dry_run)
            temp_pr_body = body_path if is_temp else None
            if not dry_run:
                write_payload(payload, dry_run=False)
            pr_result = call_pr(args, names, payload_path, body_path, dry_run=dry_run)
    except RuntimeError as exc:
        payload = build_payload(args, names, started, results, integration_result, "Fail", str(exc), worklog_sync_result)
        payload_path = write_payload(payload, dry_run=dry_run)
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
        if temp_pr_body:
            with suppress(OSError):
                temp_pr_body.unlink()
        exit_code = fail(str(exc))
        print(f"report: {payload_path}")
        return exit_code
    finally:
        if temp_pr_body:
            with suppress(OSError):
                temp_pr_body.unlink()
    print("상태: Pass")
    print(f"report: {payload_path}")
    if push_result is not None:
        print("push: dry-run" if dry_run else "push: Pass")
    if pr_result is not None:
        print("draft PR: dry-run" if dry_run else "draft PR: Pass")
    print("다음 행동:")
    if args.pr:
        print(payload["next_action"])
    elif args.push:
        print(payload["next_action"])
    elif integration_result is None:
        print("integration skipped; main merge command is not suggested.")
    else:
        print(payload["commands"]["main_merge_command"])
    if not (args.pr or args.push):
        print("push는 자동 실행하지 않습니다.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Home Search work orchestrator.")
    parser.add_argument("--self-test", action="store_true")
    subparsers = parser.add_subparsers(dest="command")

    run = subparsers.add_parser("run", help="Run a Home Search work item workflow.")
    run.add_argument("--work-id", required=True)
    run.add_argument("--slice", dest="work_id", help=argparse.SUPPRESS)
    run.add_argument("--preset", help="Preset id or alias. Defaults to work-id based resolution.")
    run.add_argument("--api-branch")
    run.add_argument("--web-branch")
    run.add_argument("--integration-branch")
    run.add_argument("--api-worktree")
    run.add_argument("--web-worktree")
    run.add_argument("--base-branch", default="main")
    run.add_argument("--targets", choices=sorted(TARGET_MODES), default="both")
    run.add_argument("--planning-mode", choices=sorted(PLANNING_MODES), default="standard")
    run.add_argument("--parallel", action="store_true")
    run.add_argument("--commit", action="store_true")
    run.add_argument("--integrate", action="store_true")
    run.add_argument("--report", action="store_true")
    run.add_argument("--push", action="store_true", help="Push the integration branch after successful integration.")
    run.add_argument("--pr", action="store_true", help="Push the integration branch and create a draft PR.")
    run.add_argument("--draft-pr", action="store_true", help="Alias for --pr; PRs are draft by default.")
    run.add_argument("--pr-title", help="Draft PR title. Defaults to the worklog PR title or work id summary.")
    run.add_argument(
        "--pr-body-from-report",
        action="store_true",
        help="Generate the PR body from the local report payload. This is the default for --pr.",
    )
    run.add_argument("--no-pr", action="store_true", help="Explicitly disable PR creation.")
    run.add_argument("--notion", action="store_true")
    run.add_argument("--slack", action="store_true")
    run.add_argument("--dry-run", action="store_true")
    run.add_argument("--no-codex", action="store_true")
    run.add_argument("--no-commit", action="store_true")
    run.add_argument("--no-integrate", action="store_true")
    run.add_argument("--codex-bin", default="codex")

    prune = subparsers.add_parser(
        "prune-reports",
        help="Delete report files for done work items and stale orphans.",
    )
    prune.add_argument("--max-age-days", type=int, default=REPORT_MAX_AGE_DAYS)
    prune.add_argument("--dry-run", action="store_true")
    return parser


def run_self_test() -> int:
    try:
        resolved, _ = resolve_preset("map-contract-hardening")
    except PresetError:
        resolved = ""
    parser = build_parser()
    pr_args = parser.parse_args(["run", "--work-id", "map-contract-hardening", "--pr", "--dry-run"])
    worklog_pr_args = parser.parse_args(["run", "--work-id", "self-test-fixture", "--pr", "--dry-run"])
    backend_args = parser.parse_args(["run", "--work-id", "self-test-fixture", "--targets", "backend", "--dry-run"])
    planning_args = parser.parse_args(["run", "--work-id", "data-architecture-checkpoint", "--targets", "planning-only"])
    pr_names = default_names(pr_args)
    pr_payload = build_payload(
        pr_args,
        pr_names,
        "2026-05-19T00:00:00+09:00",
        {},
        {"status": "pass", "exit_code": 0, "summary": "merged targets: backend, frontend", "verification": {}},
        "Pass",
    )
    body = render_pr_body(
        {
            "work_id": "self-test",
            "status": "Pass",
            "branches": {"integration": "feat/self-test-integration"},
            "verification": {"git diff --check": {"status": "pass", "exit_code": 0}},
        }
    )
    prompt = render_prompt(
        "backend_execute.md",
        {
            "SLICE": "self-test",
            "PRESET": "contract-hardening",
            "TARGET": "backend",
            "BRANCH_NAME": "feat/api-self-test",
            "ALLOWED_SCOPE": "apps/property-data/**",
            "FORBIDDEN_SCOPE": "apps/web/**",
            "VERIFICATION_COMMANDS": API_QUALITY,
            "SKILL_ROUTING": routing_text("execute", "backend"),
        },
    )
    gate_prompt = render_prompt(
        "gate_review.md",
        {
            "SLICE": "self-test",
            "PRESET": "contract-hardening",
            "TARGET": "backend",
            "BRANCH_NAME": "feat/api-self-test",
            "SKILL_ROUTING": routing_text("gate", "backend"),
            "VERIFICATION_EVIDENCE": verification_evidence_text(
                {"git diff --check": {"status": "pass", "exit_code": 0}}
            ),
            "TDD_EVIDENCE_PATH": ".codex/harness/evidence/self-test.md",
        },
    )
    invalid_branch_blocked = False
    try:
        validate_integration_branch("feat/not-integration-branch")
    except RuntimeError:
        invalid_branch_blocked = True
    global WORKLOG_PATH
    original_worklog_path = WORKLOG_PATH
    with tempfile.TemporaryDirectory() as tmp_worklog_dir:
        fixture_worklog = Path(tmp_worklog_dir) / "worklog.toml"
        fixture_worklog.write_text(
            "[[items]]\n"
            'id = "self-test-fixture"\n'
            'title_ko = "셀프테스트 픽스처"\n'
            'pr_type = "Test"\n'
            'pr_title_ko = "셀프테스트 픽스처 제목"\n'
            'status = "planned"\n'
            'targets = "backend"\n',
            encoding="utf-8",
        )
        WORKLOG_PATH = fixture_worklog
        try:
            fixture_worklog_title = pr_title(worklog_pr_args, default_names(worklog_pr_args))
        finally:
            WORKLOG_PATH = original_worklog_path
    with tempfile.TemporaryDirectory() as tmp_reports:
        tmp_root = Path(tmp_reports)
        for name in ("done-item.json", "done-item-pr-body.md", "active-item.json", "orphan.md", "orphan-old.md"):
            (tmp_root / name).write_text("x", encoding="utf-8")
        stale = datetime.now(timezone.utc).timestamp() - 90 * 24 * 3600
        os.utime(tmp_root / "orphan-old.md", (stale, stale))
        prune_statuses = {"done-item": "done", "active-item": "planned"}
        prune_dry_removed = prune_reports(root=tmp_root, statuses=prune_statuses, dry_run=True)
        prune_dry_kept = sorted(path.name for path in tmp_root.iterdir())
        prune_removed = prune_reports(root=tmp_root, statuses=prune_statuses)
        prune_remaining = sorted(path.name for path in tmp_root.iterdir())
    checks = [
        sorted(prune_dry_removed) == ["done-item-pr-body.md", "done-item.json", "orphan-old.md"],
        len(prune_dry_kept) == 5,
        sorted(prune_removed) == ["done-item-pr-body.md", "done-item.json", "orphan-old.md"],
        prune_remaining == ["active-item.json", "orphan.md"],
        report_work_id("done-item-backend-gate.md", prune_statuses) == "done-item",
        report_work_id("unrelated.md", prune_statuses) is None,
        report_work_id(payload_path_for("Self Test").name, {"self-test": "done"}) == "self-test",
        report_work_id(pr_body_path_for("Self Test").name, {"self-test": "done"}) == "self-test",
        slugify("Map Contract Hardening") == "map-contract-hardening",
        resolved == "contract-hardening",
        is_main_worktree(DEFAULT_MAIN),
        pr_args.pr is True,
        pr_args.dry_run is True,
        pr_names["integration_branch"] == "feat/map-contract-hardening-integration",
        invalid_branch_blocked,
        pr_payload["commands"]["main_merge_command"] == "not suggested; review and merge through GitHub PR manually",
        pr_payload["commands"]["push_command_suggestion"] == "handled by --pr after integration succeeds",
        pr_payload["next_action"].startswith("dry-run 결과와 PR lint preflight"),
        "llm-replan" in PLANNING_MODES,
        fixture_worklog_title == "[Test] 셀프테스트 픽스처 제목",
        pr_title(pr_args, pr_names) == "[Chore] map contract hardening 정리",
        execution_targets(backend_args) == ["backend"],
        execution_targets(planning_args) == [],
        "최초 RED:" in body,
        "feat/api-test" == default_names(
            argparse.Namespace(
                work_id="test",
                api_branch=None,
                web_branch=None,
                integration_branch=None,
                api_worktree=None,
                web_worktree=None,
            )
        )["api_branch"],
        parse_changed_files(" M apps/property-data/Foo.java\n?? apps/web/Bar.tsx\nR  old.txt -> apps/property-data/New.java")
        == ["apps/property-data/Foo.java", "apps/web/Bar.tsx", "apps/property-data/New.java"],
        expected_changed_files_for_targets(["backend"])
        == ["apps/property-data/__expected__", ".codex/harness/worklog.toml"],
        "Skill contract:" in prompt,
        "home-search-harness [orchestrator]" in prompt,
        "$tdd [primary]" in prompt,
        "$backend-api [support]" in prompt,
        "$api-contract [checkpoint]" in prompt,
        "{{SKILL_ROUTING}}" not in prompt,
        "Explicit `--pr` may push only the generated `feat/*-integration` branch." in gate_prompt,
        "- `git diff --check` = pass (harness 실행 완료, exit=0)" in gate_prompt,
        ".codex/harness/evidence/self-test.md" in gate_prompt,
        "{{VERIFICATION_EVIDENCE}}" not in gate_prompt,
        "{{TDD_EVIDENCE_PATH}}" not in gate_prompt,
    ]
    if all(checks):
        print("self-test passed: home_flow")
        return 0
    print("self-test failed: home_flow", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    if args.command == "run":
        return run_flow(args)
    if args.command == "prune-reports":
        removed = prune_reports(max_age_days=args.max_age_days, dry_run=args.dry_run)
        label = "[DRY-RUN] " if args.dry_run else ""
        for name in removed:
            print(f"{label}정리 대상 리포트: {name}")
        print(f"{label}정리한 리포트: {len(removed)}건")
        return 0
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

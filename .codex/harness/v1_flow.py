#!/usr/bin/env python3
"""Run Home Search V1 slice automation from the main worktree."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tomllib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MAIN = Path("/Users/gwongwangjae/home-search")
DEFAULT_WORKTREE_PARENT = Path("/Users/gwongwangjae")
PRESET_DIR = Path(__file__).with_name("presets")
REPORT_ROOT = DEFAULT_MAIN / ".codex" / "harness" / "reports"
DEFAULT_TARGETS = {
    "backend": {
        "prompt": "backend_execute.md",
        "allowed_scope": "apps/api/**",
        "forbidden_scope": "apps/web/**",
        "verification_commands": ["cd apps/api && ./gradlew test"],
    },
    "frontend": {
        "prompt": "frontend_execute.md",
        "allowed_scope": "apps/web/**",
        "forbidden_scope": "apps/api/**",
        "verification_commands": [
            "cd apps/web && npm run test",
            "cd apps/web && npm run build",
        ],
    },
}
KNOWN_VERIFICATION_COMMANDS = {
    "backend": {
        "cd apps/api && ./gradlew test": ("apps/api", ["./gradlew", "test"]),
    },
    "frontend": {
        "cd apps/web && npm run test": ("apps/web", ["npm", "run", "test"]),
        "cd apps/web && npm run build": ("apps/web", ["npm", "run", "build"]),
    },
}
FORBIDDEN_SAFETY_FLAGS = {
    "allow_main_merge": "main merge",
    "allow_push": "push",
    "allow_open_api_call": "Open API 호출",
    "allow_db_migration_run": "DB migration 실행",
}


class PresetError(ValueError):
    """Raised when a slice cannot be mapped to exactly one safe preset."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise ValueError("--slice must contain at least one alphanumeric character")
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


def resolve_preset(slice_name: str, explicit: str | None = None) -> tuple[str, dict[str, Any]]:
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

    slice_slug = slugify(slice_name)
    exact_matches = [
        (preset_id, preset)
        for preset_id, preset in presets.items()
        if slice_slug in _preset_exact_names(preset)
    ]
    if len(exact_matches) == 1:
        return exact_matches[0]
    if len(exact_matches) > 1:
        raise PresetError(f"slice 이름이 여러 preset alias와 일치합니다: {slice_slug}")

    pattern_matches = []
    for preset_id, preset in presets.items():
        patterns = [slugify(str(pattern)) for pattern in preset.get("slice_patterns", [])]
        if any(pattern and pattern in slice_slug for pattern in patterns):
            pattern_matches.append((preset_id, preset))
    if len(pattern_matches) == 1:
        return pattern_matches[0]
    if len(pattern_matches) > 1:
        matched = ", ".join(preset_id for preset_id, _ in pattern_matches)
        raise PresetError(f"slice 이름이 여러 preset pattern과 일치합니다: {matched}")
    raise PresetError(f"preset을 결정할 수 없습니다: {slice_slug}. 가능: {describe_presets(presets)}")


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


def current_branch(path: Path) -> str:
    return git_output(path, "branch", "--show-current")


def render_prompt(name: str, variables: dict[str, str]) -> str:
    path = Path(__file__).with_name("prompts") / name
    text = path.read_text(encoding="utf-8")
    for key, value in variables.items():
        text = text.replace("{{" + key + "}}", value)
    return text


def default_names(args: argparse.Namespace) -> dict[str, Any]:
    slug = slugify(args.slice)
    return {
        "slice": slug,
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
    output = DEFAULT_MAIN / ".codex" / "harness" / "reports" / f"{names['slice']}-{target}-last.md"
    config = target_config(args, target)
    prompt_name = str(config["prompt"])
    prompt = render_prompt(
        prompt_name,
        {
            "SLICE": names["slice"],
            "PRESET": args.preset,
            "TARGET": target,
            "BRANCH_NAME": branch,
            "ALLOWED_SCOPE": str(config["allowed_scope"]),
            "FORBIDDEN_SCOPE": str(config["forbidden_scope"]),
            "VERIFICATION_COMMANDS": "; ".join(str(command) for command in config["verification_commands"]),
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
    return run_cmd(command, DEFAULT_MAIN, dry_run=dry_run)


def run_gate_review(
    target: str,
    worktree: Path,
    args: argparse.Namespace,
    names: dict[str, Any],
    *,
    dry_run: bool,
) -> dict[str, Any]:
    output = DEFAULT_MAIN / ".codex" / "harness" / "reports" / f"{names['slice']}-{target}-gate.md"
    prompt = render_prompt(
        "gate_review.md",
        {
            "SLICE": names["slice"],
            "PRESET": args.preset,
            "TARGET": target,
            "BRANCH_NAME": names["api_branch"] if target == "backend" else names["web_branch"],
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
    return result


def changed_files(worktree: Path) -> list[str]:
    raw = git_output(worktree, "status", "--porcelain", "--untracked-files=all")
    files: list[str] = []
    for line in raw.splitlines():
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        files.append(path)
    return files


def commit_target(target: str, worktree: Path, branch: str, slice_name: str, *, dry_run: bool) -> str | None:
    files = changed_files(worktree) if not dry_run else []
    allowed = "apps/api/" if target == "backend" else "apps/web/"
    if dry_run:
        print(f"[DRY-RUN] inspect changed files in {worktree}; allowed prefix {allowed}")
        print(f"[DRY-RUN] git add -- {allowed.rstrip('/')}")
        print(f"[DRY-RUN] git commit -m 'feat({'api' if target == 'backend' else 'web'}): {slice_name}'")
        return None
    if not files:
        return None
    blocked = [path for path in files if not path.startswith(allowed)]
    if blocked:
        raise RuntimeError(f"{target} branch has out-of-scope changes: {', '.join(blocked[:5])}")
    git(worktree, "add", "--", allowed.rstrip("/"))
    scope = "api" if target == "backend" else "web"
    result = git(worktree, "commit", "-m", f"feat({scope}): {slice_name}")
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
    gate = run_gate_review(target, worktree, args, names, dry_run=dry_run)
    commit = None
    if args.commit and not args.no_commit:
        commit = commit_target(target, worktree, branch, names["slice"], dry_run=dry_run)
    return {
        "status": "pass",
        "codex": {k: codex_result[k] for k in ("status", "exit_code", "summary")},
        "verification": verification,
        "gate": {k: gate[k] for k in ("status", "exit_code", "summary")},
        "commit": commit,
    }


def call_integrate(args: argparse.Namespace, names: dict[str, Any], *, dry_run: bool) -> dict[str, Any]:
    script = Path(__file__).with_name("v1_integrate.py")
    command = [
        sys.executable,
        str(script),
        "--api-branch",
        names["api_branch"],
        "--web-branch",
        names["web_branch"],
        "--integration-branch",
        names["integration_branch"],
        "--base-branch",
        args.base_branch,
    ]
    if dry_run:
        command.append("--dry-run")
    result = run_cmd(command, DEFAULT_MAIN, dry_run=False)
    if result["status"] == "fail":
        raise RuntimeError(f"integration failed: {result['summary']}")
    return {k: result[k] for k in ("status", "exit_code", "summary")}


def payload_path_for(slice_name: str) -> Path:
    return REPORT_ROOT / f"{slugify(slice_name)}.json"


def write_payload(payload: dict[str, Any], *, dry_run: bool) -> Path:
    path = payload_path_for(str(payload.get("slice") or "unknown"))
    payload.setdefault("links", {})["payload_json"] = str(path)
    if dry_run:
        print(f"[DRY-RUN] write payload JSON: {path}")
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def call_report(payload: dict[str, Any], *, notion: bool, slack: bool, dry_run: bool) -> None:
    script = Path(__file__).with_name("v1_report.py")
    payload_path = payload_path_for(str(payload.get("slice") or "unknown"))
    command = [sys.executable, str(script), "--input-json", "-", "--payload-out", str(payload_path)]
    if notion:
        command.append("--notion")
    if slack:
        command.append("--slack")
    if dry_run:
        command.append("--dry-run")
    subprocess.run(command, input=json.dumps(payload), text=True, check=False)


def build_payload(
    args: argparse.Namespace,
    names: dict[str, Any],
    started: str,
    results: dict[str, Any],
    integration: dict[str, Any] | None,
    status: str,
    risk: str | None = None,
) -> dict[str, Any]:
    verification: dict[str, Any] = {}
    for target_key in ("backend", "frontend"):
        target_result = results.get(target_key, {})
        verification.update(target_result.get("verification", {}))
    if integration:
        verification["v1_integrate.py"] = integration
    main_merge = "not suggested"
    if integration is not None:
        main_merge = f"git -C {DEFAULT_MAIN} switch main && git -C {DEFAULT_MAIN} merge --no-ff {names['integration_branch']}"
    integration_head = None
    if integration is not None and not args.dry_run:
        integration_head = git_output(DEFAULT_MAIN, "rev-parse", "--short", "HEAD") or None
    return {
        "slice": names["slice"],
        "preset": args.preset,
        "status": status,
        "started_at": started,
        "finished_at": now_iso(),
        "branches": {
            "api": names["api_branch"],
            "web": names["web_branch"],
            "integration": names["integration_branch"],
        },
        "worktrees": {
            "main": str(DEFAULT_MAIN),
            "api": str(names["api_worktree"]),
            "web": str(names["web_worktree"]),
        },
        "commits": {
            "api": results.get("backend", {}).get("commit"),
            "web": results.get("frontend", {}).get("commit"),
            "integration_head": integration_head,
        },
        "verification": verification,
        "gate_review": "backend/web gate review completed" if results else "not run",
        "contract_risks": [],
        "residual_risks": [] if not risk else [risk],
        "next_action": "integration branch를 눈으로 검토한 뒤 main merge/push 여부를 결정",
        "commands": {
            "main_merge_command": main_merge,
            "push_command_suggestion": "git push origin <branch>",
        },
        "safety": (getattr(args, "preset_config", None) or {}).get("safety", {}),
    }


def run_flow(args: argparse.Namespace) -> int:
    try:
        preset_id, preset = resolve_preset(args.slice, args.preset)
    except (PresetError, ValueError) as exc:
        return fail(str(exc))
    args.preset = preset_id
    args.preset_config = preset
    names = default_names(args)
    started = now_iso()
    dry_run = bool(args.dry_run)

    if dry_run:
        print("[DRY-RUN] v1_flow run")
        print(f"[DRY-RUN] preset: {args.preset} ({preset.get('description', 'no description')})")
        print(json.dumps({k: str(v) for k, v in names.items()}, indent=2, sort_keys=True))
    else:
        if not DEFAULT_MAIN.exists():
            return fail(f"main worktree not found: {DEFAULT_MAIN}")
        if not is_main_worktree(Path.cwd()):
            return fail(f"main worktree에서 실행해야 합니다: {DEFAULT_MAIN}")
        if not is_clean(DEFAULT_MAIN):
            return fail("main worktree is dirty")

    results: dict[str, Any] = {}
    integration_result: dict[str, Any] | None = None
    try:
        prepare_worktree(DEFAULT_MAIN, names["api_worktree"], names["api_branch"], args.base_branch, dry_run=dry_run)
        prepare_worktree(DEFAULT_MAIN, names["web_worktree"], names["web_branch"], args.base_branch, dry_run=dry_run)

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
            results["backend"] = execute_target(
                "backend",
                names["api_worktree"],
                names["api_branch"],
                args,
                names,
                dry_run=dry_run,
            )
            results["frontend"] = execute_target(
                "frontend",
                names["web_worktree"],
                names["web_branch"],
                args,
                names,
                dry_run=dry_run,
            )

        if args.integrate and not args.no_integrate:
            integration_result = call_integrate(args, names, dry_run=dry_run)
    except RuntimeError as exc:
        payload = build_payload(args, names, started, results, integration_result, "Fail", str(exc))
        payload_path = write_payload(payload, dry_run=dry_run)
        if args.report or args.notion or args.slack:
            call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
        exit_code = fail(str(exc))
        print(f"report: {payload_path}")
        return exit_code

    payload = build_payload(args, names, started, results, integration_result, "Pass")
    payload_path = write_payload(payload, dry_run=dry_run)
    if args.report or args.notion or args.slack:
        call_report(payload, notion=args.notion, slack=args.slack, dry_run=dry_run)
    print("상태: Pass")
    print(f"report: {payload_path}")
    print("다음 행동:")
    if integration_result is None:
        print("integration skipped; main merge command is not suggested.")
    else:
        print(payload["commands"]["main_merge_command"])
    print("push는 자동 실행하지 않습니다.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Home Search V1 slice orchestrator.")
    parser.add_argument("--self-test", action="store_true")
    subparsers = parser.add_subparsers(dest="command")

    run = subparsers.add_parser("run", help="Run a V1 slice workflow.")
    run.add_argument("--slice", required=True)
    run.add_argument("--preset", help="Preset id or alias. Defaults to slice-based resolution.")
    run.add_argument("--api-branch")
    run.add_argument("--web-branch")
    run.add_argument("--integration-branch")
    run.add_argument("--api-worktree")
    run.add_argument("--web-worktree")
    run.add_argument("--base-branch", default="main")
    run.add_argument("--parallel", action="store_true")
    run.add_argument("--commit", action="store_true")
    run.add_argument("--integrate", action="store_true")
    run.add_argument("--report", action="store_true")
    run.add_argument("--notion", action="store_true")
    run.add_argument("--slack", action="store_true")
    run.add_argument("--dry-run", action="store_true")
    run.add_argument("--no-codex", action="store_true")
    run.add_argument("--no-commit", action="store_true")
    run.add_argument("--no-integrate", action="store_true")
    run.add_argument("--codex-bin", default="codex")
    return parser


def run_self_test() -> int:
    try:
        resolved, _ = resolve_preset("map-contract-hardening")
    except PresetError:
        resolved = ""
    checks = [
        slugify("Map Contract Hardening") == "map-contract-hardening",
        resolved == "contract-hardening",
        is_main_worktree(DEFAULT_MAIN),
        "feat/api-test" == default_names(
            argparse.Namespace(
                slice="test",
                api_branch=None,
                web_branch=None,
                integration_branch=None,
                api_worktree=None,
                web_worktree=None,
            )
        )["api_branch"],
    ]
    if all(checks):
        print("self-test passed: v1_flow")
        return 0
    print("self-test failed: v1_flow", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    if args.command == "run":
        return run_flow(args)
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

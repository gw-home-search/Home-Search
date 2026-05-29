#!/usr/bin/env python3
"""Minimal Home Search PreToolUse guard.

This hook blocks only deterministic hazards. It does not review code, run
tests, or replace Home Search skills/subagents.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any


FALLBACK_REPO_ROOT = Path("/Users/gwongwangjae/home-search")

PROTECTED_MUTATION_PREFIXES = (
    "apps/api/AGENTS.md",
    "apps/web/AGENTS.md",
    "build/",
    "dist/",
    "infra/",
    "scripts/",
)

PROTECTED_MUTATION_EXACT = {
    "AGENTS.md",
    "README.md",
    "package-lock.json",
}

BUILD_OUTPUT_PARTS = {
    ".gradle",
    ".next",
    ".vite",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "target",
}

READ_ONLY_COMMANDS = {
    "awk",
    "cat",
    "date",
    "find",
    "grep",
    "head",
    "jq",
    "ls",
    "nl",
    "pwd",
    "rg",
    "sed",
    "sort",
    "tail",
    "wc",
    "which",
}

READ_ONLY_GIT_SUBCOMMANDS = {
    "branch",
    "diff",
    "grep",
    "log",
    "ls-files",
    "rev-parse",
    "show",
    "status",
}

READ_ONLY_PYTHON_SCRIPTS = {
    "scripts/" + "check-test-display-names.py",
}

MUTATION_COMMANDS = {
    "chmod",
    "chown",
    "cp",
    "install",
    "ln",
    "mkdir",
    "mv",
    "perl",
    "python",
    "python3",
    "rm",
    "rmdir",
    "ruby",
    "sed",
    "tee",
    "touch",
    "truncate",
}

SECRET_PATH_RE = re.compile(
    r"(^|/)(\.env(?:\..*)?|[^/\s]*(?:secret|credential|token)[^/\s]*|[^/\s]*\.(?:pem|key))$",
    re.IGNORECASE,
)
DIRECT_PR_CREATE_RE = re.compile(r"(^|\s)gh\s+pr\s+create(\s|$)", re.IGNORECASE)


def load_payload() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def deny(reason: str) -> None:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": reason,
                }
            },
            ensure_ascii=False,
        )
    )
    raise SystemExit(0)


def as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, sort_keys=True)


def tool_input(payload: dict[str, Any]) -> dict[str, Any]:
    candidate = payload.get("tool_input") or payload.get("toolInput") or payload.get("input")
    return candidate if isinstance(candidate, dict) else {}


def collect_values(value: Any, keys: set[str]) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in keys and item is not None:
                found.append(as_text(item))
            found.extend(collect_values(item, keys))
    elif isinstance(value, list):
        for item in value:
            found.extend(collect_values(item, keys))
    return found


def text_from_content(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(part for part in (text_from_content(item) for item in value) if part)
    if isinstance(value, dict):
        for key in ("text", "content", "message", "value"):
            text = text_from_content(value.get(key))
            if text:
                return text
    return ""


def message_role(value: dict[str, Any]) -> str:
    role = value.get("role") or value.get("type")
    if isinstance(role, str):
        return role.lower()
    message = value.get("message")
    if isinstance(message, dict):
        role = message.get("role") or message.get("type")
        if isinstance(role, str):
            return role.lower()
    return ""


def message_text(value: dict[str, Any]) -> str:
    message = value.get("message")
    if isinstance(message, dict):
        text = text_from_content(message)
        if text:
            return text
    return text_from_content(value)


def command_from_payload(payload: dict[str, Any]) -> str:
    data = tool_input(payload)
    for key in ("command", "cmd", "script"):
        if isinstance(data.get(key), str):
            return data[key]
    commands = collect_values(data, {"command", "cmd"})
    return commands[0] if commands else ""


def shell_words(command: str) -> list[str]:
    try:
        return shlex.split(command, posix=True)
    except ValueError:
        return command.split()


def unwrap_shell(command: str) -> list[str]:
    commands = [command]
    words = shell_words(command)
    if len(words) >= 3 and Path(words[0]).name in {"bash", "sh", "zsh"}:
        for idx, word in enumerate(words[:-1]):
            if word in {"-c", "-lc"}:
                commands.append(words[idx + 1])
                break
    return commands


def command_name(words: list[str]) -> str:
    if not words:
        return ""
    return Path(words[0]).name


def first_script_arg(words: list[str]) -> str:
    for word in words[1:]:
        if word.startswith("-"):
            continue
        return word.removeprefix("./")
    return ""


def is_read_only_command(command: str) -> bool:
    words = shell_words(command)
    if not words:
        return True
    name = command_name(words)
    if name == "git":
        return len(words) > 1 and words[1] in READ_ONLY_GIT_SUBCOMMANDS
    if name == "sed" and any(word.startswith("-i") for word in words[1:]):
        return False
    return name in READ_ONLY_COMMANDS


def git_root(cwd: Path) -> Path | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(cwd), "rev-parse", "--show-toplevel"],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return None
    if result.returncode != 0:
        return None
    root = result.stdout.strip()
    return Path(root).resolve(strict=False) if root else None


def payload_cwd(payload: dict[str, Any]) -> Path:
    raw = payload.get("cwd")
    if isinstance(raw, str) and raw:
        return Path(raw)
    return Path(os.getcwd())


def repo_root_from_payload(payload: dict[str, Any]) -> Path:
    cwd = payload_cwd(payload)
    for candidate in (cwd, Path(os.getcwd())):
        root = git_root(candidate)
        if root is not None:
            return root
    return FALLBACK_REPO_ROOT


def current_branch(repo_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo_root), "branch", "--show-current"],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return ""
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def tokens(value: str) -> set[str]:
    return {token for token in re.split(r"[^a-z0-9]+", value.lower()) if token}


def infer_worktree_scope(repo_root: Path, cwd: Path, branch_name: str) -> str:
    repo_tokens = tokens(repo_root.name) | tokens(branch_name)
    integration_signal = bool(repo_tokens & {"integration", "integrate", "integrated"})
    backend_repo_signal = bool(repo_tokens & {"api", "backend", "server"})
    frontend_repo_signal = bool(repo_tokens & {"web", "frontend", "client"})

    if integration_signal or (backend_repo_signal and frontend_repo_signal):
        return "integration"
    if backend_repo_signal:
        return "backend"
    if frontend_repo_signal:
        return "frontend"

    try:
        rel = cwd.resolve(strict=False).relative_to(repo_root.resolve(strict=False))
    except ValueError:
        return "unknown"
    first_parts = rel.parts[:2]
    if first_parts == ("apps", "api"):
        return "backend"
    if first_parts == ("apps", "web"):
        return "frontend"
    if repo_root.name == "home-search":
        return "root"
    return "unknown"


def looks_like_path(token: str) -> bool:
    return (
        "/" in token
        or token.startswith(".")
        or token.endswith((".md", ".toml", ".json", ".py", ".java", ".ts", ".tsx", ".js", ".jsx"))
    )


def normalize_path(raw_path: str, cwd: Path, repo_root: Path) -> str | None:
    cleaned = raw_path.strip("'\"")
    if not cleaned or cleaned.startswith("-"):
        return None
    if "://" in cleaned:
        return None
    path = Path(cleaned)
    if not path.is_absolute():
        path = cwd / path
    try:
        resolved = path.resolve(strict=False)
    except OSError:
        return None
    try:
        return resolved.relative_to(repo_root.resolve(strict=False)).as_posix()
    except ValueError:
        return resolved.as_posix()


def paths_from_command(command: str, cwd: Path, repo_root: Path) -> set[str]:
    paths: set[str] = set()
    for cmd in unwrap_shell(command):
        for token in shell_words(cmd):
            if looks_like_path(token):
                normalized = normalize_path(token, cwd, repo_root)
                if normalized:
                    paths.add(normalized)
    return paths


def paths_from_patch_text(text: str, cwd: Path, repo_root: Path) -> set[str]:
    paths: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"\*\*\* (?:Add|Update|Delete) File: (.+)$", line.strip())
        if not match:
            continue
        normalized = normalize_path(match.group(1), cwd, repo_root)
        if normalized:
            paths.add(normalized)
    return paths


def paths_from_payload(payload: dict[str, Any], cwd: Path, repo_root: Path) -> set[str]:
    paths = set()
    data = tool_input(payload)
    raw_tool_input = payload.get("tool_input") or payload.get("toolInput") or payload.get("input")
    for raw_path in collect_values(
        data,
        {"file", "file_path", "path", "target", "target_path", "source", "destination"},
    ):
        normalized = normalize_path(raw_path, cwd, repo_root)
        if normalized:
            paths.add(normalized)
    command = command_from_payload(payload)
    if command:
        paths.update(paths_from_command(command, cwd, repo_root))
    if isinstance(raw_tool_input, str):
        paths.update(paths_from_patch_text(raw_tool_input, cwd, repo_root))
    paths.update(paths_from_patch_text(as_text(payload), cwd, repo_root))
    return paths


def is_secret_path(path: str) -> bool:
    return bool(SECRET_PATH_RE.search(path))


def is_build_output(path: str) -> bool:
    return any(part in BUILD_OUTPUT_PARTS for part in Path(path).parts)


def is_protected_mutation_path(path: str) -> bool:
    if path in PROTECTED_MUTATION_EXACT:
        return True
    if any(path.startswith(prefix) for prefix in PROTECTED_MUTATION_PREFIXES):
        return True
    if path.startswith("/Users/gwongwangjae/IdeaProjects/home-server/"):
        return True
    if path.startswith("/Users/gwongwangjae/frontend/home-client/"):
        return True
    if path.startswith("/Users/gwongwangjae/saved-ai-exam/"):
        return True
    return is_build_output(path)


def latest_user_message_from_transcript(path: Path) -> str:
    try:
        raw = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""

    user_messages: list[str] = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and message_role(value) == "user":
            text = message_text(value)
            if text:
                user_messages.append(text)
    if user_messages:
        return user_messages[-1]

    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return ""
    if isinstance(value, list):
        for item in reversed(value):
            if isinstance(item, dict) and message_role(item) == "user":
                return message_text(item)
    if isinstance(value, dict) and message_role(value) == "user":
        return message_text(value)
    return ""


def current_user_text(payload: dict[str, Any]) -> str:
    parts: list[str] = []
    for key in ("last_user_message", "lastUserMessage", "user_message", "userMessage", "prompt"):
        value = payload.get(key)
        if isinstance(value, str):
            parts.append(value)
    transcript = payload.get("transcript_path") or payload.get("transcriptPath")
    if isinstance(transcript, str):
        transcript_path = Path(transcript)
        if transcript_path.exists() and transcript_path.is_file():
            text = latest_user_message_from_transcript(transcript_path)
            if text:
                parts.append(text)
    return "\n".join(parts)


def has_protected_write_approval(text: str, protected_paths: list[str]) -> bool:
    if not text:
        return False
    has_request = "보호 경로 변경 요청:" in text or "Protected path 변경 요청:" in text
    has_confirmation = bool(
        re.search(r"(보호 경로 변경 승인|Protected path 변경 승인|사용자 승인)\s*:\s*(확인|승인|approved)", text, re.IGNORECASE)
    )
    has_basis = "보호 경로 변경 기준: current task approval only" in text
    has_targets = all(path in text for path in protected_paths)
    return has_request and has_confirmation and has_basis and has_targets


def is_external_reference_path(path: str) -> bool:
    return (
        path.startswith("/Users/gwongwangjae/IdeaProjects/home-server/")
        or path.startswith("/Users/gwongwangjae/frontend/home-client/")
        or path.startswith("/Users/gwongwangjae/saved-ai-exam/")
    )


def command_is_mutation(command: str) -> bool:
    if not command:
        return False
    if re.search(r"(^|[;&|]\s*)cat\b[^;&|]*>", command):
        return True
    for cmd in unwrap_shell(command):
        words = shell_words(cmd)
        name = command_name(words)
        if name in MUTATION_COMMANDS:
            if name in {"python", "python3"} and first_script_arg(words) in READ_ONLY_PYTHON_SCRIPTS:
                continue
            if name == "sed" and not any(word.startswith("-i") for word in words[1:]):
                continue
            return True
        if name == "git" and len(words) > 1 and words[1] not in READ_ONLY_GIT_SUBCOMMANDS:
            return True
    return False


def check_dangerous_command(command: str) -> None:
    commands = unwrap_shell(command)
    for cmd in commands:
        words = shell_words(cmd)
        lowered = " ".join(words).lower()
        if any(Path(word).name == "sudo" for word in words):
            deny("sudo 차단: Home Search hook은 privileged command를 허용하지 않습니다.")
        if re.search(r"(^|\s)git\s+reset\s+--hard(\s|$)", lowered):
            deny("git reset --hard 차단: destructive history/worktree reset은 허용하지 않습니다.")
        if re.search(r"(^|\s)git\s+clean\s+-[a-z]*f[a-z]*d[a-z]*(\s|$)", lowered):
            deny("git clean -fd/-df 차단: destructive untracked-file cleanup은 허용하지 않습니다.")
        for idx, word in enumerate(words):
            if Path(word).name != "rm":
                continue
            flags = "".join(w[1:] for w in words[idx + 1 :] if w.startswith("-"))
            if "r" in flags and "f" in flags:
                deny("rm -rf 차단: destructive recursive deletion은 허용하지 않습니다.")


def check_pr_publish_command(command: str) -> None:
    for cmd in unwrap_shell(command):
        if DIRECT_PR_CREATE_RE.search(cmd):
            deny(
                "PR 생성 차단: raw `gh pr create`는 PR lint를 우회할 수 있습니다. "
                "`.codex/harness/home run ... --pr` 또는 "
                "`python3 .codex/harness/home_pr.py ...`를 사용하세요."
            )


def check_payload(
    payload: dict[str, Any],
    *,
    repo_root: Path | None = None,
    branch_name: str | None = None,
) -> None:
    cwd = payload_cwd(payload)
    root = repo_root or repo_root_from_payload(payload)
    branch = current_branch(root) if branch_name is None else branch_name
    data = tool_input(payload)
    tool_name = as_text(payload.get("tool_name") or payload.get("toolName"))
    command = command_from_payload(payload)
    paths = paths_from_payload(payload, cwd, root)

    if command:
        check_dangerous_command(command)
        check_pr_publish_command(command)

    for path in paths:
        if is_secret_path(path):
            deny(f"secrets/env 접근 차단: {path}")

    is_mutation = command_is_mutation(command)
    if tool_name and re.search(r"apply_patch|write|edit|fs\.write", tool_name, re.IGNORECASE):
        is_mutation = True
    raw_tool_input = payload.get("tool_input") or payload.get("toolInput") or payload.get("input")
    if isinstance(raw_tool_input, str) and paths_from_patch_text(raw_tool_input, cwd, root):
        is_mutation = True
    if paths_from_patch_text(as_text(payload), cwd, root):
        is_mutation = True
    if data and not command and paths:
        is_mutation = True

    if not is_mutation:
        return

    approval_text = current_user_text(payload)

    for path in paths:
        if is_external_reference_path(path):
            deny(f"read-only reference 변경 차단: {path}")
        if is_build_output(path):
            deny(f"build output 변경 차단: {path}")

    protected_paths = sorted(
        path for path in paths
        if path == "docs/API_CONTRACT.md" or is_protected_mutation_path(path)
    )
    if protected_paths and not has_protected_write_approval(approval_text, protected_paths):
        deny(f"protected path 변경 차단: {', '.join(protected_paths)}")

    scope = infer_worktree_scope(root, cwd, branch)
    if scope == "backend" and any(path.startswith("apps/web/") for path in paths):
        deny("backend worktree에서 apps/web/** 변경 차단.")
    if scope == "frontend" and any(path.startswith("apps/api/") for path in paths):
        deny("frontend worktree에서 apps/api/** 변경 차단.")


def denied_output(
    payload: dict[str, Any],
    *,
    repo_root: Path | None = None,
    branch_name: str | None = None,
) -> str:
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        try:
            check_payload(payload, repo_root=repo_root, branch_name=branch_name)
        except SystemExit:
            return output.getvalue()
    return ""


def patch_payload(cwd: Path, path: str) -> dict[str, Any]:
    return {
        "cwd": str(cwd),
        "tool_name": "apply_patch",
        "tool_input": f"*** Begin Patch\n*** Update File: {path}\n@@\n-old\n+new\n*** End Patch\n",
    }


def run_self_test() -> int:
    backend_root = Path("/tmp/home-search-api-work")
    frontend_root = Path("/tmp/home-search-web-work")

    tests = [
        (
            "dangerous rm -rf is denied",
            lambda: "rm -rf 차단" in denied_output(
                {"cwd": str(FALLBACK_REPO_ROOT), "tool_input": {"cmd": "rm -rf apps/api/build"}},
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/root",
            ),
        ),
        (
            "read-only Python validator is allowed",
            lambda: not denied_output(
                {
                    "cwd": str(FALLBACK_REPO_ROOT),
                    "tool_input": {"cmd": "python3 " + "scripts/" + "check-test-display-names.py"},
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/root",
            ),
        ),
        (
            "backend worktree denies apps/web mutation",
            lambda: "apps/web/**" in denied_output(
                patch_payload(backend_root, "apps/web/src/App.tsx"),
                repo_root=backend_root,
                branch_name="feat/api-region-marker-slice",
            ),
        ),
        (
            "frontend worktree denies apps/api mutation",
            lambda: "apps/api/**" in denied_output(
                patch_payload(frontend_root, "apps/api/src/main/java/App.java"),
                repo_root=frontend_root,
                branch_name="feat/web-region-marker-slice",
            ),
        ),
        (
            "integration scope allows cross-app mutation",
            lambda: not denied_output(
                patch_payload(Path("/tmp/home-search-integration-work"), "apps/api/src/main/java/App.java"),
                repo_root=Path("/tmp/home-search-integration-work"),
                branch_name="feat/region-marker-integration",
            ),
        ),
        (
            "apps/web package lock is not globally protected",
            lambda: not is_protected_mutation_path("apps/web/package-lock.json"),
        ),
        (
            "protected mutation without approval is denied",
            lambda: "protected path 변경 차단" in denied_output(
                patch_payload(FALLBACK_REPO_ROOT, "docs/API_CONTRACT.md"),
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/protected-guard",
            ),
        ),
        (
            "stale protected transcript text is not approval",
            lambda: "protected path 변경 차단" in denied_output(
                {
                    **patch_payload(FALLBACK_REPO_ROOT, "docs/API_CONTRACT.md"),
                    "last_assistant_message": "이전 출력: protected path 변경 차단. User said Implement the plan.",
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/protected-guard",
            ),
        ),
        (
            "assistant-only protected approval is denied",
            lambda: "protected path 변경 차단" in denied_output(
                {
                    **patch_payload(FALLBACK_REPO_ROOT, "docs/API_CONTRACT.md"),
                    "last_assistant_message": "\n".join(
                        [
                            "보호 경로 변경 요청:",
                            "보호 경로 대상: docs/API_CONTRACT.md",
                            "보호 경로 변경 기준: current task approval only",
                            "사용자 승인: 확인",
                        ]
                    ),
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/protected-guard",
            ),
        ),
        (
            "protected mutation with path-specific approval is allowed",
            lambda: not denied_output(
                {
                    **patch_payload(FALLBACK_REPO_ROOT, "docs/API_CONTRACT.md"),
                    "last_user_message": "\n".join(
                        [
                            "보호 경로 변경 요청:",
                            "보호 경로 대상: docs/API_CONTRACT.md",
                            "보호 경로 변경 기준: current task approval only",
                            "사용자 승인: 확인",
                        ]
                    ),
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/protected-guard",
            ),
        ),
        (
            "build output mutation is denied before protected approval",
            lambda: "build output 변경 차단" in denied_output(
                {
                    **patch_payload(FALLBACK_REPO_ROOT, "apps/api/build/generated.txt"),
                    "last_assistant_message": "\n".join(
                        [
                            "보호 경로 변경 요청:",
                            "보호 경로 대상: apps/api/build/generated.txt",
                            "보호 경로 변경 기준: current task approval only",
                            "사용자 승인: 확인",
                        ]
                    ),
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/protected-guard",
            ),
        ),
        (
            "direct gh pr create is denied",
            lambda: "PR 생성 차단" in denied_output(
                {
                    "cwd": str(FALLBACK_REPO_ROOT),
                    "tool_input": {
                        "cmd": "gh pr create --draft --base main --head feat/x-integration --title '[Fix] x' --body-file /tmp/body.md"
                    },
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/x-integration",
            ),
        ),
        (
            "harness PR helper is allowed",
            lambda: not denied_output(
                {
                    "cwd": str(FALLBACK_REPO_ROOT),
                    "tool_input": {
                        "cmd": (
                            "python3 .codex/harness/home_pr.py --branch feat/x-integration "
                            "--title '[Fix] x' --body-file /tmp/body.md --dry-run"
                        )
                    },
                },
                repo_root=FALLBACK_REPO_ROOT,
                branch_name="feat/x-integration",
            ),
        ),
    ]

    failed = [name for name, check in tests if not check()]
    if failed:
        print("self-test failed:")
        for name in failed:
            print(f"- {name}")
        return 1
    print("self-test passed: pre_tool_use_policy")
    return 0


def main() -> None:
    check_payload(load_payload())


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    main()

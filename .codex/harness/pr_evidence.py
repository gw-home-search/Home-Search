#!/usr/bin/env python3
"""Shared PR evidence policy for Home Search harness checks."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

DIFF_CHECK = "git diff --check"
DOCKER_COMPOSE_LOCAL_CONFIG = "docker compose -f infra/docker-compose.local.yml config"
API_QUALITY = "cd apps/api && ./gradlew backendQualityCheck"
WEB_TEST = "cd apps/web && npm run test"
WEB_BUILD = "cd apps/web && npm run build"
TEST_DISPLAY_NAME_POLICY = "python3 scripts/check-test-display-names.py"
PR_LINT_SELF_TEST = "python3 .codex/harness/pr_lint.py --self-test"
PR_CONTEXT_SELF_TEST = "python3 .codex/harness/pr_context.py --self-test"
WORKLOG_SYNC_SELF_TEST = "python3 .codex/harness/worklog_sync.py --self-test"
HARNESS_PR_SELF_TEST = "python3 .codex/harness/home_pr.py --self-test"
HARNESS_FLOW_SELF_TEST = "python3 .codex/harness/home_flow.py --self-test"
HARNESS_PLAN_SELF_TEST = "python3 .codex/harness/home_plan.py --self-test"
HARNESS_REPORT_SELF_TEST = "python3 .codex/harness/home_report.py --self-test"
HARNESS_LAUNCHER_SELF_TEST = ".codex/harness/home --self-test"
SKILL_ROUTING_SELF_TEST = "python3 .codex/harness/skill_routing.py --self-test"
PROJECT_TERMS_CHECK = "python3 .codex/harness/project_terms_check.py"
PROJECT_TERMS_SELF_TEST = "python3 .codex/harness/project_terms_check.py --self-test"
STOP_HOOK_SELF_TEST = "python3 .codex/hooks/stop_verification_gate.py --self-test"
PRE_TOOL_USE_POLICY_SELF_TEST = "python3 .codex/hooks/pre_tool_use_policy.py --self-test"
POST_TOOL_USE_REVIEW_SELF_TEST = "python3 .codex/hooks/post_tool_use_review.py --self-test"

COMMAND_ORDER = (
    DIFF_CHECK,
    DOCKER_COMPOSE_LOCAL_CONFIG,
    API_QUALITY,
    WEB_TEST,
    WEB_BUILD,
    TEST_DISPLAY_NAME_POLICY,
    PR_LINT_SELF_TEST,
    PR_CONTEXT_SELF_TEST,
    WORKLOG_SYNC_SELF_TEST,
    HARNESS_PR_SELF_TEST,
    HARNESS_FLOW_SELF_TEST,
    HARNESS_PLAN_SELF_TEST,
    HARNESS_REPORT_SELF_TEST,
    HARNESS_LAUNCHER_SELF_TEST,
    SKILL_ROUTING_SELF_TEST,
    PROJECT_TERMS_CHECK,
    PROJECT_TERMS_SELF_TEST,
    STOP_HOOK_SELF_TEST,
    PRE_TOOL_USE_POLICY_SELF_TEST,
    POST_TOOL_USE_REVIEW_SELF_TEST,
)

SELF_TESTS_BY_FILE: dict[str, tuple[str, ...]] = {
    ".codex/harness/pr_lint.py": (PR_LINT_SELF_TEST,),
    ".codex/harness/pr_context.py": (PR_CONTEXT_SELF_TEST, PR_LINT_SELF_TEST),
    ".codex/harness/pr_evidence.py": (PR_LINT_SELF_TEST, HARNESS_FLOW_SELF_TEST, HARNESS_REPORT_SELF_TEST),
    ".codex/harness/worklog_sync.py": (WORKLOG_SYNC_SELF_TEST,),
    ".codex/harness/home_pr.py": (HARNESS_PR_SELF_TEST,),
    ".codex/harness/home_flow.py": (HARNESS_FLOW_SELF_TEST, HARNESS_LAUNCHER_SELF_TEST),
    ".codex/harness/home_plan.py": (HARNESS_PLAN_SELF_TEST,),
    ".codex/harness/home_report.py": (HARNESS_REPORT_SELF_TEST,),
    ".codex/harness/home": (HARNESS_LAUNCHER_SELF_TEST,),
    ".codex/harness/skill_routing.py": (SKILL_ROUTING_SELF_TEST,),
    ".codex/harness/project_terms_check.py": (PROJECT_TERMS_SELF_TEST,),
    ".codex/hooks/stop_verification_gate.py": (STOP_HOOK_SELF_TEST,),
    ".codex/hooks/pre_tool_use_policy.py": (PRE_TOOL_USE_POLICY_SELF_TEST,),
    ".codex/hooks/post_tool_use_review.py": (POST_TOOL_USE_REVIEW_SELF_TEST,),
}

SELF_TESTS_BY_DIR: tuple[tuple[str, tuple[str, ...]], ...] = (
    (".codex/harness/presets/", (HARNESS_FLOW_SELF_TEST,)),
    (".codex/harness/prompts/", (HARNESS_FLOW_SELF_TEST,)),
    (".codex/harness/reporters/", (HARNESS_REPORT_SELF_TEST,)),
)


def self_tests_for_path(path: str) -> tuple[str, ...]:
    """Self-tests for the changed file only — not the whole harness suite."""
    direct = SELF_TESTS_BY_FILE.get(path)
    if direct:
        return direct
    for prefix, commands in SELF_TESTS_BY_DIR:
        if path.startswith(prefix):
            return commands
    if path.startswith(".codex/harness/") or path.startswith(".codex/hooks/"):
        return (HARNESS_FLOW_SELF_TEST,)
    return ()


@dataclass(frozen=True)
class EvidenceRequirements:
    commands: frozenset[str]
    forbidden_paths: tuple[tuple[str, str], ...]
    backend_changed: bool
    web_changed: bool
    canonical_markdown: tuple[str, ...]

    @property
    def requires_backend_quality(self) -> bool:
        return self.backend_changed


def parse_git_status(raw: str) -> list[str]:
    """Parse `git status --short/--porcelain` output into changed file paths."""
    files: list[str] = []
    for line in raw.splitlines():
        path = line[3:].strip() if len(line) > 3 else ""
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        if path:
            files.append(path)
    return files


def ordered_commands(commands: set[str] | frozenset[str]) -> list[str]:
    ordered = [command for command in COMMAND_ORDER if command in commands]
    ordered.extend(sorted(command for command in commands if command not in COMMAND_ORDER))
    return ordered


def is_removed_companion_doc(path: str) -> bool:
    return path.lower().endswith("_ko.md")


def is_removed_path(path: str) -> bool:
    normalized = str(path).strip()
    return bool(normalized) and not (REPO_ROOT / normalized).exists()


def is_canonical_markdown(path: str) -> bool:
    lowered = path.lower()
    return lowered.endswith(".md") and not is_removed_companion_doc(path)


def requires_project_terms_check(path: str) -> bool:
    lowered = path.lower()
    if is_removed_companion_doc(path):
        return False
    if lowered.endswith(".md") and (
        path in {"AGENTS.md", "README.md", "CONTEXT.md", "CLAUDE.md"}
        or path.startswith("docs/")
        or path.startswith("apps/api/")
        or path.startswith("apps/web/")
        or path.startswith(".agents/skills/")
        or path.startswith(".codex/harness/prompts/")
        or path.startswith(".github/")
    ):
        return True
    if path.startswith(".codex/agents/"):
        return True
    return False


def is_forbidden_path(path: str) -> str | None:
    normalized = path.strip("/")
    parts = [part for part in normalized.split("/") if part]
    basename = parts[-1].lower() if parts else normalized.lower()
    if basename.startswith(".env"):
        return ".env* 파일은 금지됩니다"
    if "node_modules" in parts:
        return "node_modules output은 금지됩니다"
    build_parts = {"build", "dist", "target", "coverage", ".gradle", ".vite", ".next", "out"}
    if any(part in build_parts for part in parts):
        return "build output 경로는 금지됩니다"
    secret_tokens = [
        "secret",
        "password",
        "passwd",
        "credential",
        "private_key",
        "private-key",
        "id_rsa",
        "id_dsa",
        "id_ed25519",
        "service-account",
        "apikey",
        "api-key",
        ".pem",
        ".p12",
        ".key",
        ".keystore",
    ]
    if any(token in basename for token in secret_tokens):
        return "secret으로 보이는 파일명은 금지됩니다"
    return None


def requirements_for_changed_files(changed_files: list[str] | tuple[str, ...] | set[str]) -> EvidenceRequirements:
    changed = sorted({str(path) for path in changed_files if str(path).strip()})
    commands: set[str] = {DIFF_CHECK}
    forbidden: list[tuple[str, str]] = []
    worklog_path = ".codex/harness/worklog.toml"
    worklog_only = changed == [worklog_path]

    for path in changed:
        reason = is_forbidden_path(path)
        if reason:
            forbidden.append((path, reason))
        companion_doc = is_removed_companion_doc(path)
        if path.startswith("apps/api/") and not companion_doc:
            commands.add(API_QUALITY)
        if path.startswith("apps/web/") and not companion_doc:
            commands.add(WEB_TEST)
            commands.add(WEB_BUILD)
        if (
            path.startswith("apps/api/src/test/java/")
            or (
                path.startswith("apps/web/src/")
                and (path.endswith(".test.ts") or path.endswith(".test.tsx"))
            )
            or path == "scripts/check-test-display-names.py"
            or path == ".github/workflows/ci.yml"
        ):
            commands.add(TEST_DISPLAY_NAME_POLICY)
        if path.startswith("infra/"):
            commands.add(DOCKER_COMPOSE_LOCAL_CONFIG)
        if requires_project_terms_check(path):
            commands.add(PROJECT_TERMS_CHECK)
        if path == worklog_path:
            if worklog_only:
                commands.add(WORKLOG_SYNC_SELF_TEST)
                commands.add(HARNESS_PLAN_SELF_TEST)
            continue
        commands.update(self_tests_for_path(path))
        if path.startswith(".github/"):
            commands.add(PR_LINT_SELF_TEST)

    canonical_markdown = tuple(path for path in changed if is_canonical_markdown(path))

    return EvidenceRequirements(
        commands=frozenset(commands),
        forbidden_paths=tuple(forbidden),
        backend_changed=any(path.startswith("apps/api/") and not is_removed_companion_doc(path) for path in changed),
        web_changed=any(path.startswith("apps/web/") and not is_removed_companion_doc(path) for path in changed),
        canonical_markdown=canonical_markdown,
    )

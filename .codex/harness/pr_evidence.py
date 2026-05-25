#!/usr/bin/env python3
"""Shared PR evidence policy for Home Search V1 harness checks."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


DIFF_CHECK = "git diff --check"
DOCKER_COMPOSE_LOCAL_CONFIG = "docker compose -f infra/docker-compose.local.yml config"
API_QUALITY = "cd apps/api && ./gradlew backendQualityCheck"
WEB_TEST = "cd apps/web && npm run test"
WEB_BUILD = "cd apps/web && npm run build"
TEST_DISPLAY_NAME_POLICY = "python3 scripts/check-test-display-names.py"
PR_LINT_SELF_TEST = "python3 .codex/harness/pr_lint.py --self-test"
PR_CONTEXT_SELF_TEST = "python3 .codex/harness/pr_context.py --self-test"
PR_BODY_CHECK_SELF_TEST = "python3 .codex/harness/pr_body_check.py --self-test"
BACKLOG_SYNC_SELF_TEST = "python3 .codex/harness/backlog_sync.py --self-test"
V1_PR_SELF_TEST = "python3 .codex/harness/v1_pr.py --self-test"
V1_FLOW_SELF_TEST = "python3 .codex/harness/v1_flow.py --self-test"
V1_PLAN_SELF_TEST = "python3 .codex/harness/v1_plan.py --self-test"
V1_REPORT_SELF_TEST = "python3 .codex/harness/v1_report.py --self-test"
V1_LAUNCHER_SELF_TEST = ".codex/harness/v1 --self-test"
SKILL_ROUTING_SELF_TEST = "python3 .codex/harness/skill_routing.py --self-test"
USER_LANGUAGE_CHECK = "python3 .codex/harness/user_language_check.py --self-test"
STOP_HOOK_SELF_TEST = "python3 .codex/hooks/stop_verification_gate.py --self-test"
POST_TOOL_USE_REVIEW_SELF_TEST = "python3 .codex/hooks/post_tool_use_review.py --self-test"
KO_CHECK = "bash scripts/check-ko-docs.sh"

COMMAND_ORDER = (
    DIFF_CHECK,
    DOCKER_COMPOSE_LOCAL_CONFIG,
    API_QUALITY,
    WEB_TEST,
    WEB_BUILD,
    TEST_DISPLAY_NAME_POLICY,
    PR_LINT_SELF_TEST,
    PR_CONTEXT_SELF_TEST,
    PR_BODY_CHECK_SELF_TEST,
    BACKLOG_SYNC_SELF_TEST,
    V1_PR_SELF_TEST,
    V1_FLOW_SELF_TEST,
    V1_PLAN_SELF_TEST,
    V1_REPORT_SELF_TEST,
    V1_LAUNCHER_SELF_TEST,
    SKILL_ROUTING_SELF_TEST,
    USER_LANGUAGE_CHECK,
    STOP_HOOK_SELF_TEST,
    POST_TOOL_USE_REVIEW_SELF_TEST,
    KO_CHECK,
)


@dataclass(frozen=True)
class EvidenceRequirements:
    commands: frozenset[str]
    ko_targets: tuple[str, ...]
    missing_ko_pairs: tuple[tuple[str, str], ...]
    forbidden_paths: tuple[tuple[str, str], ...]
    backend_changed: bool
    web_changed: bool
    canonical_markdown: tuple[str, ...]
    ko_docs: tuple[str, ...]

    @property
    def requires_backend_quality(self) -> bool:
        return self.backend_changed

    @property
    def requires_ko_approval(self) -> bool:
        return bool(self.ko_docs)


def ordered_commands(commands: set[str] | frozenset[str]) -> list[str]:
    ordered = [command for command in COMMAND_ORDER if command in commands]
    ordered.extend(sorted(command for command in commands if command not in COMMAND_ORDER))
    return ordered


def is_ko_local(path: str) -> bool:
    return path.lower().endswith("_ko.local.md")


def is_ko_doc(path: str) -> bool:
    lowered = path.lower()
    return lowered.endswith("_ko.md") and not is_ko_local(path)


def is_canonical_markdown(path: str) -> bool:
    lowered = path.lower()
    return (
        lowered.endswith(".md")
        and not lowered.startswith("ai-docs/")
        and not is_ko_doc(path)
        and not is_ko_local(path)
    )


def paired_ko_path(source: str) -> str:
    path = Path(source)
    return str(path.with_name(f"{path.stem}_KO.md"))


def canonical_for_ko(path: str) -> str:
    return path[:-6] + ".md" if path.lower().endswith("_ko.md") else path


def is_forbidden_path(path: str) -> str | None:
    normalized = path.strip("/")
    parts = [part for part in normalized.split("/") if part]
    basename = parts[-1].lower() if parts else normalized.lower()
    if basename.startswith(".env"):
        return ".env* 파일은 금지됩니다"
    if is_ko_local(path):
        return "*_KO.local.md 파일은 금지됩니다"
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
    backlog_path = ".codex/harness/slices/backlog.toml"
    backlog_only = changed == [backlog_path]

    for path in changed:
        reason = is_forbidden_path(path)
        if reason:
            forbidden.append((path, reason))
        if path.startswith("apps/api/"):
            commands.add(API_QUALITY)
        if path.startswith("apps/web/"):
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
        if path == backlog_path:
            if backlog_only:
                commands.add(BACKLOG_SYNC_SELF_TEST)
                commands.add(V1_PLAN_SELF_TEST)
            continue
        if path.startswith(".codex/harness/"):
            commands.add(PR_LINT_SELF_TEST)
            commands.add(PR_CONTEXT_SELF_TEST)
            commands.add(PR_BODY_CHECK_SELF_TEST)
            commands.add(BACKLOG_SYNC_SELF_TEST)
            commands.add(V1_PR_SELF_TEST)
            commands.add(V1_FLOW_SELF_TEST)
            commands.add(V1_PLAN_SELF_TEST)
            commands.add(V1_REPORT_SELF_TEST)
            commands.add(V1_LAUNCHER_SELF_TEST)
            commands.add(SKILL_ROUTING_SELF_TEST)
            commands.add(USER_LANGUAGE_CHECK)
        if path.startswith(".github/") or path == "scripts/check-ko-docs.sh":
            commands.add(PR_LINT_SELF_TEST)
            commands.add(USER_LANGUAGE_CHECK)
        if path.startswith(".codex/hooks/"):
            commands.add(STOP_HOOK_SELF_TEST)
            commands.add(POST_TOOL_USE_REVIEW_SELF_TEST)

    canonical_markdown = tuple(path for path in changed if is_canonical_markdown(path))
    missing_pairs: list[tuple[str, str]] = []
    if canonical_markdown:
        commands.add(KO_CHECK)
        for source in canonical_markdown:
            ko_path = paired_ko_path(source)
            if ko_path not in changed:
                missing_pairs.append((source, ko_path))

    ko_docs = tuple(path for path in changed if is_ko_doc(path))
    if ko_docs:
        commands.add(KO_CHECK)

    return EvidenceRequirements(
        commands=frozenset(commands),
        ko_targets=ko_docs,
        missing_ko_pairs=tuple(missing_pairs),
        forbidden_paths=tuple(forbidden),
        backend_changed=any(path.startswith("apps/api/") for path in changed),
        web_changed=any(path.startswith("apps/web/") for path in changed),
        canonical_markdown=canonical_markdown,
        ko_docs=ko_docs,
    )

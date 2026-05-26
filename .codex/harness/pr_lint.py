#!/usr/bin/env python3
"""Strict PR metadata and evidence lint for Home Search."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from pr_context import PrContext, changed_files_from_sources, context_from_event, context_from_local
from pr_evidence import (
    API_QUALITY,
    WORKLOG_SYNC_SELF_TEST,
    DIFF_CHECK,
    POST_TOOL_USE_REVIEW_SELF_TEST,
    PR_BODY_CHECK_SELF_TEST,
    PR_CONTEXT_SELF_TEST,
    PROJECT_TERMS_CHECK,
    PROJECT_TERMS_SELF_TEST,
    PR_LINT_SELF_TEST,
    SKILL_ROUTING_SELF_TEST,
    STOP_HOOK_SELF_TEST,
    TEST_DISPLAY_NAME_POLICY,
    USER_LANGUAGE_CHECK,
    HARNESS_FLOW_SELF_TEST,
    HARNESS_INTEGRATE_SELF_TEST,
    HARNESS_LAUNCHER_SELF_TEST,
    HARNESS_PLAN_SELF_TEST,
    HARNESS_PR_SELF_TEST,
    HARNESS_REPORT_SELF_TEST,
    WEB_BUILD,
    WEB_TEST,
    is_removed_companion_doc,
    requirements_for_changed_files,
)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

GROUPS = ("title", "branch", "body", "evidence", "changed-files")
EVIDENCE_POLICIES = {"strict", "feasibility", "structure"}
GROUP_LABELS = {
    "title": "제목",
    "branch": "브랜치",
    "body": "본문",
    "evidence": "검증 근거",
    "changed-files": "변경 파일",
}

REQUIRED_SECTIONS = [
    ("요약", ("요약",)),
    ("작업 범위", ("작업 범위",)),
    ("TDD 근거", ("TDD 근거", "TDD Evidence")),
    ("검증", ("검증",)),
    ("계약 영향", ("계약 영향", "Contract 영향")),
    ("주요 위험", ("주요 위험",)),
    ("다음 행동", ("다음 행동",)),
    ("체크리스트", ("체크리스트",)),
]
EVIDENCE_LABELS = [
    ("최초 RED:", ("최초 RED:", "First RED:")),
    ("예상 RED 실패:", ("예상 RED 실패:", "Expected RED failure:")),
    ("최소 GREEN:", ("최소 GREEN:", "Minimum GREEN:")),
]
CHECKLIST_ITEMS = [
    "main merge 자동화 없음",
    "main push 없음",
    "integration branch만 push",
    "draft PR",
    "public API URL/response 영향 확인",
    "DB migration 실행 없음",
    "Open API 호출 없음",
    "secrets 저장 없음",
]
TITLE_TYPES = ("Feat", "Fix", "Chore", "Docs", "Test", "Refactor")

API_TEST = API_QUALITY
COVERAGE_LINE_RE = re.compile(r"^\s*Coverage:\s*>=\s*90%\s*$", re.MULTILINE)
DOCS_OPENAPI_LINE_RE = re.compile(r"^\s*Docs/OpenAPI:\s*generated\s+\+\s+verified\s*$", re.MULTILINE)
SKILL_TRIGGER_RE = re.compile(r"\$[a-z0-9][a-z0-9-]*")

VERIFICATION_LINE_RE = re.compile(
    r"^\s*-\s+`(?P<command>[^`\n]+)`\s+=\s+"
    r"(?P<status>pass|fail|not run)"
    r"(?:\s+\((?P<reason>[^)\n]*)\))?\s*$",
    re.IGNORECASE,
)
HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(?P<title>.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class LintMessage:
    group: str
    message: str


@dataclass(frozen=True)
class VerificationLine:
    command: str
    status: str
    reason: str | None


@dataclass
class LintResult:
    errors: list[LintMessage]

    @property
    def ok(self) -> bool:
        return not self.errors


@dataclass(frozen=True)
class PrInput:
    title: str
    body: str
    base: str
    head: str
    draft: bool
    changed_files: tuple[str, ...] = ()


@dataclass(frozen=True)
class BodyCheckResult:
    ok: bool
    errors: list[str]


def add(errors: list[LintMessage], group: str, message: str) -> None:
    if group not in GROUPS:
        raise ValueError(f"unknown lint group: {group}")
    errors.append(LintMessage(group, message))


def grouped(errors: Iterable[LintMessage]) -> dict[str, list[str]]:
    output = {group: [] for group in GROUPS}
    for error in errors:
        output.setdefault(error.group, []).append(error.message)
    return output


def format_grouped_errors(errors: Iterable[LintMessage]) -> str:
    buckets = grouped(errors)
    lines: list[str] = []
    for group in GROUPS:
        messages = buckets.get(group, [])
        if not messages:
            continue
        lines.append(f"{GROUP_LABELS.get(group, group)}:")
        lines.extend(f"- {message}" for message in messages)
    return "\n".join(lines)


def format_errors(errors: Iterable[str]) -> str:
    return "\n".join(f"- {error}" for error in errors)


def github_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def print_lint_errors(errors: list[LintMessage]) -> None:
    print("pr-lint 실패", file=sys.stderr)
    print(format_grouped_errors(errors), file=sys.stderr)
    for error in errors:
        print(
            f"::error title=pr-lint {github_escape(GROUP_LABELS.get(error.group, error.group))}::{github_escape(error.message)}",
            file=sys.stderr,
        )


def pr_input_from_context(context: PrContext) -> PrInput:
    return PrInput(
        title=context.title,
        body=context.body,
        base=context.base,
        head=context.head,
        draft=context.draft,
        changed_files=context.changed_files,
    )


def has_section(body: str, section: str) -> bool:
    for match in HEADING_RE.finditer(body):
        if match.group("title").strip() == section:
            return True
    return False


def has_any_section(body: str, sections: Iterable[str]) -> bool:
    expected = set(sections)
    for match in HEADING_RE.finditer(body):
        if match.group("title").strip() in expected:
            return True
    return False


def section_text(body: str, section: str) -> str:
    matches = list(HEADING_RE.finditer(body))
    for index, match in enumerate(matches):
        if match.group("title").strip() != section:
            continue
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        return body[start:end].strip()
    return ""


def section_text_any(body: str, sections: Iterable[str]) -> str:
    expected = set(sections)
    matches = list(HEADING_RE.finditer(body))
    for index, match in enumerate(matches):
        if match.group("title").strip() not in expected:
            continue
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        return body[start:end].strip()
    return ""


def first_label_value(text: str, label: str) -> str:
    pattern = re.compile(rf"^\s*{re.escape(label)}\s*(?P<value>.*)$", re.MULTILINE)
    match = pattern.search(text)
    return match.group("value").strip() if match else ""


def parse_status(body: str) -> str | None:
    match = re.search(r"^\s*상태:\s*(Pass|Partial|Fail)\s*$", body, re.MULTILINE)
    return match.group(1) if match else None


def no_risk_text(value: str) -> bool:
    normalized = value.strip().lower()
    return normalized in {"없음", "none", "n/a", "not applicable", "해당 없음"} or normalized.startswith("없음 ")


def concrete_text(value: str) -> bool:
    normalized = value.strip().lower()
    if not normalized:
        return False
    if normalized in {"없음", "none", "n/a", "not applicable", "tbd", "todo", "미정", "미확인"}:
        return False
    if "<" in normalized or ">" in normalized:
        return False
    return True


def parse_verification_lines(body: str, errors: list[LintMessage]) -> dict[str, VerificationLine]:
    lines: dict[str, VerificationLine] = {}
    for raw in body.splitlines():
        stripped = raw.strip()
        if not stripped.startswith("-") or "`" not in stripped or "=" not in stripped:
            continue
        match = VERIFICATION_LINE_RE.match(raw)
        if not match:
            add(errors, "evidence", f"검증 line 형식이 올바르지 않습니다: {stripped}")
            continue
        command = match.group("command").strip()
        status = match.group("status").lower()
        reason = match.group("reason")
        if reason is None or not reason.strip():
            add(errors, "evidence", f"검증 line에는 비어 있지 않은 사유가 필요합니다: `{command}`")
        lines[command] = VerificationLine(command=command, status=status, reason=reason)
    return lines


def check_title(title: str, errors: list[LintMessage]) -> None:
    stripped = title.strip()
    if len(stripped) < 10 or len(stripped) > 90:
        add(errors, "title", "제목 길이는 10-90자여야 합니다")
    lowered = stripped.lower()
    conventional_summary = stripped.split(":", 1)[1].strip().lower() if ":" in stripped else ""
    placeholders = {
        "title",
        "pr title",
        "summary",
        "type(scope): summary",
        "제목",
        "요약",
        "한글 요약",
    }
    if lowered in placeholders or conventional_summary in placeholders or "<" in stripped or ">" in stripped:
        add(errors, "title", "제목에 placeholder 문구를 남기면 안 됩니다")
    if re.search(r"\b(WIP|TODO|TBD)\b", stripped, re.IGNORECASE) or "placeholder" in lowered:
        add(errors, "title", "제목에 WIP/TODO/placeholder 문구를 남기면 안 됩니다")

    bracket = re.fullmatch(r"\[(?P<type>[A-Za-z]+)\]\s*(?P<summary>.+)", stripped)
    bracket_title = False
    if bracket:
        title_type = bracket.group("type")
        summary = bracket.group("summary").strip()
        bracket_title = title_type in TITLE_TYPES and bool(summary)
        if title_type not in TITLE_TYPES:
            add(errors, "title", "제목 type은 [Feat|Fix|Chore|Docs|Test|Refactor] 중 하나여야 합니다")
        if summary.lower() in placeholders:
            add(errors, "title", "제목에 placeholder 문구를 남기면 안 됩니다")
        if not re.search(r"[가-힣]", summary):
            add(errors, "title", "bracket 제목 요약에는 한글 설명이 포함되어야 합니다")

    conventional = re.fullmatch(r"[a-z][a-z0-9-]*\([a-z0-9_.-]+\): .+", stripped)
    if not (bracket_title or conventional):
        add(errors, "title", "제목은 `[Feat] 한글 요약` 또는 `type(scope): summary` 형식이어야 합니다")


def check_branch(base: str, head: str, draft: bool, errors: list[LintMessage]) -> None:
    if base != "main":
        add(errors, "branch", "base branch는 main이어야 합니다")
    if head in {"main", "master"}:
        add(errors, "branch", "head branch는 main/master일 수 없습니다")
    if not re.fullmatch(r"feat/.+-integration", head or ""):
        add(errors, "branch", "head branch는 feat/*-integration 형식이어야 합니다")
    if not draft:
        add(errors, "branch", "PR은 draft여야 합니다")


def check_body_structure(body: str, errors: list[LintMessage], *, template: bool) -> dict[str, VerificationLine]:
    stripped = body.strip()
    if not stripped:
        add(errors, "body", "PR body는 비어 있으면 안 됩니다")

    for canonical, aliases in REQUIRED_SECTIONS:
        if not has_any_section(body, aliases):
            add(errors, "body", f"필수 section이 없습니다: {canonical}")

    for canonical, aliases in EVIDENCE_LABELS:
        if not any(label in body for label in aliases):
            add(errors, "body", f"검증 근거 label이 없습니다: {canonical}")

    if "검증:" not in body:
        add(errors, "body", "검증 label이 필요합니다")
    if not re.search(r"영향\s+없음|영향\s+있음\s*:", body):
        add(errors, "body", "계약 영향에는 '영향 없음' 또는 '영향 있음:'이 필요합니다")
    if "주요 위험:" not in body:
        add(errors, "body", "주요 위험 label이 필요합니다")
    if "다음 행동:" not in body:
        add(errors, "body", "다음 행동 label이 필요합니다")

    if template:
        if not re.search(r"^\s*상태:\s*(Pass\|Partial\|Fail|Pass|Partial|Fail)\s*$", body, re.MULTILINE):
            add(errors, "body", "template에는 상태: Pass|Partial|Fail 이 필요합니다")
    else:
        if parse_status(body) is None:
            add(errors, "body", "상태는 정확히 '상태: Pass|Partial|Fail' 중 하나여야 합니다")

    for item in CHECKLIST_ITEMS:
        if template:
            pattern = rf"^\s*-\s+\[[ xX]\]\s+{re.escape(item)}\s*$"
        else:
            pattern = rf"^\s*-\s+\[[xX]\]\s+{re.escape(item)}\s*$"
        if not re.search(pattern, body, re.MULTILINE):
            suffix = "있어야 합니다" if template else "[x]로 체크되어야 합니다"
            add(errors, "body", f"checklist item은 {suffix}: {item}")

    return parse_verification_lines(body, errors)


def required_commands_for_files(changed_files: Iterable[str], errors: list[LintMessage]) -> set[str]:
    changed = set(changed_files)
    requirements = requirements_for_changed_files(changed)
    for path, reason in requirements.forbidden_paths:
        add(errors, "changed-files", f"{path}: {reason}")
    return set(requirements.commands)


def required_skill_triggers_for_files(changed_files: Iterable[str]) -> set[str]:
    changed = tuple(changed_files)
    if not changed:
        return set()
    required = {"home-search-harness"}
    if any(path.startswith("apps/api/") and not is_removed_companion_doc(path) for path in changed):
        required.update({"$backend-api", "$tdd", "$api-contract", "$code-review"})
    if any(path.startswith("apps/web/") and not is_removed_companion_doc(path) for path in changed):
        required.update({"$frontend-web", "$tdd", "$api-contract", "$code-review"})
    if any(path.startswith(".codex/harness/") for path in changed):
        required.update({"$code-review"})
    if any(path.startswith(".codex/hooks/") for path in changed):
        required.update({"$systematic-debugging", "$code-review"})
    return required


def check_skill_evidence(body: str, changed_files: tuple[str, ...], errors: list[LintMessage]) -> None:
    required = required_skill_triggers_for_files(changed_files)
    if not required:
        return
    found = set(SKILL_TRIGGER_RE.findall(body))
    if "home-search-harness" in body:
        found.add("home-search-harness")
    for trigger in sorted(required):
        if trigger not in found:
            add(errors, "evidence", f"필수 skill evidence가 없습니다: {trigger}")


def check_evidence(
    body: str,
    verification: dict[str, VerificationLine],
    changed_files: tuple[str, ...],
    errors: list[LintMessage],
    *,
    evidence_policy: str,
) -> None:
    if evidence_policy not in EVIDENCE_POLICIES:
        raise ValueError(f"unknown evidence policy: {evidence_policy}")
    status = parse_status(body)
    enforce_changed_file_rules = evidence_policy in {"strict", "feasibility"}
    require_pass = evidence_policy == "strict"
    required = required_commands_for_files(changed_files, errors) if enforce_changed_file_rules else set()
    backend_changed = requirements_for_changed_files(changed_files).backend_changed
    if enforce_changed_file_rules:
        check_skill_evidence(body, changed_files, errors)

    for command in sorted(required):
        line = verification.get(command)
        if line is None:
            expected = "pass" if require_pass else "pass|not run"
            add(errors, "evidence", f"필수 검증 근거가 없습니다: `{command}` = {expected}")
        elif require_pass and line.status != "pass":
            add(errors, "evidence", f"필수 검증은 pass여야 합니다: `{command}` = {line.status}")
        elif not require_pass and line.status == "fail":
            add(errors, "evidence", f"feasibility 검증은 fail이면 안 됩니다: `{command}`")

    if backend_changed and require_pass:
        if not COVERAGE_LINE_RE.search(body):
            add(errors, "evidence", "apps/api 변경에는 `Coverage: >=90%` evidence가 필요합니다")
        if not DOCS_OPENAPI_LINE_RE.search(body):
            add(errors, "evidence", "apps/api 변경에는 `Docs/OpenAPI: generated + verified` evidence가 필요합니다")

    if status == "Pass":
        for line in verification.values():
            if line.status == "fail":
                add(errors, "evidence", f"Pass 상태에는 fail 검증을 포함할 수 없습니다: `{line.command}`")
        if "미확인" in body:
            add(errors, "evidence", "Pass 상태에는 미확인을 포함할 수 없습니다")

    risk = first_label_value(section_text_any(body, ("주요 위험",)), "주요 위험:")
    next_action = first_label_value(section_text_any(body, ("다음 행동",)), "다음 행동:")
    if status == "Pass" and risk and not no_risk_text(risk):
        add(errors, "body", "Pass 상태에는 해결되지 않은 주요 위험을 포함할 수 없습니다")
    if status in {"Partial", "Fail"}:
        if not concrete_text(risk):
            add(errors, "body", f"{status} 상태에는 구체적인 주요 위험이 필요합니다")
        if not concrete_text(next_action):
            add(errors, "body", f"{status} 상태에는 구체적인 다음 행동이 필요합니다")


def lint_pr(
    data: PrInput,
    *,
    enforce_changed_file_rules: bool | None = None,
    evidence_policy: str = "strict",
) -> LintResult:
    if enforce_changed_file_rules is not None:
        evidence_policy = "strict" if enforce_changed_file_rules else "structure"
    errors: list[LintMessage] = []
    check_title(data.title, errors)
    check_branch(data.base, data.head, data.draft, errors)
    verification = check_body_structure(data.body, errors, template=False)
    check_evidence(
        data.body,
        verification,
        data.changed_files,
        errors,
        evidence_policy=evidence_policy,
    )
    return LintResult(errors)


def check_body(body: str) -> BodyCheckResult:
    errors: list[LintMessage] = []
    verification = check_body_structure(body, errors, template=False)
    check_evidence(body, verification, (), errors, evidence_policy="strict")
    return BodyCheckResult(ok=not errors, errors=[error.message for error in errors])


def lint_template(path: str) -> LintResult:
    errors: list[LintMessage] = []
    body = Path(path).read_text(encoding="utf-8")
    check_body_structure(body, errors, template=True)
    return LintResult(errors)


def valid_body(
    *,
    checklist_checked: bool = True,
    status: str = "Pass",
    risk: str = "없음",
) -> str:
    mark = "x" if checklist_checked else " "
    return f"""## 요약

상태: {status}
이번 PR은 Home Search PR lint CI와 harness evidence 검사를 강화합니다.

## 작업 범위

- backend: 없음
- frontend: 없음
- harness: PR lint validator, draft PR guard, evidence rendering
- docs/infra: PR template, GitHub Actions workflow, documentation policy

## 사용 skill

| phase | skill | role | path | required evidence |
| --- | --- | --- | --- | --- |
| execute | home-search-harness | orchestrator | .codex/harness/home | 상태; 검증; 다음 행동 |
| execute | $tdd | primary | .agents/skills/tdd/SKILL.md | 최초 RED; 예상 RED 실패; 최소 GREEN |
| execute | $backend-api | support | .agents/skills/backend-api/SKILL.md | backendQualityCheck; Coverage: >=90%; Docs/OpenAPI |
| execute | $frontend-web | support | .agents/skills/frontend-web/SKILL.md | cd apps/web && npm run test; cd apps/web && npm run build |
| execute | $api-contract | checkpoint | .agents/skills/api-contract/SKILL.md | 계약 영향 |
| recover | $systematic-debugging | recovery | .agents/skills/systematic-debugging/SKILL.md | 차단 사유; 복구 순서; 검증 |
| gate | $code-review | primary | .agents/skills/code-review/SKILL.md | reviewer: 지적사항; 검증 공백; 잔여 위험 |

## TDD 근거

최초 RED: unchecked checklist and missing changed-file evidence fixtures fail pr-lint
예상 RED 실패: grouped pr-lint errors include body, evidence, changed-files, branch
최소 GREEN: valid draft integration PR fixture passes all metadata and evidence rules

## 검증

검증:
- `{DIFF_CHECK}` = pass (정상)
- `{API_TEST}` = not run (api 변경 없음)
- `{WEB_TEST}` = not run (web 변경 없음)
- `{WEB_BUILD}` = not run (web 변경 없음)
- `{TEST_DISPLAY_NAME_POLICY}` = not run (테스트 표시 이름 변경 없음)
- `{PR_LINT_SELF_TEST}` = pass (자체 테스트)
- `{PR_CONTEXT_SELF_TEST}` = pass (PR context 공용 helper 자체 테스트)
- `{PR_BODY_CHECK_SELF_TEST}` = pass (PR body 검사 자체 테스트)
- `{WORKLOG_SYNC_SELF_TEST}` = pass (worklog sync 자체 테스트)
- `{HARNESS_PR_SELF_TEST}` = pass (draft PR 생성 helper 자체 테스트)
- `{HARNESS_FLOW_SELF_TEST}` = pass (harness flow 자체 테스트)
- `{HARNESS_INTEGRATE_SELF_TEST}` = pass (harness integration 자체 테스트)
- `{HARNESS_PLAN_SELF_TEST}` = pass (harness plan 자체 테스트)
- `{HARNESS_REPORT_SELF_TEST}` = pass (harness report 자체 테스트)
- `{HARNESS_LAUNCHER_SELF_TEST}` = pass (harness launcher 자체 테스트)
- `{SKILL_ROUTING_SELF_TEST}` = pass (skill routing 자체 테스트)
- `{USER_LANGUAGE_CHECK}` = pass (사용자 노출 언어 점검)
- `{PROJECT_TERMS_SELF_TEST}` = pass (용어 점검 자체 테스트)
- `{PROJECT_TERMS_CHECK}` = pass (프로젝트 용어 점검)
- `{STOP_HOOK_SELF_TEST}` = pass (stop hook fixture)
- `{POST_TOOL_USE_REVIEW_SELF_TEST}` = pass (post-tool hook fixture)

Coverage: >=90%
Docs/OpenAPI: generated + verified

## 계약 영향

영향 없음

contract-reviewer: not needed

## 주요 위험

주요 위험: {risk}
reviewer: 지적사항 = none

## 다음 행동

다음 행동: GitHub draft PR에서 pr-lint와 기존 CI check를 확인합니다.

## 체크리스트

- [{mark}] main merge 자동화 없음
- [{mark}] main push 없음
- [{mark}] integration branch만 push
- [{mark}] draft PR
- [{mark}] public API URL/response 영향 확인
- [{mark}] DB migration 실행 없음
- [{mark}] Open API 호출 없음
- [{mark}] secrets 저장 없음
"""


def valid_input(**overrides: object) -> PrInput:
    values = {
        "title": "[Chore] PR lint 근거 검사 강화",
        "body": valid_body(),
        "base": "main",
        "head": "feat/pr-lint-hardening-integration",
        "draft": True,
        "changed_files": (
            ".github/workflows/pr-lint.yml",
            ".github/pull_request_template.md",
            ".codex/harness/pr_lint.py",
        ),
    }
    values.update(overrides)
    return PrInput(**values)  # type: ignore[arg-type]


def legacy_body() -> str:
    return valid_body().replace("## TDD 근거", "## TDD Evidence").replace(
        "최초 RED:", "First RED:"
    ).replace("예상 RED 실패:", "Expected RED failure:").replace("최소 GREEN:", "Minimum GREEN:").replace(
        "## 계약 영향", "## Contract 영향"
    )


def expect_case(name: str, data: PrInput, group: str, needle: str) -> bool:
    result = lint_pr(data)
    if result.ok:
        print(f"self-test failed: {name} unexpectedly passed", file=sys.stderr)
        return False
    matched = any(error.group == group and needle in error.message for error in result.errors)
    if not matched:
        print(f"self-test failed: {name} missing {group}: {needle}", file=sys.stderr)
        print(format_grouped_errors(result.errors), file=sys.stderr)
    return matched


def run_self_test() -> int:
    valid = lint_pr(valid_input())
    legacy = lint_pr(valid_input(body=legacy_body()))
    template = lint_template(".github/pull_request_template.md")
    old_style_unchecked = valid_input(body=valid_body(checklist_checked=False))
    web_missing_build = valid_input(
        body=valid_body().replace(f"- `{WEB_BUILD}` = not run (web 변경 없음)\n", ""),
        changed_files=("apps/web/src/app/App.tsx",),
    )
    backend_missing_quality = valid_input(
        body=valid_body().replace(f"- `{API_TEST}` = not run (api 변경 없음)", f"- `{API_TEST}` = not run (확인 안 함)"),
        changed_files=("apps/api/src/main/java/com/home/App.java",),
    )
    backend_test_only = valid_input(
        body=valid_body().replace(
            f"- `{API_TEST}` = not run (api 변경 없음)",
            "- `cd apps/api && ./gradlew test` = pass (단위 테스트만 확인)",
        ),
        changed_files=("apps/api/src/main/java/com/home/App.java",),
    )
    backend_missing_coverage = valid_input(
        body=valid_body().replace("Coverage: >=90%\n", ""),
        changed_files=("apps/api/src/main/java/com/home/App.java",),
    )
    web_without_lint = valid_input(
        body=valid_body()
        .replace(f"- `{WEB_TEST}` = not run (web 변경 없음)", f"- `{WEB_TEST}` = pass (frontend tests)")
        .replace(f"- `{WEB_BUILD}` = not run (web 변경 없음)", f"- `{WEB_BUILD}` = pass (frontend build)"),
        changed_files=("apps/web/src/app/App.tsx",),
    )
    web_feasibility = valid_input(
        changed_files=("apps/web/__expected__",),
    )
    web_feasibility_missing_build = valid_input(
        body=valid_body().replace(f"- `{WEB_BUILD}` = not run (web 변경 없음)\n", ""),
        changed_files=("apps/web/__expected__",),
    )
    web_feasibility_fail = valid_input(
        body=valid_body().replace(
            f"- `{WEB_TEST}` = not run (web 변경 없음)",
            f"- `{WEB_TEST}` = fail (dry-run fixture failure)",
        ),
        changed_files=("apps/web/__expected__",),
    )
    web_missing_skill = valid_input(
        body=valid_body().replace("$frontend-web", "$frontend-web-missing"),
        changed_files=("apps/web/src/app/App.tsx",),
    )
    hook_missing_skill = valid_input(
        body=valid_body().replace("$systematic-debugging", "$debugging-missing"),
        changed_files=(".codex/hooks/stop_verification_gate.py",),
    )
    hook_missing_self_test = valid_input(
        body=valid_body().replace(
            f"- `{STOP_HOOK_SELF_TEST}` = pass (stop hook fixture)",
            f"- `{STOP_HOOK_SELF_TEST}` = not run (확인 안 함)",
        ),
        changed_files=(".codex/hooks/stop_verification_gate.py",),
    )
    test_display_name_missing = valid_input(
        body=valid_body()
        .replace(f"- `{WEB_TEST}` = not run (web 변경 없음)", f"- `{WEB_TEST}` = pass (frontend tests)")
        .replace(f"- `{WEB_BUILD}` = not run (web 변경 없음)", f"- `{WEB_BUILD}` = pass (frontend build)")
        .replace(f"- `{TEST_DISPLAY_NAME_POLICY}` = not run (테스트 표시 이름 변경 없음)\n", ""),
        changed_files=("apps/web/src/app/App.test.tsx",),
    )
    markdown_missing_terms_check = valid_input(
        body=valid_body().replace(f"- `{PROJECT_TERMS_CHECK}` = pass (프로젝트 용어 점검)\n", ""),
        changed_files=("docs/README.md",),
    )
    pass_with_open_risk = valid_input(body=valid_body(risk="미확인 gate 위험이 남아 있습니다."))
    non_draft = valid_input(draft=False)
    non_integration_head = valid_input(head="feat/pr-lint-hardening")
    bracket_title = valid_input(title="[Feat] 한글 제목 규칙 정리")
    bracket_without_korean = valid_input(title="[Feat] English summary")
    forbidden_env = valid_input(changed_files=(".env.local",))
    placeholder_summary = valid_input(title="feat(api): summary")

    checks = [
        valid.ok,
        legacy.ok,
        template.ok,
        expect_case("unchecked checklist", old_style_unchecked, "body", "checklist item"),
        expect_case("web build evidence missing", web_missing_build, "evidence", WEB_BUILD),
        expect_case("backend quality evidence missing", backend_missing_quality, "evidence", API_TEST),
        expect_case("backend test only is insufficient", backend_test_only, "evidence", API_TEST),
        expect_case("backend coverage evidence missing", backend_missing_coverage, "evidence", "Coverage"),
        lint_pr(web_without_lint).ok,
        lint_pr(web_feasibility, evidence_policy="feasibility").ok,
        not lint_pr(web_feasibility_missing_build, evidence_policy="feasibility").ok,
        not lint_pr(web_feasibility_fail, evidence_policy="feasibility").ok,
        expect_case("web skill evidence missing", web_missing_skill, "evidence", "$frontend-web"),
        expect_case("hook self-test evidence missing", hook_missing_self_test, "evidence", STOP_HOOK_SELF_TEST),
        expect_case("hook skill evidence missing", hook_missing_skill, "evidence", "$systematic-debugging"),
        expect_case(
            "test display name evidence missing",
            test_display_name_missing,
            "evidence",
            TEST_DISPLAY_NAME_POLICY,
        ),
        expect_case("markdown project terms evidence missing", markdown_missing_terms_check, "evidence", PROJECT_TERMS_CHECK),
        expect_case("pass with open risk", pass_with_open_risk, "evidence", "미확인"),
        expect_case("non-draft PR", non_draft, "branch", "draft"),
        expect_case("non-integration head", non_integration_head, "branch", "feat/*-integration"),
        lint_pr(bracket_title).ok,
        expect_case("bracket title requires Korean", bracket_without_korean, "title", "한글"),
        expect_case("forbidden env path", forbidden_env, "changed-files", ".env"),
        expect_case("placeholder conventional title", placeholder_summary, "title", "placeholder"),
    ]
    if all(checks):
        print("self-test passed: pr_lint")
        return 0
    if not valid.ok:
        print("self-test failed: valid fixture did not pass", file=sys.stderr)
        print(format_grouped_errors(valid.errors), file=sys.stderr)
    if not legacy.ok:
        print("self-test failed: legacy label fixture did not pass", file=sys.stderr)
        print(format_grouped_errors(legacy.errors), file=sys.stderr)
    if not template.ok:
        print("self-test failed: PR template fixture did not pass", file=sys.stderr)
        print(format_grouped_errors(template.errors), file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Lint Home Search PR metadata, body, and changed-file evidence.")
    parser.add_argument("--event-json", help="GitHub event JSON path.")
    parser.add_argument("--changed-files-nul", help="NUL-separated changed file path list.")
    parser.add_argument("--changed-files-file", help="Newline-separated changed file path list.")
    parser.add_argument("--template-file", help="Check PR template headings, labels, and checklist labels.")
    parser.add_argument("--title")
    parser.add_argument("--base")
    parser.add_argument("--head", "--branch", dest="head")
    parser.add_argument("--body-file")
    parser.add_argument("--body-env")
    parser.add_argument("--evidence-policy", choices=sorted(EVIDENCE_POLICIES), default="strict")
    draft = parser.add_mutually_exclusive_group()
    draft.add_argument("--draft", dest="draft", action="store_true")
    draft.add_argument("--no-draft", dest="draft", action="store_false")
    parser.set_defaults(draft=None)
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()

    try:
        if args.template_file:
            result = lint_template(args.template_file)
        else:
            changed_files = changed_files_from_sources(args.changed_files_nul, args.changed_files_file)
            context = (
                context_from_event(args.event_json, changed_files)
                if args.event_json
                else context_from_local(
                    title=args.title,
                    base=args.base,
                    head=args.head,
                    draft=args.draft,
                    body_file=args.body_file,
                    body_env=args.body_env,
                    changed_files=changed_files,
                )
            )
            data = pr_input_from_context(context)
            result = lint_pr(data, evidence_policy=args.evidence_policy)
    except (OSError, ValueError) as exc:
        print(f"pr-lint 입력 읽기 실패: {exc}", file=sys.stderr)
        return 2

    if result.ok:
        print("pr-lint 통과")
        return 0
    print_lint_errors(result.errors)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

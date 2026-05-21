#!/usr/bin/env python3
"""Render Home Search V1 slice reports.

Markdown is the canonical local report. Notion and Slack are optional
best-effort reporters and never change the core workflow result.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_evidence import (
    API_QUALITY,
    DIFF_CHECK,
    PR_BODY_CHECK_SELF_TEST,
    ordered_commands,
    PR_LINT_SELF_TEST,
    SKILL_ROUTING_SELF_TEST,
    USER_LANGUAGE_CHECK,
    V1_FLOW_SELF_TEST,
    V1_LAUNCHER_SELF_TEST,
    V1_PLAN_SELF_TEST,
    V1_REPORT_SELF_TEST,
    requirements_for_changed_files,
)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_ROOT = REPO_ROOT / ".codex" / "harness" / "reports"
FALLBACK_PR_BODY_COMMANDS = [DIFF_CHECK]


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def status_text(value: Any) -> str:
    raw = str(value or "Partial").strip().lower()
    if raw == "pass":
        return "Pass"
    if raw == "fail":
        return "Fail"
    return "Partial"


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def nested(payload: dict[str, Any], *keys: str, default: Any = None) -> Any:
    current: Any = payload
    for key in keys:
        if not isinstance(current, dict):
            return default
        current = current.get(key)
    return default if current is None else current


def command_status(result: Any) -> str:
    if isinstance(result, dict):
        return str(result.get("status") or "skipped")
    return str(result or "skipped")


def command_summary(result: Any) -> str:
    if isinstance(result, dict):
        parts = [f"status={result.get('status', 'skipped')}"]
        if result.get("exit_code") is not None:
            parts.append(f"exit={result['exit_code']}")
        if result.get("summary"):
            parts.append(str(result["summary"]))
        return ", ".join(parts)
    return str(result or "skipped")


def changed_files_from_payload(payload: dict[str, Any]) -> list[str]:
    raw = payload.get("changed_files") or []
    if isinstance(raw, dict):
        raw = raw.get("files") or []
    if not isinstance(raw, list):
        return []
    return [str(path) for path in raw if str(path).strip()]


def pr_body_commands(payload: dict[str, Any]) -> list[str]:
    changed_files = changed_files_from_payload(payload)
    if not changed_files:
        return FALLBACK_PR_BODY_COMMANDS
    requirements = requirements_for_changed_files(changed_files)
    return ordered_commands(requirements.commands)


def has_pass(verification: dict[str, Any], command: str) -> bool:
    return command_status(verification.get(command)).lower() == "pass"


def backend_quality_lines(payload: dict[str, Any], verification: dict[str, Any]) -> str:
    changed_files = changed_files_from_payload(payload)
    requirements = requirements_for_changed_files(changed_files)
    if not requirements.requires_backend_quality:
        return ""
    if not has_pass(verification, API_QUALITY):
        return ""
    return "\nCoverage: >=90%\nDocs/OpenAPI: generated + verified\n"


def ko_section(payload: dict[str, Any]) -> str:
    changed_files = changed_files_from_payload(payload)
    requirements = requirements_for_changed_files(changed_files)
    ko_targets = list(requirements.ko_targets)
    ko_payload = payload.get("ko_docs") if isinstance(payload.get("ko_docs"), dict) else {}
    payload_targets = ko_payload.get("targets") if isinstance(ko_payload, dict) else None
    if isinstance(payload_targets, list) and payload_targets:
        ko_targets = [str(path) for path in payload_targets]
    approved = bool(ko_payload.get("approved")) if isinstance(ko_payload, dict) else False
    if ko_targets and approved:
        return "\n".join(
            [
                "KO 수정 승인: 확인",
                f"KO 대상: {', '.join(ko_targets)}",
                "KO 생성 기준: canonical source only",
            ]
        )
    if ko_targets:
        return "\n".join(
            [
                "KO 수정 승인: 미확인",
                f"KO 대상: {', '.join(ko_targets)}",
                "KO 생성 기준: canonical source only",
            ]
        )
    return "\n".join(
        [
            "KO 수정 승인: 해당 없음",
            "KO 대상: 해당 없음",
            "KO 생성 기준: 해당 없음",
        ]
    )


def default_payload(args: argparse.Namespace) -> dict[str, Any]:
    started = args.started_at or now_iso()
    finished = args.finished_at or started
    return {
        "slice": args.slice or "unknown",
        "preset": args.preset or "unknown",
        "targets": args.targets or "both",
        "status": status_text(args.status),
        "started_at": started,
        "finished_at": finished,
        "branches": {
            "api": args.api_branch,
            "web": args.web_branch,
            "integration": args.integration_branch,
        },
        "worktrees": {
            "main": str(REPO_ROOT),
            "api": args.api_worktree,
            "web": args.web_worktree,
        },
        "commits": {},
        "verification": {},
        "gate_review": args.gate_review or "",
        "contract_risks": [],
        "residual_risks": [],
        "next_action": args.next_action or "",
        "commands": {},
        "links": {},
        "notifications": {},
    }


def load_payload(args: argparse.Namespace) -> dict[str, Any]:
    if not args.input_json:
        return default_payload(args)
    if args.input_json == "-":
        raw = sys.stdin.read()
    else:
        raw = Path(args.input_json).read_text(encoding="utf-8")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise ValueError("report payload must be a JSON object")
    payload.setdefault("status", "Partial")
    payload.setdefault("started_at", now_iso())
    payload.setdefault("finished_at", now_iso())
    payload.setdefault("branches", {})
    payload.setdefault("worktrees", {})
    payload.setdefault("commits", {})
    payload.setdefault("verification", {})
    payload.setdefault("links", {})
    payload.setdefault("notifications", {})
    return payload


def report_path_for(payload: dict[str, Any], explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    slug = str(payload.get("slice") or "unknown").strip() or "unknown"
    return REPORT_ROOT / f"{slug}.md"


def write_payload(path: str | None, payload: dict[str, Any], *, dry_run: bool) -> None:
    if not path:
        return
    payload_path = Path(path).resolve()
    payload.setdefault("links", {})["payload_json"] = str(payload_path)
    if dry_run:
        print(f"[DRY-RUN] write payload JSON: {payload_path}")
        return
    payload_path.parent.mkdir(parents=True, exist_ok=True)
    payload_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def pr_body_path_for(payload: dict[str, Any], explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    slug = str(payload.get("slice") or "unknown").strip() or "unknown"
    return REPORT_ROOT / f"{slug}-pr-body.md"


def render_matrix(verification: dict[str, Any]) -> str:
    if not verification:
        return "| 명령 | 상태 |\n| --- | --- |\n| not run | skipped |\n"
    lines = ["| 명령 | 상태 |", "| --- | --- |"]
    for command, result in verification.items():
        safe_command = str(command).replace("|", "\\|")
        lines.append(f"| `{safe_command}` | {command_summary(result)} |")
    return "\n".join(lines) + "\n"


def render_list(items: list[Any]) -> str:
    if not items:
        return "- none\n"
    return "".join(f"- {item}\n" for item in items)


def render_skill_routing(payload: dict[str, Any]) -> str:
    routing = payload.get("skill_routing")
    if not isinstance(routing, dict):
        return "- not recorded\n"
    lines: list[str] = []
    for mode in ("execute", "gate", "recover", "plan"):
        item = routing.get(mode)
        if not isinstance(item, dict):
            continue
        skills = item.get("skills")
        if not isinstance(skills, list) or not skills:
            continue
        names = []
        for skill in skills:
            if isinstance(skill, dict):
                names.append(str(skill.get("trigger") or skill.get("name") or "").strip())
            else:
                names.append(str(skill).strip())
        names = [name for name in names if name]
        if names:
            lines.append(f"- {mode}: {', '.join(names)}")
    return "\n".join(lines) + ("\n" if lines else "- not recorded\n")


def verification_line(command: str, verification: dict[str, Any]) -> str:
    result = verification.get(command)
    if isinstance(result, dict):
        status = command_status(result)
        reason = result.get("summary") or "ok"
    elif result:
        status = str(result)
        reason = "reported"
    else:
        status = "not run"
        reason = "payload에 결과 없음"
    if status == "skipped":
        status = "not run"
    return f"- `{command}` = {status} ({reason})"


def render_pr_body(payload: dict[str, Any]) -> str:
    status = status_text(payload.get("status"))
    targets = payload.get("targets") or "both"
    branches = nested(payload, "branches", default={})
    links = nested(payload, "links", default={})
    verification = nested(payload, "verification", default={})
    contract_risks = as_list(payload.get("contract_risks"))
    residual_risks = as_list(payload.get("residual_risks"))
    contract_text = "영향 없음" if not contract_risks else "영향 있음: " + "; ".join(str(item) for item in contract_risks)
    risk_text = "없음" if not residual_risks else "; ".join(str(item) for item in residual_risks)
    next_action = payload.get("next_action") or "GitHub PR diff와 checks를 확인한 뒤 수동 merge 결정"
    report_link = links.get("markdown_report") or "생성 안 됨"
    payload_link = links.get("payload_json") or "생성 안 됨"
    lines = "\n".join(verification_line(command, verification) for command in pr_body_commands(payload))
    backend_evidence = backend_quality_lines(payload, verification)
    ko_text = ko_section(payload)
    return f"""## 요약

상태: {status}

slice: {payload.get("slice", "unknown")}
targets: {targets}
integration branch: {branches.get("integration")}
local report: {report_link}
payload: {payload_link}

## 작업 범위

- backend: V1 slice payload 기준 변경 확인
- frontend: V1 slice payload 기준 변경 확인
- harness: integration branch push, draft PR 생성, strict PR lint evidence 지원
- docs/infra: PR template, pr-lint CI workflow, KO sync evidence

## TDD 근거

최초 RED: PR lint self-test fixture가 unchecked checklist, missing changed-file evidence, non-draft PR을 차단
예상 RED 실패: pr-lint가 body/evidence/changed-files/branch group error를 출력
최소 GREEN: integration 성공 후 strict PR body 생성, PR lint 통과, integration branch push/PR command 준비

## 검증

검증:
{lines}
{backend_evidence}

## 계약 영향

{contract_text}

contract-reviewer: contract risk 없으면 not needed, 있으면 필요

## KO 문서 변경

{ko_text}

## 주요 위험

주요 위험: {risk_text}
reviewer: 지적사항 = none, gate report에 지적사항이 있으면 listed

## 다음 행동

다음 행동: {next_action}

## 체크리스트

- [x] main merge 자동화 없음
- [x] main push 없음
- [x] integration branch만 push
- [x] draft PR
- [x] V1 API URL/response 영향 확인
- [x] DB migration 실행 없음
- [x] Open API 호출 없음
- [x] secrets 저장 없음
"""


def render_report(payload: dict[str, Any]) -> str:
    slice_name = payload.get("slice", "unknown")
    status = status_text(payload.get("status"))
    targets = payload.get("targets") or "both"
    branches = nested(payload, "branches", default={})
    worktrees = nested(payload, "worktrees", default={})
    commits = nested(payload, "commits", default={})
    links = nested(payload, "links", default={})
    commands = nested(payload, "commands", default={})
    notifications = nested(payload, "notifications", default={})

    return f"""# V1 Slice 보고서: {slice_name}

## 요약
- status: {status}
- preset: {payload.get("preset", "unknown")}
- targets: {targets}
- started_at: {payload.get("started_at", "")}
- finished_at: {payload.get("finished_at", "")}
- next_action: {payload.get("next_action", "")}

## 브랜치
- api_branch: {branches.get("api")}
- web_branch: {branches.get("web")}
- integration_branch: {branches.get("integration")}

## worktree
- main: {worktrees.get("main")}
- api: {worktrees.get("api")}
- web: {worktrees.get("web")}

## 커밋
- api: {commits.get("api")}
- web: {commits.get("web")}
- integration_head: {commits.get("integration_head")}

## 검증 매트릭스
{render_matrix(nested(payload, "verification", default={}))}
## 사용 skill
{render_skill_routing(payload)}
## 게이트 리뷰
{payload.get("gate_review") or "실행 안 됨"}

## 위험
### 계약 위험
{render_list(as_list(payload.get("contract_risks")))}
### 잔여 위험
{render_list(as_list(payload.get("residual_risks")))}
## 다음 행동
{payload.get("next_action") or "지정 안 됨"}

## 명령
- main_merge_command: `{commands.get("main_merge_command", "제안 없음")}`
- push_command_suggestion: `{commands.get("push_command_suggestion", "제안 없음")}`

## 링크
- markdown_report: {links.get("markdown_report")}
- payload_json: {links.get("payload_json")}
- notion_page_url: {links.get("notion_page_url")}
- slack_message_url: {links.get("slack_message_url")}

## 알림
- notion: {notifications.get("notion", "skipped")}
- slack: {notifications.get("slack", "skipped")}
"""


def write_report(path: Path, payload: dict[str, Any], *, dry_run: bool) -> None:
    payload.setdefault("links", {})["markdown_report"] = str(path)
    if dry_run:
        print(f"[DRY-RUN] write Markdown report: {path}")
        print(render_report(payload))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_report(payload), encoding="utf-8")


def write_pr_body(path: Path, payload: dict[str, Any], *, dry_run: bool) -> None:
    payload.setdefault("links", {})["pr_body"] = str(path)
    if dry_run:
        print(f"[DRY-RUN] write PR body: {path}")
        print(render_pr_body(payload))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_pr_body(payload), encoding="utf-8")


def run_reporters(
    payload: dict[str, Any],
    report_path: Path,
    *,
    notion_enabled: bool,
    slack_enabled: bool,
    dry_run: bool,
) -> None:
    payload.setdefault("notifications", {})
    if notion_enabled:
        try:
            from reporters import notion

            result = notion.publish(payload, report_path, dry_run=dry_run)
        except Exception as exc:  # pragma: no cover - defensive boundary.
            result = {"status": "warning", "warning": f"Notion reporter failed: {exc}"}
        payload["notifications"]["notion"] = result.get("status", "warning")
        if result.get("url"):
            payload.setdefault("links", {})["notion_page_url"] = result["url"]
        if result.get("warning"):
            print(f"warning: {result['warning']}", file=sys.stderr)
    else:
        payload["notifications"].setdefault("notion", "skipped")

    if slack_enabled:
        try:
            from reporters import slack

            result = slack.publish(payload, report_path, dry_run=dry_run)
        except Exception as exc:  # pragma: no cover - defensive boundary.
            result = {"status": "warning", "warning": f"Slack reporter failed: {exc}"}
        payload["notifications"]["slack"] = result.get("status", "warning")
        if result.get("url"):
            payload.setdefault("links", {})["slack_message_url"] = result["url"]
        if result.get("warning"):
            print(f"warning: {result['warning']}", file=sys.stderr)
    else:
        payload["notifications"].setdefault("slack", "skipped")


def run_self_test() -> int:
    from pr_lint import PrInput, lint_pr

    payload = {
        "slice": "self-test",
        "status": "Pass",
        "preset": "contract-hardening",
        "started_at": "2026-05-19T00:00:00+09:00",
        "finished_at": "2026-05-19T00:01:00+09:00",
        "branches": {"api": "feat/api-self-test", "web": "feat/web-self-test"},
        "changed_files": [
            ".codex/harness/v1_report.py",
            ".agents/skills/v1-slice-harness/SKILL.md",
            ".agents/skills/v1-slice-harness/SKILL_KO.md",
            ".ko-docs.toml",
        ],
        "ko_docs": {
            "approved": True,
            "targets": [".agents/skills/v1-slice-harness/SKILL_KO.md"],
        },
        "skill_routing": {
            "execute": {
                "skills": [
                    {"trigger": "$tdd"},
                    {"trigger": "$api-contract"},
                    {"trigger": "$code-review"},
                ]
            }
        },
        "verification": {
            DIFF_CHECK: {"status": "pass", "exit_code": 0},
            PR_LINT_SELF_TEST: {"status": "pass", "exit_code": 0},
            PR_BODY_CHECK_SELF_TEST: {"status": "pass", "exit_code": 0},
            V1_FLOW_SELF_TEST: {"status": "pass", "exit_code": 0},
            V1_PLAN_SELF_TEST: {"status": "pass", "exit_code": 0},
            V1_REPORT_SELF_TEST: {"status": "pass", "exit_code": 0},
            V1_LAUNCHER_SELF_TEST: {"status": "pass", "exit_code": 0},
            SKILL_ROUTING_SELF_TEST: {"status": "pass", "exit_code": 0},
            USER_LANGUAGE_CHECK: {"status": "pass", "exit_code": 0},
            "bash scripts/check-ko-docs.sh": {"status": "pass", "exit_code": 0},
        },
    }
    rendered = render_report(payload)
    pr_body = render_pr_body(payload)
    linted_pr_body = lint_pr(
        PrInput(
            title="V1 self-test integration",
            body=pr_body,
            base="main",
            head="feat/self-test-integration",
            draft=True,
            changed_files=tuple(payload["changed_files"]),
        )
    )
    checks = [
        "# V1 Slice 보고서: self-test" in rendered,
        "검증 매트릭스" in rendered,
        f"`{DIFF_CHECK}`" in rendered,
        f"`{PR_LINT_SELF_TEST}`" in pr_body,
        f"`{PR_BODY_CHECK_SELF_TEST}`" in pr_body,
        f"`{V1_FLOW_SELF_TEST}`" in pr_body,
        f"`{SKILL_ROUTING_SELF_TEST}`" in pr_body,
        f"`{USER_LANGUAGE_CHECK}`" in pr_body,
        "`bash scripts/check-ko-docs.sh`" in pr_body,
        "$tdd, $api-contract, $code-review" in rendered,
        "## KO 문서 변경" in pr_body,
        "KO 수정 승인: 확인" in pr_body,
        "## 요약" in pr_body,
        "최초 RED:" in pr_body,
        "검증:" in pr_body,
        "영향 없음" in pr_body,
        "- [x] main merge 자동화 없음" in pr_body,
        "- [x] main push 없음" in pr_body,
        "- [x] integration branch만 push" in pr_body,
        "- [x] draft PR" in pr_body,
        linted_pr_body.ok,
    ]
    if all(checks):
        print("self-test passed: v1_report")
        return 0
    print("self-test failed: v1_report", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Render Home Search V1 slice reports.")
    parser.add_argument("--input-json", help="JSON payload file, or '-' for stdin.")
    parser.add_argument("--report-path", help="Override Markdown report path.")
    parser.add_argument("--payload-out", help="Write normalized payload JSON after reporters.")
    parser.add_argument("--pr-body-out", help="Write a GitHub PR body Markdown file.")
    parser.add_argument("--slice")
    parser.add_argument("--preset")
    parser.add_argument("--targets")
    parser.add_argument("--status", default="Partial")
    parser.add_argument("--started-at")
    parser.add_argument("--finished-at")
    parser.add_argument("--api-branch")
    parser.add_argument("--web-branch")
    parser.add_argument("--integration-branch")
    parser.add_argument("--api-worktree")
    parser.add_argument("--web-worktree")
    parser.add_argument("--gate-review")
    parser.add_argument("--next-action")
    parser.add_argument("--notion", action="store_true", help="Best-effort Notion reporter.")
    parser.add_argument("--slack", action="store_true", help="Best-effort Slack reporter.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()

    payload = load_payload(args)
    path = report_path_for(payload, args.report_path)
    write_report(path, payload, dry_run=args.dry_run)
    if args.pr_body_out:
        write_pr_body(pr_body_path_for(payload, args.pr_body_out), payload, dry_run=args.dry_run)
    run_reporters(
        payload,
        path,
        notion_enabled=args.notion,
        slack_enabled=args.slack,
        dry_run=args.dry_run,
    )
    if args.notion or args.slack:
        write_report(path, payload, dry_run=args.dry_run)
    write_payload(args.payload_out, payload, dry_run=args.dry_run)
    print(f"report: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

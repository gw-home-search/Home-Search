#!/usr/bin/env python3
"""Compatibility wrapper for Home Search PR body linting."""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from pathlib import Path

from pr_lint import check_body as strict_check_body


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)


@dataclass(frozen=True)
class CheckResult:
    ok: bool
    errors: list[str]


def read_body(args: argparse.Namespace) -> str:
    if bool(args.body_file) == bool(args.body_env):
        raise ValueError("--body-file 또는 --body-env 중 하나만 지정하세요")
    if args.body_file:
        return Path(args.body_file).read_text(encoding="utf-8")
    value = os.environ.get(args.body_env or "")
    if value is None:
        raise ValueError(f"환경 변수를 찾을 수 없습니다: {args.body_env}")
    return value


def check_body(body: str) -> CheckResult:
    result = strict_check_body(body)
    return CheckResult(ok=result.ok, errors=result.errors)


def format_errors(errors: list[str]) -> str:
    return "\n".join(f"- {error}" for error in errors)


def valid_sample() -> str:
    return """## 요약

상태: Pass
이번 PR은 Home Search harness PR body evidence 검사를 정리합니다.

## 작업 범위

- backend: 없음
- frontend: 없음
- harness: PR lint wrapper와 body evidence 검사
- docs/infra: PR template과 CI evidence 확인

## 사용 skill

| phase | skill | role | path | required evidence |
| --- | --- | --- | --- | --- |
| execute | home-search-harness | orchestrator | .codex/harness/home | 상태; 검증; 다음 행동 |
| execute | $tdd | primary | .agents/skills/tdd/SKILL.md | 최초 RED; 예상 RED 실패; 최소 GREEN |
| execute | $api-contract | checkpoint | .agents/skills/api-contract/SKILL.md | 계약 영향 |
| gate | $code-review | primary | .agents/skills/code-review/SKILL.md | reviewer: 지적사항 |

## TDD 근거

최초 RED: unchecked checklist fixture fails strict PR lint
예상 RED 실패: body group reports unchecked checklist item
최소 GREEN: checked draft PR body fixture passes strict body lint

## 검증

검증:
- `git diff --check` = pass (정상)

## 계약 영향

영향 없음

contract-reviewer: not needed

## 주요 위험

주요 위험: 없음
reviewer: 지적사항 = none

## 다음 행동

다음 행동: GitHub PR diff와 checks를 확인한 뒤 수동 merge를 결정합니다.

## 체크리스트

- [x] main merge 자동화 없음
- [x] main push 없음
- [x] integration branch만 push
- [x] draft PR
- [x] public API URL/response 영향 확인
- [x] DB migration 실행 없음
- [x] Open API 호출 없음
- [x] secrets 저장 없음
"""


def run_self_test() -> int:
    passing = check_body(valid_sample())
    empty = check_body("")
    unchecked = check_body(valid_sample().replace("- [x] draft PR", "- [ ] draft PR"))
    missing_diff = check_body(valid_sample().replace("- `git diff --check` = pass (정상)\n", ""))
    pass_with_risk = check_body(valid_sample().replace("주요 위험: 없음", "주요 위험: 미확인 risk"))
    checks = [
        passing.ok,
        not empty.ok,
        any("empty" in error or "비어" in error for error in empty.errors),
        not unchecked.ok,
        any("draft PR" in error for error in unchecked.errors),
        not missing_diff.ok,
        any("git diff --check" in error for error in missing_diff.errors),
        not pass_with_risk.ok,
        any("미확인" in error for error in pass_with_risk.errors),
    ]
    if all(checks):
        print("self-test passed: pr_body_check")
        return 0
    print("self-test failed: pr_body_check", file=sys.stderr)
    if not passing.ok:
        print(format_errors(passing.errors), file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check Home Search PR body evidence.")
    parser.add_argument("--body-file", help="PR body Markdown file.")
    parser.add_argument("--body-env", help="Environment variable containing the PR body.")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    try:
        body = read_body(args)
    except (OSError, ValueError) as exc:
        print(f"PR body 검사 실패: {exc}", file=sys.stderr)
        return 2
    result = check_body(body)
    if result.ok:
        print("PR body 검사 통과")
        return 0
    print("PR body 검사 실패", file=sys.stderr)
    print(format_errors(result.errors), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

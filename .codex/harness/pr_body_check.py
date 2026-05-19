#!/usr/bin/env python3
"""Validate Home Search PR bodies for Korean evidence sections."""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

REQUIRED_SECTIONS = [
    "요약",
    "작업 범위",
    "TDD Evidence",
    "검증",
    "Contract 영향",
    "주요 위험",
    "다음 행동",
    "체크리스트",
]
EVIDENCE_LABELS = [
    "First RED:",
    "Expected RED failure:",
    "Minimum GREEN:",
]
VERIFICATION_COMMANDS = [
    "cd apps/api && ./gradlew test",
    "cd apps/web && npm run test",
    "cd apps/web && npm run build",
    "git diff --check",
]
MIN_NONSPACE_LENGTH = 120
MIN_HANGUL_COUNT = 20
MIN_HANGUL_RATIO = 0.08


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


def has_section(body: str, section: str) -> bool:
    pattern = re.compile(rf"^\s{{0,3}}#{{1,6}}\s+{re.escape(section)}\s*$", re.MULTILINE)
    return pattern.search(body) is not None


def hangul_count(text: str) -> int:
    return len(re.findall(r"[가-힣]", text))


def nonspace_length(text: str) -> int:
    return len(re.sub(r"\s+", "", text))


def has_verification_command(body: str) -> bool:
    return any(command in body for command in VERIFICATION_COMMANDS)


def has_verification_status(body: str) -> bool:
    return re.search(r"=\s*(pass|fail|not run)\b", body, re.IGNORECASE) is not None


def check_body(body: str) -> CheckResult:
    errors: list[str] = []
    stripped = body.strip()
    compact_length = nonspace_length(stripped)
    hangul = hangul_count(stripped)
    ratio = hangul / max(compact_length, 1)

    if not stripped:
        errors.append("PR 본문이 비어 있습니다.")
    if compact_length < MIN_NONSPACE_LENGTH:
        errors.append("PR 본문이 너무 짧습니다.")
    if hangul < MIN_HANGUL_COUNT or ratio < MIN_HANGUL_RATIO:
        errors.append("한국어 본문 비중이 부족합니다.")

    for section in REQUIRED_SECTIONS:
        if not has_section(body, section):
            errors.append(f"필수 섹션 누락: {section}")

    for label in EVIDENCE_LABELS:
        if label not in body:
            errors.append(f"evidence label 누락: {label}")

    if "검증:" not in body:
        errors.append("검증 label이 필요합니다.")
    if not has_verification_command(body):
        errors.append("검증 command가 필요합니다.")
    if not has_verification_status(body):
        errors.append("검증 결과는 pass/fail/not run 중 하나여야 합니다.")

    if not re.search(r"영향\s+없음|영향\s+있음\s*:", body):
        errors.append("Contract 영향은 '영향 없음' 또는 '영향 있음:'으로 작성하세요.")
    if "주요 위험:" not in body:
        errors.append("주요 위험 label이 필요합니다.")
    if "다음 행동:" not in body:
        errors.append("다음 행동 label이 필요합니다.")

    return CheckResult(ok=not errors, errors=errors)


def format_errors(errors: list[str]) -> str:
    return "\n".join(f"- {error}" for error in errors)


def valid_sample() -> str:
    return """## 요약

상태: Pass
이번 PR은 V1 harness의 integration branch push와 draft PR 생성을 추가합니다.

## 작업 범위

- backend: 없음
- frontend: 없음
- harness: PR 생성과 PR body 검사
- docs/infra: GitHub PR template과 CI workflow

## TDD Evidence

First RED: `.codex/harness/v1 dry map-contract-hardening --pr` 옵션 미지원 실패
Expected RED failure: argparse가 `--pr` 옵션을 인식하지 못함
Minimum GREEN: dry-run에서 PR body 생성과 PR command 출력 확인

## 검증

검증:
- `cd apps/api && ./gradlew test` = not run (harness only)
- `cd apps/web && npm run test` = not run (harness only)
- `cd apps/web && npm run build` = not run (harness only)
- `git diff --check` = pass

## Contract 영향

영향 없음

contract-reviewer: not needed

## 주요 위험

주요 위험: gh 인증이 없으면 자동 PR 생성이 중단됩니다.
reviewer: Findings = none

## 다음 행동

다음 행동: GitHub PR diff와 checks를 확인한 뒤 수동 merge를 결정합니다.

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


def run_self_test() -> int:
    passing = check_body(valid_sample())
    empty = check_body("")
    english_only = check_body(
        """## Summary

Status: Pass
This pull request adds draft pull request automation and evidence checks.

## Scope

- harness: pull request automation

## Evidence

First RED: missing option
Expected RED failure: parser error
Minimum GREEN: dry-run command succeeds

Validation:
- `git diff --check` = pass (ok)
"""
    )
    missing_evidence = check_body(valid_sample().replace("First RED:", "First:"))
    checks = [
        passing.ok,
        not empty.ok,
        "PR 본문이 비어 있습니다." in empty.errors,
        not english_only.ok,
        any("한국어" in error for error in english_only.errors),
        not missing_evidence.ok,
        any("First RED:" in error for error in missing_evidence.errors),
    ]
    if all(checks):
        print("self-test passed: pr_body_check")
        return 0
    print("self-test failed: pr_body_check", file=sys.stderr)
    if not passing.ok:
        print(format_errors(passing.errors), file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check Korean Home Search PR body evidence.")
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

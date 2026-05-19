#!/usr/bin/env python3
"""PostToolUse feedback for failed verification commands.

The hook summarizes only selected test/build/check failures. It does not retry,
fix, or roll back anything.
"""

from __future__ import annotations

import json
import re
import sys
from typing import Any


WATCHED_COMMANDS = (
    re.compile(r"(^|\s)(?:\./)?gradlew\s+test(\s|$)"),
    re.compile(r"(^|\s)(?:\./)?gradlew\s+verify(\s|$)"),
    re.compile(r"(^|\s)npm\s+run\s+test(\s|$)"),
    re.compile(r"(^|\s)npm\s+run\s+build(\s|$)"),
    re.compile(r"(^|\s)git\s+diff\s+--check(\s|$)"),
    re.compile(r"(^|\s)bash\s+scripts/check-ko-docs\.sh(\s|$)"),
)


def load_payload() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, sort_keys=True)


def collect(value: Any, key_names: set[str]) -> list[Any]:
    found: list[Any] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in key_names:
                found.append(item)
            found.extend(collect(item, key_names))
    elif isinstance(value, list):
        for item in value:
            found.extend(collect(item, key_names))
    return found


def first_text(payload: dict[str, Any], keys: set[str]) -> str:
    for value in collect(payload, keys):
        text = as_text(value)
        if text:
            return text
    return ""


def command_from_payload(payload: dict[str, Any]) -> str:
    tool_input = payload.get("tool_input") or payload.get("toolInput") or {}
    if isinstance(tool_input, dict):
        for key in ("command", "cmd", "script"):
            if isinstance(tool_input.get(key), str):
                return tool_input[key]
    return first_text(payload, {"command", "cmd"})


def exit_code_from_payload(payload: dict[str, Any]) -> int | None:
    for value in collect(payload, {"exit_code", "exitCode", "status"}):
        if isinstance(value, bool):
            continue
        if isinstance(value, int):
            return value
        if isinstance(value, str) and value.strip().lstrip("-").isdigit():
            return int(value.strip())
    return None


def watched(command: str) -> bool:
    return any(pattern.search(command) for pattern in WATCHED_COMMANDS)


def output_summary(payload: dict[str, Any]) -> list[str]:
    text = "\n".join(
        part
        for part in (
            first_text(payload, {"stderr"}),
            first_text(payload, {"stdout"}),
            first_text(payload, {"output", "aggregated_output"}),
        )
        if part
    )
    lines = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line:
            lines.append(line)
        if len(lines) == 3:
            break
    return lines


def block(reason: str) -> None:
    print(json.dumps({"decision": "block", "reason": reason}, ensure_ascii=False))
    raise SystemExit(0)


def review_decision(payload: dict[str, Any]) -> str | None:
    command = command_from_payload(payload)
    if not command or not watched(command):
        return None

    exit_code = exit_code_from_payload(payload)
    if exit_code in (None, 0):
        return None

    lines = output_summary(payload)
    summary = "\n".join(f"- {line}" for line in lines) if lines else "- 캡처된 output 없음"
    return (
        "검증 명령이 실패했습니다.\n"
        f"명령: {command}\n"
        f"exit code: {exit_code}\n"
        f"요약:\n{summary}\n"
        "다음 행동: 계속 구현하지 말고 실패 원인을 먼저 확인하세요."
    )


def run_self_test() -> int:
    npm_failure = {
        "tool_input": {"cmd": "npm run test"},
        "exit_code": 1,
        "stderr": "failed test line\nsecond line",
    }
    gradle_failure = {
        "tool_input": {"cmd": "./gradlew test"},
        "exit_code": 1,
        "stdout": "Tests failed",
    }
    rg_failure = {
        "tool_input": {"cmd": "rg missing"},
        "exit_code": 1,
        "stderr": "no matches",
    }
    npm_success = {
        "tool_input": {"cmd": "npm run build"},
        "exit_code": 0,
        "stdout": "built",
    }
    tests = [
        ("npm failure is summarized", review_decision(npm_failure) is not None),
        ("gradle failure is summarized", review_decision(gradle_failure) is not None),
        ("unwatched rg failure is ignored", review_decision(rg_failure) is None),
        ("watched success is ignored", review_decision(npm_success) is None),
    ]
    failed = [name for name, passed in tests if not passed]
    if failed:
        print("self-test failed:")
        for name in failed:
            print(f"- {name}")
        return 1
    print("self-test passed: post_tool_use_review")
    return 0


def main() -> None:
    payload = load_payload()
    decision = review_decision(payload)
    if decision:
        block(decision)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    main()

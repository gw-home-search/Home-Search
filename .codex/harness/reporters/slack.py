"""Best-effort Slack reporter for V1 harness reports."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import urllib.error
import urllib.request
from contextlib import suppress
from pathlib import Path
from typing import Any


def message_for(payload: dict[str, Any], report_path: Path) -> str:
    branches = payload.get("branches", {})
    verification = payload.get("verification", {})
    tests = []
    for command, result in verification.items():
        status = result.get("status", "skipped") if isinstance(result, dict) else result
        tests.append(f"{command}={status}")
    report_link = payload.get("links", {}).get("notion_page_url") or str(report_path)
    return "\n".join(
        [
            f"Home Search V1 slice: {payload.get('slice')}",
            f"상태: {payload.get('status')}",
            f"integration branch: {branches.get('integration')}",
            f"검증: {', '.join(tests) if tests else 'not run'}",
            f"보고서: {report_link}",
            f"다음 행동: {payload.get('next_action')}",
        ]
    )


def _send_webhook(webhook_url: str, text: str) -> dict[str, str | None]:
    request = urllib.request.Request(
        webhook_url,
        data=json.dumps({"text": text}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            if 200 <= response.status < 300:
                return {"status": "sent", "url": None, "warning": None}
            return {"status": "warning", "url": None, "warning": f"Slack webhook status {response.status}"}
    except (urllib.error.URLError, OSError) as exc:
        return {"status": "warning", "url": None, "warning": f"Slack webhook 실패: {exc}"}


def _send_mcp(text: str) -> dict[str, str | None]:
    channel = os.environ.get("SLACK_CHANNEL_ID") or os.environ.get("SLACK_CHANNEL")
    if not channel:
        return {"status": "skipped", "url": None, "warning": None}
    codex_bin = os.environ.get("CODEX_BIN", "codex")
    prompt = f"""Send this short Home Search report only if Slack MCP is available.

Do not read env files or print secrets. Use MCP auth only.
If Slack MCP is unavailable, answer exactly: SLACK_SKIPPED.
Channel: {channel}
Message:
{text}

After sending, print a Slack message URL if one is available; otherwise print SLACK_SENT.
"""
    with tempfile.NamedTemporaryFile("w+", encoding="utf-8", delete=False) as handle:
        output_path = Path(handle.name)
    try:
        result = subprocess.run(
            [
                codex_bin,
                "exec",
                "--sandbox",
                "read-only",
                "--output-last-message",
                str(output_path),
                prompt,
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"status": "warning", "url": None, "warning": f"Slack MCP reporter 사용 불가: {exc}"}

    try:
        message = output_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        message = ""
    finally:
        with suppress(OSError):
            output_path.unlink()

    if result.returncode != 0:
        return {"status": "warning", "url": None, "warning": "Slack MCP reporter command 실패"}
    if "SLACK_SKIPPED" in message:
        return {"status": "skipped", "url": None, "warning": None}
    url = next((part for part in message.split() if part.startswith("http")), None)
    return {"status": "sent", "url": url, "warning": None}


def publish(payload: dict[str, Any], report_path: Path, *, dry_run: bool) -> dict[str, str | None]:
    text = message_for(payload, report_path)
    if dry_run:
        print("[DRY-RUN] Slack reporter: Slack MCP 또는 webhook이 설정되어 있으면 message를 보냅니다")
        print(text)
        return {"status": "skipped", "url": None, "warning": None}

    webhook_url = os.environ.get("SLACK_WEBHOOK_URL")
    if webhook_url:
        return _send_webhook(webhook_url, text)
    return _send_mcp(text)

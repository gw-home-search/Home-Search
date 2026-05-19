"""Best-effort Notion reporter for V1 harness reports."""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from contextlib import suppress
from pathlib import Path
from typing import Any


URL_RE = re.compile(r"https?://\S*notion\S*", re.IGNORECASE)


def _summary(payload: dict[str, Any]) -> str:
    branches = payload.get("branches", {})
    verification = payload.get("verification", {})
    lines = [
        f"Slice: {payload.get('slice')}",
        f"Status: {payload.get('status')}",
        f"Integration: {branches.get('integration')}",
        "Verification:",
    ]
    for command, result in verification.items():
        if isinstance(result, dict):
            lines.append(f"- {command}: {result.get('status', 'skipped')}")
        else:
            lines.append(f"- {command}: {result}")
    lines.append(f"Next: {payload.get('next_action')}")
    return "\n".join(lines)


def _prompt(payload: dict[str, Any], report_path: Path) -> str:
    title = f"Home Search V1 Slice Report - {payload.get('slice')}"
    parent_page = os.environ.get("NOTION_PARENT_PAGE_ID")
    data_source = os.environ.get("NOTION_DATA_SOURCE_ID")
    target = "standalone private page"
    if data_source:
        target = f"data_source_id={data_source}"
    elif parent_page:
        target = f"page_id={parent_page}"
    return f"""Create a Notion report page only if the Notion MCP is available.

Do not read env files or print secrets. Use MCP auth only.
If Notion MCP is unavailable, answer exactly: NOTION_SKIPPED.

Title: {title}
Target: {target}
Local Markdown report path: {report_path}

Create sections:
- Summary
- Branches
- Commits
- Verification Matrix
- Gate Review
- Risks
- Next Action

Follow the Notion MCP create-page instructions, including fetching the enhanced
Markdown spec before creating the page if the tool requires it.

Report data:
{_summary(payload)}

After creating the page, print only the Notion page URL on its own line.
"""


def publish(payload: dict[str, Any], report_path: Path, *, dry_run: bool) -> dict[str, str | None]:
    if dry_run:
        print("[DRY-RUN] Notion reporter: would probe Notion MCP and create a page if available")
        print(f"[DRY-RUN] Notion title: Home Search V1 Slice Report - {payload.get('slice')}")
        return {"status": "skipped", "url": None, "warning": None}

    codex_bin = os.environ.get("CODEX_BIN", "codex")
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
                _prompt(payload, report_path),
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"status": "warning", "url": None, "warning": f"Notion reporter unavailable: {exc}"}

    message = ""
    try:
        message = output_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        pass
    finally:
        with suppress(OSError):
            output_path.unlink()

    if result.returncode != 0:
        return {"status": "warning", "url": None, "warning": "Notion reporter command failed"}
    if "NOTION_SKIPPED" in message:
        return {"status": "skipped", "url": None, "warning": None}
    match = URL_RE.search(message)
    if not match:
        return {"status": "warning", "url": None, "warning": "Notion reporter returned no page URL"}
    return {"status": "sent", "url": match.group(0).rstrip(").,"), "warning": None}

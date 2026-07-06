#!/usr/bin/env python3
"""Shared payload and git helpers for Home Search hooks."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

FALLBACK_REPO_ROOT = Path("/Users/gwongwangjae/home-search")


def load_payload() -> dict[str, Any]:
    try:
        raw = sys.stdin.read()
    except OSError:
        return {}
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


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

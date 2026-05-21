#!/usr/bin/env python3
"""Shared PR input context helpers for CI and local harness publishing."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)


@dataclass(frozen=True)
class PrContext:
    title: str
    body: str
    base: str
    head: str
    draft: bool
    changed_files: tuple[str, ...] = ()


def read_body_file(path: str | Path) -> str:
    return Path(path).read_text(encoding="utf-8")


def read_body_env(name: str) -> str:
    value = os.environ.get(name)
    if value is None:
        raise ValueError(f"환경 변수를 찾을 수 없습니다: {name}")
    return value


def body_from_sources(body_file: str | None, body_env: str | None) -> str:
    if bool(body_file) == bool(body_env):
        raise ValueError("--body-file 또는 --body-env 중 하나만 지정하세요")
    if body_file:
        return read_body_file(body_file)
    return read_body_env(str(body_env))


def read_changed_files_nul(path: str | None) -> tuple[str, ...]:
    if not path:
        return ()
    raw = Path(path).read_bytes()
    return tuple(part.decode("utf-8") for part in raw.split(b"\0") if part)


def read_changed_files_text(path: str | None) -> tuple[str, ...]:
    if not path:
        return ()
    return tuple(line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip())


def changed_files_from_sources(changed_files_nul: str | None, changed_files_file: str | None) -> tuple[str, ...]:
    return read_changed_files_nul(changed_files_nul) + read_changed_files_text(changed_files_file)


def changed_files_for_branch(repo: Path, base: str, head: str) -> tuple[str, ...]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", f"{base}...{head}"],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        message = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
        raise ValueError(f"changed file 목록을 만들 수 없습니다: {message}")
    return tuple(part.decode("utf-8") for part in result.stdout.split(b"\0") if part)


def context_from_event(event_path: str, changed_files: tuple[str, ...]) -> PrContext:
    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    pr = event.get("pull_request")
    if not isinstance(pr, dict):
        raise ValueError("event JSON에 pull_request가 없습니다")
    base = pr.get("base") if isinstance(pr.get("base"), dict) else {}
    head = pr.get("head") if isinstance(pr.get("head"), dict) else {}
    return PrContext(
        title=str(pr.get("title") or ""),
        body=str(pr.get("body") or ""),
        base=str(base.get("ref") or event.get("base_ref") or ""),
        head=str(head.get("ref") or ""),
        draft=bool(pr.get("draft")),
        changed_files=changed_files,
    )


def context_from_local(
    *,
    title: str | None,
    base: str | None,
    head: str | None,
    draft: bool | None,
    body_file: str | None,
    body_env: str | None,
    changed_files: tuple[str, ...] = (),
) -> PrContext:
    missing = [name for name, value in (("title", title), ("base", base), ("head", head)) if not value]
    if draft is None:
        missing.append("draft/no-draft")
    if missing:
        raise ValueError("필수 인자가 없습니다: " + ", ".join(missing))
    return PrContext(
        title=str(title),
        body=body_from_sources(body_file, body_env),
        base=str(base),
        head=str(head),
        draft=bool(draft),
        changed_files=changed_files,
    )


def run_self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        body_path = tmp_path / "body.md"
        body_path.write_text("body", encoding="utf-8")
        text_path = tmp_path / "changed.txt"
        text_path.write_text("a.txt\nb.txt\n", encoding="utf-8")
        nul_path = tmp_path / "changed.nul"
        nul_path.write_bytes(b"c.txt\0d.txt\0")
        event_path = tmp_path / "event.json"
        event_path.write_text(
            json.dumps(
                {
                    "pull_request": {
                        "title": "[Chore] 공용 PR 입력",
                        "body": "event body",
                        "draft": True,
                        "base": {"ref": "main"},
                        "head": {"ref": "feat/shared-pr-input-integration"},
                    }
                }
            ),
            encoding="utf-8",
        )
        local = context_from_local(
            title="[Chore] 공용 PR 입력",
            base="main",
            head="feat/shared-pr-input-integration",
            draft=True,
            body_file=str(body_path),
            body_env=None,
            changed_files=changed_files_from_sources(str(nul_path), str(text_path)),
        )
        event = context_from_event(str(event_path), ("workflow.yml",))
        checks = [
            local.body == "body",
            local.changed_files == ("c.txt", "d.txt", "a.txt", "b.txt"),
            event.base == "main",
            event.head == "feat/shared-pr-input-integration",
            event.draft is True,
        ]
    if all(checks):
        print("self-test passed: pr_context")
        return 0
    print("self-test failed: pr_context", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check shared PR context helpers.")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return run_self_test()
    print("pr_context는 단독 실행 동작이 없습니다. --self-test를 사용하세요.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

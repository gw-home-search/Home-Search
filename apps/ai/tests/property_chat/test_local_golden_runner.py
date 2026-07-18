from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Callable


AI_ROOT = Path(__file__).resolve().parents[2]
RUNNER = AI_ROOT / "ops" / "run-local-property-golden.sh"


def write_vars(path: Path, *, include_provider: bool = True) -> None:
    lines = [
        "HOME_AI_PROPERTY_DSN=postgresql://home_search_ai_reader:p%40ss@postgis:5432/home_search",
    ]
    if include_provider:
        lines.extend(
            [
                "HOME_AI_OPENAI_API_KEY=test-provider-secret",
                "HOME_AI_OPENAI_PRIMARY_MODEL=gpt-5.6-luna",
                "HOME_AI_OPENAI_SECONDARY_MODEL=gpt-5.6-terra",
                "HOME_AI_OPENAI_TIMEOUT_SECONDS=15",
            ]
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path.chmod(0o600)


def write_fake_uv(path: Path) -> None:
    path.write_text(
        """#!/bin/sh
set -eu
test "$PGPASSWORD" = 'p@ss'
test "$HOME_AI_PROPERTY_DSN" = 'host=127.0.0.1 port=15432 dbname=home_search user=home_search_ai_reader'
case "$*" in
  'run home-ai-property-golden --mode offline')
    test -z "${HOME_AI_OPENAI_API_KEY:-}"
    printf offline >"$FAKE_UV_MARKER"
    ;;
  'run home-ai-property-golden --mode live --case-id complex-identity-jamsil-ells')
    test "$HOME_AI_OPENAI_API_KEY" = 'test-provider-secret'
    test "$HOME_AI_OPENAI_PRIMARY_MODEL" = 'gpt-5.6-luna'
    test "$HOME_AI_OPENAI_SECONDARY_MODEL" = 'gpt-5.6-terra'
    test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '15'
    test "$HOME_AI_GOLDEN_LIVE_CONFIRM" = 'RUN_ONE_LIVE_GOLDEN_CASE'
    printf live >"$FAKE_UV_MARKER"
    ;;
  *) exit 91 ;;
esac
""",
        encoding="utf-8",
    )
    path.chmod(0o700)


def run_runner(
    tmp_path: Path,
    mode: str,
    *,
    confirmation: str = "",
    mutate_vars: Callable[[Path], None] | None = None,
) -> subprocess.CompletedProcess[str]:
    vars_file = tmp_path / "ai.env"
    fake_uv = tmp_path / "uv"
    marker = tmp_path / "called"
    write_vars(vars_file)
    if mutate_vars is not None:
        mutate_vars(vars_file)
    write_fake_uv(fake_uv)
    environment = os.environ.copy()
    environment.update(
        {
            "PATH": f"{tmp_path}{os.pathsep}{environment['PATH']}",
            "FAKE_UV_MARKER": str(marker),
            "HOME_AI_GOLDEN_LIVE_CONFIRM": confirmation,
        }
    )
    result = subprocess.run(
        [str(RUNNER), mode, str(vars_file)],
        cwd=AI_ROOT,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )
    result.marker = marker  # type: ignore[attr-defined]
    return result


def test_offline_runner_passes_only_property_credentials(tmp_path: Path) -> None:
    result = run_runner(tmp_path, "offline")

    assert result.returncode == 0, result.stderr
    assert result.marker.read_text(encoding="utf-8") == "offline"  # type: ignore[attr-defined]
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr


def test_live_runner_is_fixed_to_one_case_and_passes_provider_settings(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert result.marker.read_text(encoding="utf-8") == "live"  # type: ignore[attr-defined]
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr


def test_live_runner_rejects_missing_confirmation_without_invoking_uv(
    tmp_path: Path,
) -> None:
    result = run_runner(tmp_path, "live")

    assert result.returncode == 1
    assert "live 골든 확인값" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]
    assert "test-provider-secret" not in result.stdout + result.stderr


def test_runner_rejects_group_readable_vars_without_invoking_uv(
    tmp_path: Path,
) -> None:
    def make_group_readable(path: Path) -> None:
        path.chmod(0o640)

    result = run_runner(tmp_path, "offline", mutate_vars=make_group_readable)

    assert result.returncode == 1
    assert "group/other 권한" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]
    assert "test-provider-secret" not in result.stdout + result.stderr


def test_runner_rejects_unapproved_property_dsn_without_leaking_it(
    tmp_path: Path,
) -> None:
    rejected_password = "must-not-leak"

    def replace_dsn(path: Path) -> None:
        contents = path.read_text(encoding="utf-8")
        path.write_text(
            contents.replace(
                "postgresql://home_search_ai_reader:p%40ss@postgis:5432/home_search",
                f"postgresql://home_search:{rejected_password}@postgis:5432/home_search",
            ),
            encoding="utf-8",
        )

    result = run_runner(tmp_path, "offline", mutate_vars=replace_dsn)

    assert result.returncode == 1
    assert "승인된 local AI reader 연결 형식" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]
    assert rejected_password not in result.stdout + result.stderr


def test_live_runner_rejects_duplicate_provider_key_without_leaking_it(
    tmp_path: Path,
) -> None:
    duplicate_secret = "duplicate-must-not-leak"

    def append_duplicate_key(path: Path) -> None:
        with path.open("a", encoding="utf-8") as vars_file:
            vars_file.write(f"HOME_AI_OPENAI_API_KEY={duplicate_secret}\n")

    result = run_runner(
        tmp_path,
        "live",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
        mutate_vars=append_duplicate_key,
    )

    assert result.returncode == 1
    assert "정확히 한 번 정의" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]
    assert duplicate_secret not in result.stdout + result.stderr

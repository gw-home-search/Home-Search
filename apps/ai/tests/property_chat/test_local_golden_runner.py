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
        "HOME_AI_REFERENCE_DSN=postgresql://home_search_ai_runtime:r%40ss@postgis:5432/home_search_ai",
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
case "$*" in
  'run home-ai-property-golden --mode offline')
    test "$PGPASSWORD" = 'p@ss'
    test "$HOME_AI_PROPERTY_DSN" = 'host=127.0.0.1 port=15432 dbname=home_search user=home_search_ai_reader'
    test -z "${HOME_AI_OPENAI_API_KEY:-}"
    printf offline >"$FAKE_UV_MARKER"
    ;;
  'run home-ai-property-golden --mode live --case-id complex-identity-jamsil-ells')
    test "$PGPASSWORD" = 'p@ss'
    test "$HOME_AI_PROPERTY_DSN" = 'host=127.0.0.1 port=15432 dbname=home_search user=home_search_ai_reader'
    test "$HOME_AI_OPENAI_API_KEY" = 'test-provider-secret'
    test "$HOME_AI_OPENAI_PRIMARY_MODEL" = 'gpt-5.6-luna'
    test "$HOME_AI_OPENAI_SECONDARY_MODEL" = 'gpt-5.6-terra'
    test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '15'
    test "$HOME_AI_GOLDEN_LIVE_CONFIRM" = 'RUN_ONE_LIVE_GOLDEN_CASE'
    printf complex-identity-jamsil-ells >"$FAKE_UV_MARKER"
    ;;
  'run home-ai-property-golden --mode live --case-id recent-trades-jamsil-ells-84')
    test "$PGPASSWORD" = 'p@ss'
    test "$HOME_AI_PROPERTY_DSN" = 'host=127.0.0.1 port=15432 dbname=home_search user=home_search_ai_reader'
    test "$HOME_AI_OPENAI_API_KEY" = 'test-provider-secret'
    test "$HOME_AI_OPENAI_PRIMARY_MODEL" = 'gpt-5.6-luna'
    test "$HOME_AI_OPENAI_SECONDARY_MODEL" = 'gpt-5.6-terra'
    test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '15'
    test "$HOME_AI_GOLDEN_LIVE_CONFIRM" = 'RUN_ONE_LIVE_GOLDEN_CASE'
    printf recent-trades-jamsil-ells-84 >"$FAKE_UV_MARKER"
    ;;
  'run python -m ai_service.property_chat.criteria_activation')
    test "$HOME_AI_OPENAI_API_KEY" = 'test-provider-secret'
    test "$HOME_AI_OPENAI_PRIMARY_MODEL" = 'gpt-5.6-luna'
    test "$HOME_AI_OPENAI_SECONDARY_MODEL" = 'gpt-5.6-terra'
    test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '15'
    test "$HOME_AI_GOLDEN_LIVE_CONFIRM" = 'RUN_ONE_LIVE_GOLDEN_CASE'
    printf criteria-recommendation-academy-transit >"$FAKE_UV_MARKER"
    ;;
  'run python -m ai_service.property_chat.reference_activation')
    test "$HOME_AI_PROPERTY_DSN" = 'postgresql://home_search_ai_reader:p%40ss@127.0.0.1:15432/home_search'
    test "$HOME_AI_REFERENCE_DSN" = 'postgresql://home_search_ai_runtime:r%40ss@127.0.0.1:15432/home_search_ai'
    test "$HOME_AI_OPENAI_API_KEY" = 'test-provider-secret'
    test "$HOME_AI_OPENAI_PRIMARY_MODEL" = 'gpt-5.6-luna'
    test "$HOME_AI_OPENAI_SECONDARY_MODEL" = 'gpt-5.6-terra'
    case "$HOME_AI_REFERENCE_ACTIVATION_CASE_ID" in
      comparison-jamsil-ells-helio-84 | budget-recommendation-songpa-84-retail) test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '30' ;;
      *) test "$HOME_AI_OPENAI_TIMEOUT_SECONDS" = '15' ;;
    esac
    test "$HOME_AI_GOLDEN_LIVE_CONFIRM" = 'RUN_ONE_LIVE_GOLDEN_CASE'
    case "$HOME_AI_REFERENCE_ACTIVATION_CASE_ID" in
      school-location-jamsil-ells | comparison-jamsil-ells-helio-84 | budget-recommendation-songpa-84-retail)
        printf '%s' "$HOME_AI_REFERENCE_ACTIVATION_CASE_ID" >"$FAKE_UV_MARKER" ;;
      *) exit 92 ;;
    esac
    ;;
  *) exit 91 ;;
esac
""",
        encoding="utf-8",
    )
    path.chmod(0o700)


def write_fake_gnu_stat(path: Path) -> None:
    path.write_text(
        """#!/bin/sh
set -eu
if [ "$1" = "-c" ] && [ "$2" = "%a" ]; then
    printf '600'
    exit 0
fi
if [ "$1" = "-f" ]; then
    printf 'gnu-filesystem-stat'
    exit 0
fi
exit 2
""",
        encoding="utf-8",
    )
    path.chmod(0o700)


def run_runner(
    tmp_path: Path,
    mode: str,
    *,
    case_id: str | None = None,
    confirmation: str = "",
    mutate_vars: Callable[[Path], None] | None = None,
) -> subprocess.CompletedProcess[str]:
    vars_file = tmp_path / "ai.env"
    fake_uv = tmp_path / "uv"
    marker = tmp_path / "called"
    property_vars_file = tmp_path / "property.env"
    write_vars(vars_file)
    property_vars_file.write_text(
        "AI_DATA_RUNTIME_DB_PASSWORD=r@ss\n", encoding="utf-8"
    )
    property_vars_file.chmod(0o600)
    if mutate_vars is not None:
        mutate_vars(vars_file)
    write_fake_uv(fake_uv)
    environment = os.environ.copy()
    environment.update(
        {
            "PATH": f"{tmp_path}{os.pathsep}{environment['PATH']}",
            "FAKE_UV_MARKER": str(marker),
            "HOME_AI_GOLDEN_LIVE_CONFIRM": confirmation,
            "HOME_AI_REFERENCE_PROPERTY_VARS_FILE": str(property_vars_file),
        }
    )
    arguments = [str(RUNNER), mode]
    if case_id is not None:
        arguments.extend(["--case-id", case_id])
    arguments.append(str(vars_file))
    result = subprocess.run(
        arguments,
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


def test_offline_runner_accepts_gnu_stat_permissions(tmp_path: Path) -> None:
    write_fake_gnu_stat(tmp_path / "stat")

    result = run_runner(tmp_path, "offline")

    assert result.returncode == 0, result.stderr
    assert result.marker.read_text(encoding="utf-8") == "offline"  # type: ignore[attr-defined]


def test_live_runner_runs_one_allowlisted_case_and_passes_provider_settings(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="recent-trades-jamsil-ells-84",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert (  # type: ignore[attr-defined]
        result.marker.read_text(encoding="utf-8")
        == "recent-trades-jamsil-ells-84"
    )
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr


def test_live_runner_runs_criteria_recommendation_activation_case(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="criteria-recommendation-academy-transit",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert (  # type: ignore[attr-defined]
        result.marker.read_text(encoding="utf-8")
        == "criteria-recommendation-academy-transit"
    )
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr


def test_live_runner_runs_school_reference_activation_case(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="school-location-jamsil-ells",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert (  # type: ignore[attr-defined]
        result.marker.read_text(encoding="utf-8")
        == "school-location-jamsil-ells"
    )
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr
    assert "r@ss" not in result.stdout + result.stderr


def test_live_runner_runs_comparison_activation_case(tmp_path: Path) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="comparison-jamsil-ells-helio-84",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert (  # type: ignore[attr-defined]
        result.marker.read_text(encoding="utf-8")
        == "comparison-jamsil-ells-helio-84"
    )
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr
    assert "r@ss" not in result.stdout + result.stderr


def test_live_runner_runs_budget_retail_activation_case(tmp_path: Path) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="budget-recommendation-songpa-84-retail",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 0, result.stderr
    assert (  # type: ignore[attr-defined]
        result.marker.read_text(encoding="utf-8")
        == "budget-recommendation-songpa-84-retail"
    )
    assert "test-provider-secret" not in result.stdout + result.stderr
    assert "p@ss" not in result.stdout + result.stderr
    assert "r@ss" not in result.stdout + result.stderr


def test_live_runner_rejects_missing_confirmation_without_invoking_uv(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="complex-identity-jamsil-ells",
    )

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
        case_id="complex-identity-jamsil-ells",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
        mutate_vars=append_duplicate_key,
    )

    assert result.returncode == 1
    assert "정확히 한 번 정의" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]
    assert duplicate_secret not in result.stdout + result.stderr


def test_live_runner_rejects_missing_case_id_without_invoking_uv(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 2
    assert "--case-id" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]


def test_live_runner_rejects_unapproved_case_id_without_invoking_uv(
    tmp_path: Path,
) -> None:
    result = run_runner(
        tmp_path,
        "live",
        case_id="complex-not-found",
        confirmation="RUN_ONE_LIVE_GOLDEN_CASE",
    )

    assert result.returncode == 1
    assert "승인된 live case" in result.stderr
    assert not result.marker.exists()  # type: ignore[attr-defined]

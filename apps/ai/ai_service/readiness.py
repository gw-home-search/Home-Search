from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass
from collections.abc import Callable
from typing import Protocol

from .chat import (
    get_academy_location_repository,
    get_enabled_reference_capabilities,
    get_property_fact_repository,
    get_rail_station_repository,
)

_READINESS_PROBE_TIMEOUT_SECONDS = 2.0


@dataclass(frozen=True)
class ReadinessResult:
    status: str
    checks: dict[str, str]


class ReadinessChecker(Protocol):
    async def check(self) -> ReadinessResult: ...


class RuntimeReadinessChecker:
    async def check(self) -> ReadinessResult:
        enabled = get_enabled_reference_capabilities()
        property_state, academy_state, rail_state = await asyncio.gather(
            _repository_state(get_property_fact_repository),
            _optional_repository_state(
                "academy_lookup" in enabled, get_academy_location_repository
            ),
            _optional_repository_state(
                "rail_station_lookup" in enabled, get_rail_station_repository
            ),
        )
        openai_state = "configured" if _openai_configured() else "not_configured"
        checks = {
            "property": property_state,
            "academy": academy_state,
            "rail": rail_state,
            "openai": openai_state,
        }
        if property_state != "ready" or openai_state != "configured":
            status = "NOT_READY"
        elif academy_state != "ready" or rail_state != "ready":
            status = "DEGRADED"
        else:
            status = "READY"
        return ReadinessResult(status, checks)


async def _repository_state(factory: Callable[[], object]) -> str:
    try:
        await asyncio.wait_for(
            asyncio.to_thread(_probe_repository, factory),
            timeout=_READINESS_PROBE_TIMEOUT_SECONDS,
        )
    except Exception:
        return "not_ready"
    return "ready"


def _probe_repository(factory: Callable[[], object]) -> None:
    repository = factory()
    probe = getattr(repository, "readiness_probe", None)
    if not callable(probe):
        raise RuntimeError("repository readiness probe is unavailable")
    probe()


async def _optional_repository_state(
    enabled: bool, factory: Callable[[], object]
) -> str:
    if not enabled:
        return "unavailable"
    return "ready" if await _repository_state(factory) == "ready" else "unavailable"


def _openai_configured() -> bool:
    from .property_chat.openai_responses import OpenAIResponsesSettings

    api_key = os.getenv("HOME_AI_OPENAI_API_KEY", "").strip()
    primary_model = os.getenv("HOME_AI_OPENAI_PRIMARY_MODEL", "").strip()
    secondary_model = os.getenv("HOME_AI_OPENAI_SECONDARY_MODEL", "").strip()
    if not api_key or not primary_model or not secondary_model:
        return False
    if primary_model == secondary_model:
        return False
    try:
        timeout_seconds = float(os.getenv("HOME_AI_OPENAI_TIMEOUT_SECONDS", "8"))
        for model in (primary_model, secondary_model):
            OpenAIResponsesSettings(
                api_key=api_key,
                model=model,
                timeout_seconds=timeout_seconds,
            )
    except (TypeError, ValueError):
        return False
    return True


def get_readiness_checker() -> ReadinessChecker:
    return RuntimeReadinessChecker()

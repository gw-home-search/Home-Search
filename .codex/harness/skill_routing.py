#!/usr/bin/env python3
"""Skill routing policy for Home Search V1 harness prompts."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class SkillRoute:
    skill: str
    reason: str


def normalize_targets(targets: str | Iterable[str] | None) -> tuple[str, ...]:
    if targets is None:
        return ()
    if isinstance(targets, str):
        raw = targets.strip()
        if not raw:
            return ()
        if raw == "both":
            return ("backend", "frontend")
        if raw == "planning-only":
            return ("planning-only",)
        return (raw,)
    normalized: list[str] = []
    for target in targets:
        normalized.extend(normalize_targets(str(target)))
    return tuple(dict.fromkeys(normalized))


def has_backend(targets: tuple[str, ...]) -> bool:
    return "backend" in targets


def has_frontend(targets: tuple[str, ...]) -> bool:
    return "frontend" in targets


def routes_for(mode: str, targets: str | Iterable[str] | None = None) -> tuple[SkillRoute, ...]:
    target_set = normalize_targets(targets)
    routes: list[SkillRoute] = []

    if mode in {"next", "plan"}:
        routes.append(SkillRoute("$planning", "turn backlog, recent report evidence, risks, and acceptance criteria into a decision-complete plan."))
        if mode == "plan":
            routes.append(SkillRoute("$tdd", "define First RED, Expected RED failure, and Minimum GREEN before execution."))
        if has_backend(target_set) or has_frontend(target_set):
            routes.append(SkillRoute("$api-contract", "check V1 URL, request, response, unit, and error compatibility before implementation."))
        return tuple(routes)

    if mode == "execute":
        routes.append(SkillRoute("$tdd", "drive behavior changes through First RED, Expected RED failure, Minimum GREEN, and regression evidence."))
        if has_backend(target_set):
            routes.append(SkillRoute("$backend-api", "apply apps/api Spring Boot, persistence, ingest, Flyway, and backendQualityCheck rules."))
            routes.append(SkillRoute("$api-contract", "preserve V1 API request, response, unit, and error contracts."))
        if has_frontend(target_set):
            routes.append(SkillRoute("$frontend-web", "apply apps/web Vite React, Kakao map, adapter, and map-first UI rules."))
            routes.append(SkillRoute("$api-contract", "preserve frontend compatibility with V1 URLs, fields, units, and error behavior."))
        routes.append(SkillRoute("$systematic-debugging", "use only when lint/test/build/hook/CI/runtime evidence fails and a reproducible loop is needed."))
        routes.append(SkillRoute("$code-review", "perform findings-first local review before completion or PR evidence."))
        return dedupe(routes)

    if mode == "gate":
        routes.append(SkillRoute("$code-review", "review completed diffs and evidence findings-first before completion or PR."))
        routes.append(SkillRoute("$tdd", "verify First RED validity and Minimum GREEN evidence when behavior changed."))
        if has_backend(target_set) or has_frontend(target_set):
            routes.append(SkillRoute("$api-contract", "check contract-adjacent changes before gate Pass."))
        return dedupe(routes)

    if mode == "recover":
        routes.append(SkillRoute("$systematic-debugging", "reproduce the failing command or hook block, isolate root cause, and collect recovery evidence."))
        routes.append(SkillRoute("$tdd", "add or repair regression evidence when the root cause is behavior-related."))
        routes.append(SkillRoute("$code-review", "review the recovered diff and remaining verification gaps."))
        return tuple(routes)

    if mode == "report":
        return (SkillRoute("$code-review", "summarize PR/report evidence and residual risk findings-first."),)

    return ()


def dedupe(routes: Iterable[SkillRoute]) -> tuple[SkillRoute, ...]:
    seen: set[str] = set()
    output: list[SkillRoute] = []
    for route in routes:
        if route.skill in seen:
            continue
        seen.add(route.skill)
        output.append(route)
    return tuple(output)


def route_names(mode: str, targets: str | Iterable[str] | None = None) -> list[str]:
    return [route.skill for route in routes_for(mode, targets)]


def routing_text(mode: str, targets: str | Iterable[str] | None = None) -> str:
    routes = routes_for(mode, targets)
    if not routes:
        return "- none"
    return "\n".join(f"- {route.skill}: {route.reason}" for route in routes)


def routing_payload(mode: str, targets: str | Iterable[str] | None = None) -> dict[str, object]:
    routes = routes_for(mode, targets)
    return {
        "mode": mode,
        "targets": list(normalize_targets(targets)),
        "skills": [{"name": route.skill.lstrip("$"), "trigger": route.skill, "reason": route.reason} for route in routes],
    }


def run_self_test() -> int:
    execute = route_names("execute", "both")
    plan = route_names("plan", "backend")
    recover = routing_text("recover")
    checks = [
        "$tdd" in execute,
        "$backend-api" in execute,
        "$frontend-web" in execute,
        "$api-contract" in execute,
        "$code-review" in execute,
        execute.count("$api-contract") == 1,
        "$planning" in plan,
        "$systematic-debugging" in recover,
    ]
    if all(checks):
        print("self-test passed: skill_routing")
        return 0
    print("self-test failed: skill_routing")
    return 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())

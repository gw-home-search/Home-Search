#!/usr/bin/env python3
"""Skill routing policy for Home Search V1 harness prompts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]
SKILL_ROOT = REPO_ROOT / ".agents" / "skills"


@dataclass(frozen=True)
class SkillDefinition:
    name: str
    trigger: str
    path: str
    description: str
    available: bool


@dataclass(frozen=True)
class SkillRoute:
    skill: str
    reason: str
    phase: str
    role: str
    required_evidence: tuple[str, ...] = ()
    fallback: str = ""
    path: str = ""
    description: str = ""
    available: bool = False


HARNESS_EVIDENCE = ("상태", "검증", "다음 행동")
PLAN_EVIDENCE = ("인수 기준", "중단 조건", "다음 행동")
TDD_EVIDENCE = ("최초 RED", "예상 RED 실패", "최소 GREEN")
CONTRACT_EVIDENCE = ("계약 영향", "contract-reviewer: 게이트 결정")
BACKEND_EVIDENCE = ("backendQualityCheck", "Coverage: >=90%", "Docs/OpenAPI")
FRONTEND_EVIDENCE = ("cd apps/web && npm run test", "cd apps/web && npm run build")
DEBUG_EVIDENCE = ("차단 사유", "복구 순서", "검증")
REVIEW_EVIDENCE = ("reviewer: 지적사항", "검증 공백", "잔여 위험")


def skill_name(value: str) -> str:
    return value.strip().lstrip("$")


def skill_path(name: str) -> Path:
    return SKILL_ROOT / skill_name(name) / "SKILL.md"


def parse_frontmatter(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}
    if not lines or lines[0].strip() != "---":
        return {}
    metadata: dict[str, str] = {}
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        metadata[key.strip()] = value.strip().strip('"').strip("'")
    return metadata


def load_skill_definition(name: str) -> SkillDefinition:
    normalized = skill_name(name)
    path = skill_path(normalized)
    metadata = parse_frontmatter(path) if path.exists() else {}
    declared_name = metadata.get("name", "")
    available = path.exists() and declared_name == normalized
    return SkillDefinition(
        name=normalized,
        trigger=f"${normalized}",
        path=path.relative_to(REPO_ROOT).as_posix(),
        description=metadata.get("description", ""),
        available=available,
    )


def route(
    name: str,
    phase: str,
    role: str,
    reason: str,
    required_evidence: Iterable[str] = (),
    fallback: str = "",
) -> SkillRoute:
    definition = load_skill_definition(name)
    return SkillRoute(
        skill=definition.trigger,
        phase=phase,
        role=role,
        reason=reason,
        required_evidence=tuple(required_evidence),
        fallback=fallback,
        path=definition.path,
        description=definition.description,
        available=definition.available,
    )


def harness_route(phase: str, reason: str, evidence: Iterable[str] = HARNESS_EVIDENCE) -> SkillRoute:
    return route("v1-slice-harness", phase, "orchestrator", reason, evidence)


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

    if mode == "next":
        routes.append(harness_route("next", "resolve backlog, report evidence, target filters, and next command boundaries."))
        routes.append(route("planning", "next", "primary", "turn backlog, recent report evidence, risks, and acceptance criteria into next-slice candidates.", PLAN_EVIDENCE))
        routes.append(route("code-review", "next", "support", "interpret recent gate findings only when report evidence contains review findings.", REVIEW_EVIDENCE, "not needed when no recent findings exist."))
        return dedupe(routes)

    if mode == "plan":
        routes.append(harness_route("plan", "render a non-mutating slice execution brief and next dry-run command."))
        routes.append(route("planning", "plan", "primary", "produce the decision-complete slice plan and acceptance criteria.", PLAN_EVIDENCE))
        routes.append(route("tdd", "plan", "checkpoint", "define First RED, Expected RED failure, and Minimum GREEN before execution.", TDD_EVIDENCE))
        if has_backend(target_set) or has_frontend(target_set):
            routes.append(route("api-contract", "plan", "checkpoint", "check V1 URL, request, response, unit, and error compatibility before implementation.", CONTRACT_EVIDENCE))
        return dedupe(routes)

    if mode == "execute":
        routes.append(harness_route("execute", "prepare target worktrees, inject skill contracts, collect verification, gate, commit, integration, and optional PR evidence."))
        routes.append(route("tdd", "execute", "primary", "drive behavior changes through First RED, Expected RED failure, Minimum GREEN, and regression evidence.", TDD_EVIDENCE))
        if has_backend(target_set):
            routes.append(route("backend-api", "execute", "support", "apply apps/api Spring Boot, persistence, ingest, Flyway, and backendQualityCheck rules.", BACKEND_EVIDENCE))
            routes.append(route("api-contract", "execute", "checkpoint", "preserve V1 API request, response, unit, and error contracts.", CONTRACT_EVIDENCE))
        if has_frontend(target_set):
            routes.append(route("frontend-web", "execute", "support", "apply apps/web Vite React, Kakao map, adapter, and map-first UI rules.", FRONTEND_EVIDENCE))
            routes.append(route("api-contract", "execute", "checkpoint", "preserve frontend compatibility with V1 URLs, fields, units, and error behavior.", CONTRACT_EVIDENCE))
        routes.append(route("systematic-debugging", "execute", "recovery", "use only when lint/test/build/hook/CI/runtime evidence fails and a reproducible loop is needed.", DEBUG_EVIDENCE, "not used when all verification passes."))
        routes.append(route("code-review", "execute", "review", "perform findings-first local review before completion or PR evidence.", REVIEW_EVIDENCE))
        return dedupe(routes)

    if mode == "gate":
        routes.append(harness_route("gate", "run read-only gate review against completed target diff and recorded evidence."))
        routes.append(route("code-review", "gate", "primary", "review completed diffs and evidence findings-first before completion or PR.", REVIEW_EVIDENCE))
        routes.append(route("tdd", "gate", "checkpoint", "verify First RED validity and Minimum GREEN evidence when behavior changed.", TDD_EVIDENCE))
        if has_backend(target_set) or has_frontend(target_set):
            routes.append(route("api-contract", "gate", "checkpoint", "check contract-adjacent changes before gate Pass.", CONTRACT_EVIDENCE))
        return dedupe(routes)

    if mode == "recover":
        routes.append(harness_route("recover", "turn hook blocks and failed commands into a non-mutating recovery plan unless execution is explicitly requested."))
        routes.append(route("systematic-debugging", "recover", "primary", "reproduce the failing command or hook block, isolate root cause, and collect recovery evidence.", DEBUG_EVIDENCE))
        routes.append(route("tdd", "recover", "checkpoint", "add or repair regression evidence when the root cause is behavior-related.", TDD_EVIDENCE, "not needed for evidence-only reruns."))
        routes.append(route("code-review", "recover", "review", "review the recovered diff and remaining verification gaps.", REVIEW_EVIDENCE))
        return dedupe(routes)

    if mode == "report":
        routes.append(harness_route("report", "render local report, PR body, safety commands, and notification links."))
        routes.append(route("code-review", "report", "primary", "summarize PR/report evidence and residual risk findings-first.", REVIEW_EVIDENCE))
        return dedupe(routes)

    return ()


def dedupe(routes: Iterable[SkillRoute]) -> tuple[SkillRoute, ...]:
    seen: set[str] = set()
    output: list[SkillRoute] = []
    for item in routes:
        if item.skill in seen:
            continue
        seen.add(item.skill)
        output.append(item)
    return tuple(output)


def route_names(mode: str, targets: str | Iterable[str] | None = None) -> list[str]:
    return [item.skill for item in routes_for(mode, targets)]


def routing_text(mode: str, targets: str | Iterable[str] | None = None) -> str:
    routes = routes_for(mode, targets)
    if not routes:
        return "- none"
    lines = ["Skill contract:"]
    for item in routes:
        evidence = "; ".join(item.required_evidence) if item.required_evidence else "not specified"
        fallback = f" Fallback: {item.fallback}" if item.fallback else ""
        availability = "available" if item.available else "missing"
        lines.append(
            f"- {item.skill} [{item.role}] path={item.path} ({availability}): {item.reason} "
            f"Required evidence: {evidence}.{fallback}"
        )
    return "\n".join(lines)


def routing_payload(mode: str, targets: str | Iterable[str] | None = None) -> dict[str, object]:
    routes = routes_for(mode, targets)
    return {
        "mode": mode,
        "targets": list(normalize_targets(targets)),
        "skills": [
            {
                "name": item.skill.lstrip("$"),
                "trigger": item.skill,
                "phase": item.phase,
                "role": item.role,
                "path": item.path,
                "description": item.description,
                "reason": item.reason,
                "required_evidence": list(item.required_evidence),
                "fallback": item.fallback,
                "available": item.available,
            }
            for item in routes
        ],
    }


def run_self_test() -> int:
    execute = route_names("execute", "both")
    plan = route_names("plan", "backend")
    recover = routing_text("recover")
    payload = routing_payload("execute", "frontend")
    frontend_skills = payload["skills"]
    frontend_web = next(
        (item for item in frontend_skills if isinstance(item, dict) and item.get("name") == "frontend-web"),
        {},
    )
    all_routes = routes_for("execute", "both") + routes_for("plan", "backend") + routes_for("gate", "frontend")
    checks = [
        "$v1-slice-harness" in execute,
        "$tdd" in execute,
        "$backend-api" in execute,
        "$frontend-web" in execute,
        "$api-contract" in execute,
        "$code-review" in execute,
        execute.count("$api-contract") == 1,
        "$v1-slice-harness" in plan,
        "$planning" in plan,
        "$systematic-debugging" in recover,
        "Skill contract:" in routing_text("execute", "frontend"),
        frontend_web.get("role") == "support",
        frontend_web.get("path") == ".agents/skills/frontend-web/SKILL.md",
        "cd apps/web && npm run test" in frontend_web.get("required_evidence", []),
        all(item.available for item in all_routes),
        not load_skill_definition("missing-skill").available,
        not load_skill_definition("v1-slice-harness").path.lower().endswith("_ko.md"),
    ]
    if all(checks):
        print("self-test passed: skill_routing")
        return 0
    print("self-test failed: skill_routing")
    return 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())

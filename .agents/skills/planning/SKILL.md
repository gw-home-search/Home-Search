---
name: planning
description: Turn Home Search /goal or ambiguous requests into decision-complete plans with V1 guardrails.
---

# Planning Skill

Use this skill when a request is goal-level or has ambiguous scope. The goal is to produce an implementation-ready plan while stopping when V1 API or data invariants would change.

## Inputs

- User request or `/goal` brief.
- `AGENTS.md`.
- Relevant canonical `docs/*.md`.
- Target code or source backend/frontend read-only references when needed.

## Required Plan Fields

- Goal.
- Scope.
- Non-scope.
- Touched subsystem: backend, frontend, data, infra, docs.
- App `AGENTS.md` and `CONTEXT.md` checked.
- Code-mapper preflight need.
- Contract-reviewer checkpoint.
- Public contract impact.
- Data invariant impact.
- TDD gate decision: required, not applicable, or blocked/no test environment.
- TDD slice plan.
- Agent handoffs.
- Web/API collision risk.
- Verification commands.
- Stop conditions.

## Backend Checklist

- V1 API URLs and response shapes remain stable.
- Raw ingest -> normalized trade ordering is preserved.
- Duplicate-safe ingest and failed match queryability are preserved.
- The `complex_id` operational relation is clear.
- V2 features do not enter the critical path.

## Frontend Checklist

- Map, search, region, detail, and trade API compatibility is preserved.
- Marker adapter fields match the canonical contract.
- Map failure handling keeps the map usable.
- Verification includes only package scripts that exist.

## Output Rule

Keep the plan short and executable. Prefer TDD slice plans over generic test
plans for backend or frontend behavior changes. For backend/frontend behavior
slices, name the `tdd-guide` handoff when RED validity, public seam, expected
RED failure, or minimum GREEN is part of the plan; otherwise state the RED
waiver reason. Use Korean-first prose for the user-facing plan body while
keeping commands, paths, status tokens, and API names unchanged. If the user
asked for implementation and no stop condition is hit, proceed after the plan.

---
name: planning
description: Convert Home Search /goal, ambiguous requests, next-slice choices, acceptance criteria, and V1 API/data guardrail questions into decision-complete plans. Use for "plan", "planning", "next slice comparison", "acceptance criteria", "API contract impact", "목표", "플랜", "계획", "다음 slice 비교", "인수 기준". Do not use for failed command debugging or final diff review; route failures to systematic-debugging and review to code-review/reviewer.
---


# Planning Skill

Use this skill when a request is goal-level or has ambiguous scope. The goal is to produce an implementation-ready plan while stopping when V1 API or data invariants would change.

## When To Use

- `/goal`, ambiguous requests, or next-slice selection.
- Comparing candidate slices or turning gate findings into acceptance criteria.
- Questions about V1 API contract impact, data invariant impact, or whether a
  request belongs in V1 or V2.

## Do Not Use

- Failed lint, test, build, hook, CI, runtime, or API reproduction work; use
  `systematic-debugging`.
- Final diff, gate, or PR review; use `code-review` or `reviewer`.
- Running First RED/GREEN loops; route behavior changes to `tdd`.

## Routes To

- `tdd` or `tdd-guide` when the plan needs First RED, expected RED failure,
  public seam, or minimum GREEN decisions.
- `contract-reviewer` when URL, request, response, unit, or error compatibility
  can change.
- `systematic-debugging` when the plan starts from a concrete failure.
- `code-review` or `reviewer` when the plan becomes a completed diff review.

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
- Acceptance criteria.
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

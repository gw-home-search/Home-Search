---
name: spec-to-plan
description: Convert Home Search goals into decision-complete V1 implementation plans before web/api work starts.
---

# Spec To Plan Skill

Use this skill when a Home Search request is goal-level, cross-app, ambiguous, or likely to affect both `apps/api` and `apps/web`.

## Purpose

Turn user intent into an implementation-ready plan that preserves the V1 API contract, data invariants, and app ownership boundaries.

This is the Home Search rewrite of spec-driven development, writing-plans, and PRD/task-breakdown patterns. Do not copy external templates; keep the output specific to Home Search V1.

## Required Inputs

- Root `AGENTS.md`.
- `docs/README.md`.
- `docs/MIGRATION_PLAN.md`.
- Relevant canonical docs.
- Root `CONTEXT.md`.
- `apps/api/CONTEXT.md` when backend is involved.
- `apps/web/CONTEXT.md` when frontend is involved.
- Existing target files if they exist.

## Plan Fields

Every plan must state:

- Goal.
- Success criteria.
- In scope.
- Out of scope.
- Public API contract impact.
- Data invariant impact.
- Affected app ownership: `apps/api`, `apps/web`, both, or neither.
- Vertical slices.
- Test strategy.
- Verification commands.
- Stop conditions.

## Guardrails

Stop and ask before planning implementation that requires:

- Public V1 URL, method, field, type, or unit changes.
- Data migration that loses or reinterprets existing data.
- V2 work in the V1 critical path.
- Cross-app changes without an API contract checkpoint.

## Output Rules

- Prefer short, decision-complete plans.
- Use Korean-first prose for the user-facing plan body while keeping commands,
  paths, status tokens, and API names unchanged.
- Do not create implementation code.
- Do not modify `docs/API_CONTRACT.md`.
- If the plan later needs ADR recording, mark it as an ADR candidate instead of writing `docs/adr` directly.

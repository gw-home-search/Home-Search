---
name: planning
description: Home Search /goal 또는 ambiguous request를 V1 guardrails가 있는 decision-complete plan으로 바꾼다.
---

# Planning Skill

요청이 goal-level이거나 scope가 ambiguous할 때 이 skill을 사용한다. 목표는 V1 API 또는 data invariants 변경이 필요하면 중단하면서 implementation-ready plan을 만드는 것이다.

## Inputs

- User request 또는 `/goal` brief.
- `AGENTS.md`.
- 관련 canonical `docs/*.md`.
- 필요할 경우 target code 또는 source backend/frontend read-only references.

## Required Plan Fields

- Goal.
- Scope.
- Non-scope.
- Touched subsystem: backend, frontend, data, infra, docs.
- App `AGENTS.md`와 `CONTEXT.md` checked.
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

- V1 API URLs와 response shapes는 안정적으로 유지된다.
- Raw ingest -> normalized trade ordering이 보존된다.
- Duplicate-safe ingest와 failed match queryability가 보존된다.
- `complex_id` operational relation이 명확하다.
- V2 features는 critical path에 들어오지 않는다.

## Frontend Checklist

- Map, search, region, detail, trade API compatibility가 보존된다.
- Marker adapter fields가 canonical contract와 맞다.
- Map failure handling이 map usable 상태를 유지한다.
- Verification에는 존재하는 package scripts만 포함한다.

## Output Rule

계획은 짧고 실행 가능하게 유지한다. backend 또는 frontend behavior changes에는 generic test plans보다 TDD slice plans를 선호한다. backend/frontend behavior slices에서 RED validity, public seam, expected RED failure, minimum GREEN이 plan의 일부라면 `tdd-guide` handoff를 명시하고, 그렇지 않으면 RED waiver reason을 적는다. User-facing plan body는 Korean-first prose를 사용하되 commands, paths, status tokens, API names는 그대로 유지한다. 사용자가 implementation을 요청했고 stop condition이 없으면 plan 이후 진행한다.

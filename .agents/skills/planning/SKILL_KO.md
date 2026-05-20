---
name: planning
description: Home Search /goal, ambiguous requests, next-slice choices, acceptance criteria, V1 API/data guardrail questions를 decision-complete plan으로 변환합니다. "plan", "planning", "next slice comparison", "acceptance criteria", "API contract impact", "목표", "플랜", "계획", "다음 slice 비교", "인수 기준"에 사용합니다. failed command debugging이나 final diff review에는 사용하지 말고, failure는 systematic-debugging으로, review는 code-review/reviewer로 라우팅합니다.
---


# Planning Skill

요청이 goal-level이거나 scope가 모호할 때 이 skill을 사용합니다. 목표는 V1 API
또는 data invariant가 바뀌는 지점에서는 멈추면서 implementation-ready plan을
만드는 것입니다.

## When To Use

- `/goal`, ambiguous requests, next-slice selection.
- candidate slice 비교 또는 gate findings를 acceptance criteria로 변환.
- V1 API contract impact, data invariant impact, 요청이 V1/V2 중 어디에
  속하는지에 대한 질문.

## Do Not Use

- failed lint, test, build, hook, CI, runtime, API reproduction work에는
  `systematic-debugging`을 사용합니다.
- final diff, gate, PR review에는 `code-review` 또는 `reviewer`를 사용합니다.
- First RED/GREEN loop 실행에는 사용하지 말고 behavior change는 `tdd`로
  라우팅합니다.

## Routes To

- plan에 First RED, expected RED failure, public seam, minimum GREEN 결정이
  필요하면 `tdd` 또는 `tdd-guide`.
- URL, request, response, unit, error compatibility가 바뀔 수 있으면
  `contract-reviewer`.
- plan이 concrete failure에서 시작하면 `systematic-debugging`.
- plan이 completed diff review로 넘어가면 `code-review` 또는 `reviewer`.

## Inputs

- User request 또는 `/goal` brief.
- `AGENTS.md`.
- 관련 canonical `docs/*.md`.
- 필요 시 target code 또는 source backend/frontend read-only reference.

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

- V1 API URL과 response shape는 stable하게 유지합니다.
- Raw ingest -> normalized trade ordering을 보존합니다.
- Duplicate-safe ingest와 failed match queryability를 보존합니다.
- `complex_id` operational relation을 명확히 합니다.
- V2 feature를 critical path에 넣지 않습니다.

## Frontend Checklist

- Map, search, region, detail, trade API compatibility를 보존합니다.
- Marker adapter field가 canonical contract와 일치합니다.
- Map failure handling은 map을 계속 usable하게 둡니다.
- Verification은 실제 존재하는 package script만 포함합니다.

## Output Rule

plan은 짧고 실행 가능하게 유지합니다. backend 또는 frontend behavior change에는
generic test plan보다 TDD slice plan을 선호합니다. backend/frontend behavior
slice에서는 RED validity, public seam, expected RED failure, minimum GREEN이
plan의 일부이면 `tdd-guide` handoff를 명시하고, 아니면 RED waiver reason을
적습니다. user-facing plan body는 Korean-first prose로 작성하되 commands, paths,
status tokens, API names는 그대로 유지합니다. 사용자가 implementation을 요청했고
stop condition이 없으면 plan 이후 진행합니다.

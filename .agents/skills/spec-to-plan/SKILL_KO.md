---
name: spec-to-plan
description: web/api 작업 전에 Home Search goals를 decision-complete V1 implementation plans로 변환한다.
---

# Spec To Plan Skill

Home Search 요청이 goal-level, cross-app, ambiguous이거나 `apps/api`와 `apps/web` 둘 다에 영향을 줄 가능성이 있을 때 이 skill을 사용한다.

## Purpose

사용자 의도를 V1 API contract, data invariants, app ownership boundaries를 보존하는 implementation-ready plan으로 바꾼다.

이것은 spec-driven development, writing-plans, PRD/task-breakdown patterns를 Home Search에 맞게 다시 쓴 것이다. 외부 templates를 복사하지 말고 Home Search V1에 특화된 output을 유지한다.

## Required Inputs

- Root `AGENTS.md`.
- `docs/README.md`.
- `docs/MIGRATION_PLAN.md`.
- 관련 canonical docs.
- Root `CONTEXT.md`.
- backend가 관련되면 `apps/api/CONTEXT.md`.
- frontend가 관련되면 `apps/web/CONTEXT.md`.
- 존재하는 target files.

## Plan Fields

모든 plan은 다음을 명시해야 한다:

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

다음이 필요한 implementation plan 전에 중단하고 질문한다:

- Public V1 URL, method, field, type, unit changes.
- 기존 data를 잃거나 reinterpret하는 data migration.
- V1 critical path 안의 V2 work.
- API contract checkpoint 없는 cross-app changes.

## Output Rules

- 짧고 decision-complete한 plans를 선호한다.
- User-facing plan body는 Korean-first prose를 사용하되 commands, paths, status tokens, API names는 그대로 유지한다.
- implementation code를 만들지 않는다.
- `docs/API_CONTRACT.md`를 수정하지 않는다.
- 나중에 ADR 기록이 필요하면 `docs/adr`를 직접 쓰지 말고 ADR candidate로 표시한다.

---
name: api-contract
description: backend/frontend 변경 전에 Home Search V1 API URL, request, response, unit, error compatibility를 확인한다.
---

# API Contract Skill

API clients, controllers, DTOs, marker adapters, detail/trade flows, request validation, error handling을 건드리는 작업에 이 skill을 사용한다.

## Purpose

parallel work가 시작되거나 landing되기 전에 `apps/api`와 `apps/web`이 `docs/API_CONTRACT.md`와 compatible한지 유지한다.

## Required Inputs

- Root `AGENTS.md`.
- `docs/API_CONTRACT.md`.
- map work에는 `docs/MAP_DISPLAY_FLOW.md`.
- trade/detail work에는 `docs/DATA_STORAGE.md`.
- 관련 app `AGENTS.md`와 `CONTEXT.md`.

## Checks

작업이 다음 항목을 보존하는지 확인한다:

- URL과 HTTP method.
- Request field names와 types.
- Response field names와 types.
- Amount units.
- Coordinate conventions.
- Error status와 `ProblemDetail` shape.
- Empty-result behavior.
- V1/V2 boundary.

## Frontend Rules

- canonical marker fields를 유지한다: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- `id`, `latitude`, `longitude` 같은 source variants는 adapters 내부에서만 허용한다.
- UI redesign을 위해 backend contract changes를 요구하지 않는다.

## Backend Rules

- canonical V1 fields를 반환한다.
- `complex_id`를 operational trade relation으로 보존한다.
- contract가 먼저 명시적으로 변경되지 않는 한 public trade responses에 `complex_pk`, `apt_seq`, `source`, `source_key` 같은 audit fields를 노출하지 않는다.
- Map endpoints는 ranking, trend, favorite, alarm, mail, auth state를 요구하면 안 된다.

## Output

다음을 보고한다:

- Contract impact: none, compatible, or breaking.
- Required tests.
- breaking change가 발견될 경우 필요한 stop condition.

User-facing summaries는 Korean-first prose를 사용하되 API field names, paths, commands, status tokens는 그대로 유지한다.

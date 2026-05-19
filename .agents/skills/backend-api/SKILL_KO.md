---
name: backend-api
description: Home Search apps/api Spring Boot, Flyway, ingest, V1 API 작업을 안내한다.
---

# Backend API Skill

`apps/api` backend planning, implementation, review, debugging에 이 skill을 사용한다.

## Required Inputs

- Root `AGENTS.md`.
- `apps/api/AGENTS.md`.
- Root `CONTEXT.md`.
- `apps/api/CONTEXT.md`.
- `docs/ARCHITECTURE.md`.
- `docs/DATA_STORAGE.md`.
- `docs/API_CONTRACT.md`.
- `docs/INFRA_AND_ENV.md`.

## Writable Scope

사용자가 더 넓은 범위를 명시 승인하지 않는 한 `apps/api/**`만 수정한다.

## Backend Guardrails

- V1 API URLs와 response shapes를 보존한다.
- normalized trades보다 raw ingest records를 먼저 저장한다.
- Duplicate ingest가 duplicate normalized trades를 만들면 안 된다.
- Failed matches는 explainable하고 queryable해야 한다.
- `complex_id`를 operational trade relation으로 사용한다.
- audit, matching, dedupe를 위해 `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.
- rankings, favorites, alarms, mail, recommendations, auth-dependent UX, heavy analytics를 V1 map/trade work 밖에 둔다.

## Testing

public seams를 우선한다:

- API contract behavior를 위한 Controller와 DTO tests.
- ingest ordering과 status transitions를 위한 Application service tests.
- uniqueness, partitioning, latest lookup, failed match queryability를 위한 Repository/Flyway tests.
- parsing과 source-key normalization을 위한 External API adapter tests.

## Verification

`apps/api`가 있으면 먼저 사용 가능한 Gradle tasks를 확인한다. 가장 좁은 관련 test를 실행한 뒤, 있으면 `./gradlew test` 또는 `./gradlew verify`를 실행한다.

## Stop Conditions

다음 전에 중단한다:

- Public API breaking change.
- Data-loss migration.
- `complex_id` 또는 `complex_pk` reinterpretation.
- map endpoints에 V2 dependencies 추가.
- secrets 또는 local env values 도입.

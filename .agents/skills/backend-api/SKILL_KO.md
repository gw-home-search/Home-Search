# Backend API Skill KO

> KO 생성 기준: canonical source only
> Source: `.agents/skills/backend-api/SKILL.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.agents/skills/backend-api/SKILL.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

---
name: backend-api
description: Guide Home Search apps/api Spring Boot, Flyway, ingest, and public API work.
---


# Backend API Skill

Use this skill for `apps/api` backend planning, implementation, review, or debugging.

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

Only `apps/api/**`, unless the user explicitly approves a broader scope.

## Backend Guardrails

- Preserve public API URLs and response shapes.
- Save raw ingest records before normalized trades.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- Use `complex_id` as the operational trade relation.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key` for audit, matching, and dedupe.
- Keep rankings, favorites, alarms, mail, recommendations, auth-dependent UX, and heavy analytics out of map/trade work.

## Testing

Prefer public seams:

- Controller and DTO tests for API contract behavior.
- Application service tests for ingest ordering and status transitions.
- Repository/Flyway tests for uniqueness, partitioning, latest lookup, and failed match queryability.
- External API adapter tests for parsing and source-key normalization.

## Verification

When `apps/api` exists, inspect available Gradle tasks first. Run the narrowest relevant test, then `./gradlew test` or `./gradlew verify` if present.

## Stop Conditions

Stop before:

- Public API breaking change.
- Data-loss migration.
- `complex_id` or `complex_pk` reinterpretation.
- Adding later-scope dependencies to map endpoints.
- Introducing secrets or local env values.

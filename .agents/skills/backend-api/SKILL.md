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

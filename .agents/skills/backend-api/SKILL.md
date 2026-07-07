---
name: backend-api
description: Guide Home Search apps/home-data Spring Boot, Flyway, JdbcClient persistence, RTMS ingest, PostGIS map query, and public API implementation with backendQualityCheck verification. Use for "backend", "Spring", "Flyway", "ingest", "migration", "repository", "controller", "batch", "백엔드", "인제스트", "마이그레이션", "배치". Do not use for contract impact decisions (use api-contract), security findings (use security-audit), or failing-command debugging (use systematic-debugging).
---


# Backend API Skill

Use this skill for `apps/home-data` backend planning, implementation, review, or debugging.

## Required Inputs

- Root `AGENTS.md`.
- `apps/home-data/AGENTS.md`.
- Root `CONTEXT.md`.
- `apps/home-data/CONTEXT.md`.
- `docs/ARCHITECTURE.md`.
- `docs/DATA_STORAGE.md`.
- `docs/API_CONTRACT.md`.
- `docs/INFRA_AND_ENV.md`.
- `docs/RESTRUCTURING_PLAN.md` for module split, Spring Batch, or package
  structure work.

## Writable Scope

Only `apps/home-data/**`, unless the user explicitly approves a broader scope.

## Backend Guardrails

- Preserve public API URLs and response shapes.
- Save raw ingest records before normalized trades.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- Use `complex_id` as the operational trade relation.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key` for audit, matching, and dedupe.
- Keep rankings, favorites, alarms, mail, recommendations, auth-dependent UX, and heavy analytics out of map/trade work.

## Domain Placement

Follow the Domain Principles section in root `AGENTS.md` for every backend
addition, not only package refactors:

- Persisted business states, reasons, classifications, confidence values, and
  state-transition rules live under `com.home.domain.<feature>`.
- Domain code must not import `application/**`, `infrastructure/**`, Spring,
  JDBC, HTTP clients, or Flyway.
- Domain enums keep constants stable and provide Korean
  `titleKo()`/`descriptionKo()` metadata.
- Repeated `status == ...` branching around stored business meaning belongs in
  domain-owned methods or policy objects, not in application services.

## Testing

Prefer public seams:

- Controller and DTO tests for API contract behavior — run under `test`.
- Application service tests for ingest ordering and status transitions — run
  under `test`.
- Repository/Flyway tests for uniqueness, partitioning, latest lookup, and
  failed match queryability — run under `persistenceTest` only; write them in
  the persistence test source set or they will not execute where expected.
- External API adapter tests for parsing and source-key normalization — run
  under `test`.

## Verification

- Canonical gate: `cd apps/home-data && ./gradlew backendQualityCheck`. It runs
  `test`, `persistenceTest`, and coverage checks, and it is the PR evidence
  command for any `apps/home-data/**` change
  (see `.codex/harness/pr_evidence.py`).
- `./gradlew test` alone skips PostGIS integration tests. Repository, Flyway,
  partitioning, and PostGIS behavior lives in `persistenceTest`; a change in
  those seams is unverified until `persistenceTest` runs.
- While iterating, run the narrowest target first
  (`./gradlew test --tests '<ClassName>'`), then the canonical gate before any
  completion claim.
- During `docs/RESTRUCTURING_PLAN.md` execution the Gradle module layout
  changes (core/api-app/batch-app). Re-check task names and paths against the
  plan instead of assuming the single-module layout.

## Stop Conditions

Stop before:

- Public API breaking change.
- Data-loss migration.
- `complex_id` or `complex_pk` reinterpretation.
- Adding later-scope dependencies to map endpoints.
- Introducing secrets or local env values.

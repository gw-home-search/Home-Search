---
name: api-contract
description: Check Home Search public API URL, method, request/response field, unit, coordinate, error shape, and empty-result compatibility before backend/frontend changes land. Use for "API contract", "contract impact", "breaking change", "response shape", "marker fields", "compatibility check", "계약 영향", "계약 검토", "호환성", "공개 API", "응답 형태", "필드 변경". Do not use for security findings (use security-audit) or failing-command debugging (use systematic-debugging); route gate decisions on contract-adjacent diffs to contract-reviewer.
---


# API Contract Skill

Use this skill when work touches API clients, controllers, DTOs, marker adapters, detail/trade flows, request validation, or error handling.

## Purpose

Keep `apps/property-data` and `apps/web` compatible with `docs/API_CONTRACT.md` before parallel work begins or lands.

This skill is the local compatibility checklist. When a gate flow routes to the
read-only `contract-reviewer` agent, that agent owns the gate decision; this
skill prepares and verifies the same evidence locally.

## Field Ownership

`docs/API_CONTRACT.md` is the single owner of public URL, field, unit, and
error definitions, including the canonical marker field list. Read the current
definitions from the contract document during every check. Do not rely on field
lists restated in skill files, and do not add new restated lists.

## Required Inputs

- Root `AGENTS.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md` for map work.
- `docs/DATA_STORAGE.md` for trade/detail work.
- Related app `AGENTS.md` and `CONTEXT.md`.

## Checks

Confirm the work preserves:

- URL and HTTP method.
- Request field names and types.
- Response field names and types.
- Amount units.
- Coordinate conventions.
- Error status and `ProblemDetail` shape.
- Empty-result behavior.
- current/later-scope boundary.

## Frontend Rules

- Keep marker payloads on the canonical field names owned by
  `docs/API_CONTRACT.md`; verify against that document, not memory.
- Accept source variants such as `id`, `latitude`, and `longitude` only inside adapters.
- Do not require backend contract changes for UI redesign.

## Backend Rules

- Return canonical project fields.
- Preserve `complex_id` as the operational trade relation.
- Do not expose audit fields such as `complex_pk`, `apt_seq`, `source`, or `source_key` in public trade responses unless the contract is explicitly changed first.
- Map endpoints must not require ranking, trend, favorite, alarm, mail, or auth state.

## Verification

- Backend contract surface: run the affected controller/DTO tests first
  (`cd apps/property-data && ./gradlew test --tests '<ControllerTest>'`), then the
  canonical gate `./gradlew backendQualityCheck` before completion claims.
- Frontend contract surface: `cd apps/web && npm run test` covers adapter
  normalization and marker transforms.
- Compare shapes against the current `docs/API_CONTRACT.md` text opened during
  the check, never against memory or test fixtures alone.

## Output

Report:

- Contract impact: none, compatible, or breaking.
- Required tests.
- Required stop condition if any breaking change is found.

Use Korean-first prose for user-facing summaries, but keep API field names,
paths, commands, and status tokens unchanged.

---
name: api-contract
description: Check Home Search V1 API URL, request, response, unit, and error compatibility before backend/frontend changes.
---

# API Contract Skill

Use this skill when work touches API clients, controllers, DTOs, marker adapters, detail/trade flows, request validation, or error handling.

## Purpose

Keep `apps/api` and `apps/web` compatible with `docs/API_CONTRACT.md` before parallel work begins or lands.

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
- V1/V2 boundary.

## Frontend Rules

- Keep canonical marker fields: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- Accept source variants such as `id`, `latitude`, and `longitude` only inside adapters.
- Do not require backend contract changes for UI redesign.

## Backend Rules

- Return canonical V1 fields.
- Preserve `complex_id` as the operational trade relation.
- Do not expose audit fields such as `complex_pk`, `apt_seq`, `source`, or `source_key` in public trade responses unless the contract is explicitly changed first.
- Map endpoints must not require ranking, trend, favorite, alarm, mail, or auth state.

## Output

Report:

- Contract impact: none, compatible, or breaking.
- Required tests.
- Required stop condition if any breaking change is found.

---
name: frontend-web
description: Guide Home Search apps/web Vite React, Kakao map, API adapter, and map-first UI work.
---


# Frontend Web Skill

Use this skill for `apps/web` frontend planning, implementation, review, or debugging.

## Required Inputs

- Root `AGENTS.md`.
- `apps/web/AGENTS.md`.
- Root `CONTEXT.md`.
- `apps/web/CONTEXT.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md`.
- `docs/UI_UX_MIGRATION.md`.

## Writable Scope

Only `apps/web/**`, unless the user explicitly approves a broader scope.

## Frontend Guardrails

- Preserve V1 API calls for map, search, region, detail, and trade flows.
- Keep API normalization inside adapters.
- Use canonical marker fields: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- During migration, accept `id`, `latitude`, and `longitude` variants only in adapter code.
- Keep map usable on marker API failure.
- Show non-blocking error state for map fetch failures.
- Do not redesign into a marketing page; the primary surface is map exploration.

## Testing

Prefer public seams:

- API adapter normalization.
- Marker transform.
- Component state.
- Map failure fallback.
- Detail drawer and trade list behavior.

## Verification

When `apps/web/package.json` exists, inspect scripts and run existing commands only. Typical checks are `npm run lint` and `npm run build`. Use browser smoke verification for meaningful map UI changes.

## Stop Conditions

Stop before:

- Changing a V1 URL, field name, type, or unit.
- Requiring backend response changes for UI-only work.
- Adding tracking, analytics, secrets, or unrelated external scripts.
- Expanding into V2 ranking, favorite, alarm, mail, recommendation, or auth flows.

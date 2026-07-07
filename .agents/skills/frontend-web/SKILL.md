---
name: frontend-web
description: Guide Home Search apps/web Vite React, Kakao map, API adapter normalization, marker rendering, detail/trade drawer, and map-first UI implementation. Use for "frontend", "React", "Vite", "Kakao map", "marker", "adapter", "drawer", "map UI", "프론트", "지도", "마커", "어댑터", "상세 패널". Do not use for design direction (use home-search-design), contract impact decisions (use api-contract), or failing-command debugging (use systematic-debugging).
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

- Preserve public API calls for map, search, region, detail, and trade flows.
- Keep API normalization inside adapters.
- Use the canonical marker field names owned by `docs/API_CONTRACT.md`; read
  them from that document instead of restating the list here.
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

Inspect `apps/web/package.json` scripts and run existing commands only.

- Canonical checks: `cd apps/web && npm run test` (vitest) and
  `cd apps/web && npm run build`. There is no `lint` script.
- PR evidence for any `apps/web/**` change requires both commands above
  (see `.codex/harness/pr_evidence.py`).
- Run the narrowest vitest target first when iterating, then the full
  `npm run test` before completion claims.
- `npm run test:live-api` is an optional live-backend smoke script; it needs a
  running api and is not part of the PR gate.
- Use browser smoke verification for meaningful map UI changes.

## Stop Conditions

Stop before:

- Changing a public API URL, field name, type, or unit.
- Requiring backend response changes for UI-only work.
- Adding tracking, analytics, secrets, or unrelated external scripts.
- Expanding into later-scope ranking, favorite, alarm, mail, recommendation, or auth flows.

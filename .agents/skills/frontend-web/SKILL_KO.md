# Frontend Web Skill KO

> KO 생성 기준: canonical source only
> Source: `.agents/skills/frontend-web/SKILL.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.agents/skills/frontend-web/SKILL.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

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

- Preserve public API calls for map, search, region, detail, and trade flows.
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

- Changing a public API URL, field name, type, or unit.
- Requiring backend response changes for UI-only work.
- Adding tracking, analytics, secrets, or unrelated external scripts.
- Expanding into later-scope ranking, favorite, alarm, mail, recommendation, or auth flows.

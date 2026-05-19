---
name: frontend-web
description: Home Search apps/web Vite React, Kakao map, API adapter, map-first UI 작업을 안내한다.
---

# Frontend Web Skill

`apps/web` frontend planning, implementation, review, debugging에 이 skill을 사용한다.

## Required Inputs

- Root `AGENTS.md`.
- `apps/web/AGENTS.md`.
- Root `CONTEXT.md`.
- `apps/web/CONTEXT.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md`.
- `docs/UI_UX_MIGRATION.md`.

## Writable Scope

사용자가 더 넓은 범위를 명시 승인하지 않는 한 `apps/web/**`만 수정한다.

## Frontend Guardrails

- map, search, region, detail, trade flows의 V1 API calls를 보존한다.
- API normalization은 adapters 안에 둔다.
- canonical marker fields를 사용한다: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- migration 중에는 `id`, `latitude`, `longitude` variants를 adapter code에서만 허용한다.
- marker API 실패 시에도 map을 사용할 수 있게 유지한다.
- map fetch failures에는 non-blocking error state를 보여준다.
- marketing page로 redesign하지 않는다. primary surface는 map exploration이다.

## Testing

public seams를 우선한다:

- API adapter normalization.
- Marker transform.
- Component state.
- Map failure fallback.
- Detail drawer and trade list behavior.

## Verification

`apps/web/package.json`이 있으면 scripts를 확인하고 기존 commands만 실행한다. 일반적인 checks는 `npm run lint`와 `npm run build`다. 의미 있는 map UI changes에는 browser smoke verification을 사용한다.

## Stop Conditions

다음 전에 중단한다:

- V1 URL, field name, type, unit 변경.
- UI-only work를 위해 backend response changes 요구.
- tracking, analytics, secrets, unrelated external scripts 추가.
- V2 ranking, favorite, alarm, mail, recommendation, auth flows로 확장.

# Frontend Web Skill

이 문서는 `frontend-web` 스킬의 한국어 companion이다. 기준은 영문 `SKILL.md`이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## 목적

`apps/web`의 Vite React, Kakao map, API adapter, map-first UI 작업을 안내한다.

## 필수 입력

Root `AGENTS.md`, `apps/web/AGENTS.md`, root `CONTEXT.md`, `apps/web/CONTEXT.md`, `docs/API_CONTRACT.md`, `docs/MAP_DISPLAY_FLOW.md`, `docs/UI_UX_MIGRATION.md`.

## 수정 가능 범위

사용자가 명시적으로 승인하지 않는 한 `apps/web/**`만 수정한다.

## 프론트엔드 가드레일

Map, search, region, detail, trade flow의 V1 API call을 보존한다. API normalization은 adapter 안에 둔다. Canonical marker field는 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`이다. Migration 중 `id`, `latitude`, `longitude` variant는 adapter에서만 임시 수용한다.

## 검증

`apps/web/package.json`이 있으면 scripts를 확인하고 존재하는 명령만 실행한다. 일반적인 검증 후보는 `npm run lint`, `npm run build`다. Map UI behavior가 바뀌면 browser smoke verification을 사용한다.

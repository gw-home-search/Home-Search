# 프론트엔드 작업 노트

이 문서는 `apps/web`에 Vite React/Kakao map project frontend를 만들거나 수정할 때 참고하는 개인 노트다.

## 읽기 순서

1. `AGENTS.md`
2. `docs/API_CONTRACT.md`
3. `docs/MAP_DISPLAY_FLOW.md`
4. `docs/UI_UX_MIGRATION.md`
5. 관련 target frontend 파일
6. 필요한 경우 source frontend read-only reference

## Project Guardrails

- `/api/v1/map/regions`와 `/api/v1/map/complexes` 호출 호환성을 유지한다.
- search, region, detail, trade API compatibility를 유지한다.
- marker adapter는 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`을 canonical field로 다룬다.
- source migration 중 `id`, `latitude`, `longitude` variant는 adapter에서만 임시 수용한다.
- marker API 실패 시 map usability를 유지하고 non-blocking error state를 보여준다.
- UI/UX 변경은 가능하지만 API contract는 유지한다.

## 작업 루프

1. `App -> map components -> sidebar -> store -> API adapter` 흐름을 확인한다.
2. request/response field와 unit을 `docs/API_CONTRACT.md`와 대조한다.
3. behavior 변경이면 adapter, component, fallback 중심으로 failing test를 먼저 검토한다.
4. map failure, empty result, selected marker state를 확인한다.
5. 변경 후 lint/build와 필요한 browser verification을 남긴다.

## 검증 기준

`apps/web/package.json`이 없으면 문서 검증만 한다. 생성된 뒤에는 scripts를 확인하고 존재하는 명령만 실행한다.

- 기본 후보: `npm run lint`
- 기본 후보: `npm run build`
- source frontend에 test script가 없으므로 임의로 `npm test`를 요구하지 않는다.

## 멈출 조건

API URL, field name, field type, amount unit 변경이 필요하면 사용자 확인 전까지 구현하지 않는다.

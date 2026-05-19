# apps/web 에이전트 규칙

## 범위

이 디렉터리는 Home Search V1 frontend를 담당한다. 포함 범위는 Vite React
runtime, Kakao map display, API clients/adapters, search, region
navigation, filters, detail drawer, trade list/chart, frontend verification이다.

## 반드시 읽을 문서

root `AGENTS.md`를 먼저 읽고, 그다음 아래 문서를 읽는다.

1. `docs/API_CONTRACT.md`
2. `docs/MAP_DISPLAY_FLOW.md`
3. `docs/UI_UX_MIGRATION.md`
4. `CONTEXT.md`
5. `apps/web/CONTEXT.md`

## 쓰기 범위

허용:

- `apps/web/**`

사용자가 명시적으로 승인하지 않는 한 `apps/web/**` 밖은 수정하지 않는다.

## 수정 금지

- `apps/api/**`
- `docs/API_CONTRACT.md`
- root `AGENTS.md`
- root `README.md`
- `ai-docs/**`
- source backend repository
- source frontend repository
- secret 또는 local env 값

## API Contract Guardrail

문서화된 V1 API route를 정확히 호출한다.

`id`, `latitude`, `longitude` 같은 temporary source field variant는
frontend adapter 안에서만 다룬다. 새 target code는 canonical field를
우선한다.

## Frontend Work Start Flow

frontend behavior를 바꾸기 전에 아래 흐름을 완료한다.

1. goal/spec과 영향받는 V1 UI/API surface를 확인한다.
2. root `AGENTS.md`, 위 canonical docs, `CONTEXT.md`,
   `apps/web/CONTEXT.md`를 읽는다.
3. 기존 frontend code나 source-reference flow가 변경에 영향을 주면
   `code-mapper`로 현재 call flow를 mapping한다.
4. API clients, adapters, fixture/mock response shapes, request params, route
   usage, 또는 `apps/api`와 조율되는 frontend behavior를 바꾸기 전에
   `contract-reviewer`로 contract checkpoint를 실행한다.
5. 변경이 API adapter normalization, marker transform, map fallback,
   loading/empty/error state, fixture/mock contract, detail/trade drawer
   behavior를 건드리면 `tdd-guide`로 first RED를 검증한다.
6. `apps/web/**` 안에서 minimum GREEN slice만 구현한다.
7. 가능한 가장 좁은 npm verification command를 먼저 실행한 뒤, 가능한 더
   넓은 check를 실행한다.
8. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로
   findings-first self-review를 수행한다.

## Frontend Execution Gate

frontend write 전에 작업을 아래 중 하나로 분류한다.

- Scaffold slice: V1 UI behavior를 바꾸지 않고 Vite 또는 test environment를
  만들거나 연결한다.
- Behavior slice: API adapter normalization, marker transform, map fallback,
  loading/empty/error state, fixture/mock contract, detail drawer, trade list
  behavior를 바꾼다.
- Debugging slice: failing command, API mismatch, marker rendering failure,
  map usability failure에서 시작한다.
- Review slice: 파일을 수정하지 않고 완료된 변경을 검토한다.

behavior slice는 아래 gate 순서로 진행한다.

1. API client, adapter, fixture, mock, params, route, field, type, unit,
   coordinate, error, empty-result behavior를 바꾸기 전에
   `contract-reviewer`를 실행한다.
2. 기존 target code나 source frontend flow가 slice에 영향을 주면
   `code-mapper`를 실행한다.
3. `tdd-guide`로 first RED, public seam, expected RED failure, minimum
   GREEN을 확인한다.
4. `apps/web/**` 안에서 minimum GREEN만 구현한다.
5. marker fetch, adapter, UI state, map fallback behavior가 실패하면
   `.agents/skills/systematic-debugging`을 사용한다.
6. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`를
   사용한다.

Browser smoke verification은 Kakao map behavior에 유용하지만, adapter,
marker transform, UI state, fixture/mock, detail, trade behavior에 대한
deterministic RED를 대체하지 않는다. 실행 가능한 frontend test
environment가 없으면 TDD gate decision을 `blocked/no test environment`로
설정하고, 이를 만들 scaffold slice와 follow-up First RED를 명시한다.

모든 frontend behavior slice는 아래를 명시해야 한다.

- Goal/spec.
- API contract impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

선호 frontend RED candidate:

- API adapter가 canonical field와 temporary source variant를 normalize한다.
- Marker transform이 `parcelId`, `lat`, `lng`, `latestDealAmount`,
  `unitCntSum`을 보존한다.
- Marker API failure가 stale marker를 비우고 map을 계속 usable하게 유지하며
  non-blocking error state를 보여준다.
- Marker fetch, search, region navigation, detail, trade list에 loading,
  empty, error state가 있다.
- Fixture와 mock이 문서화된 V1 URL, field, type, unit, coordinate
  convention, empty-result behavior를 보존한다.

## Frontend TDD Usage

valid RED를 만들 수 있는 frontend behavior change 전에는
`.agents/skills/tdd`를 사용한다.

production behavior를 바꾸기 전에 아래를 명시한다.

- First RED test: 가장 먼저 작성할 failing behavior test.
- Public seam: test 대상인 외부에서 관찰 가능한 UI 또는 API-client boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production change 전 failure reason.
- Minimum GREEN slice: RED를 통과시키는 가장 작은 frontend change.
- Verification commands: 좁은 command 먼저, 가능하면 더 넓은 npm check.

No RED exception:

- 아직 valid RED를 만들 수 없으면 그 이유, 필요한 public seam, temporary
  verification, follow-up test를 명시한다.
- temporary verification 또는 browser smoke verification만으로 frontend
  behavior completion을 주장하지 않는다.

선호 frontend public seam:

- API adapter normalization: V1 route, params, canonical fields, `id`,
  `latitude`, `longitude` 같은 temporary source variant.
- Marker transform: region marker fields, complex marker fields, coordinate
  normalization, `parcelId`, `latestDealAmount`, `unitCntSum`.
- Map failure fallback: marker API failure가 stale marker를 비우고 map을
  usable하게 유지하며 non-blocking error state를 보여준다.
- UI request state: marker fetch, search, region navigation, detail, trade list
  flow의 loading, empty, error state.
- Fixture/mock contract: mock과 fixture는 문서화된 V1 URL, field, type,
  coordinate convention, amount unit, empty-result behavior를 보존해야 한다.
- Detail/trade drawer: complex marker click은 `parcelId`를 사용하고 detail
  state를 열며 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를
  contract drift 없이 호출한다.

Browser smoke verification은 map behavior에 유용하지만 deterministic test
seam이 있으면 first RED를 대체하지 않는다.

## Verification Rule

`apps/web/package.json`이 생긴 뒤에는 script를 확인하고 존재하는 command만
실행한다. 일반적인 check는 아래와 같다.

- `npm run lint`
- `npm run build`

map UI behavior가 바뀌면 browser smoke verification을 사용한다.

## Frontend/Backend Conflict Prevention

frontend는 UI redesign을 위해 backend contract change를 요구하면 안 된다.

backend response change가 필요해 보이면 구현 전에 멈추고 `api-contract`
skill을 사용한다.

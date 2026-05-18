# apps/web Agent Rules

## Scope

이 디렉터리는 Home Search V1 frontend를 담당한다: Vite React runtime, Kakao map display, API clients/adapters, search, region navigation, filters, detail drawer, trade list/chart, frontend verification.

## Must Read

root `AGENTS.md`를 읽은 뒤 다음을 읽는다.

1. `docs/API_CONTRACT.md`
2. `docs/MAP_DISPLAY_FLOW.md`
3. `docs/UI_UX_MIGRATION.md`
4. `CONTEXT.md`
5. `apps/web/CONTEXT.md`

## Writable Scope

Allowed:

- `apps/web/**`

사용자가 명시적으로 승인하지 않는 한 `apps/web/**` 밖은 수정하지 않는다.

## Do Not Modify

- `apps/api/**`
- `docs/API_CONTRACT.md`
- Root `AGENTS.md`
- Root `README.md`
- `ai-docs/**`
- Source backend repository
- Source frontend repository
- Secrets or local env values

## API Contract Guardrail

문서화된 V1 API route를 그대로 호출한다.

`id`, `latitude`, `longitude` 같은 temporary source field variant는 frontend adapter 안에만 둔다. 새 target code는 canonical field를 선호해야 한다.

## Frontend Work Start Flow

Frontend behavior를 변경하기 전에 이 흐름을 완료한다.

1. Goal/spec과 영향을 받는 V1 UI/API surface를 확인한다.
2. 위에 나열된 root `AGENTS.md`, canonical docs, `CONTEXT.md`, `apps/web/CONTEXT.md`를 읽는다.
3. 기존 frontend code 또는 source-reference flow가 변경에 영향을 주면 `code-mapper`로 현재 call flow를 매핑한다.
4. API client, adapter, fixture/mock response shape, request param, route usage 또는 `apps/api`와 조율된 frontend behavior를 바꾸기 전에 `contract-reviewer`로 contract checkpoint를 수행한다.
5. 변경이 API adapter normalization, marker transform, map fallback, loading/empty/error state, fixture/mock contract, detail/trade drawer behavior를 건드리면 `tdd-guide`로 first RED를 검증한다.
6. `apps/web/**` 안에서 minimum GREEN slice만 구현한다.
7. 가장 좁은 npm verification command를 먼저 실행한 뒤, 사용 가능한 더 넓은 check를 실행한다.
8. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로 findings-first self-review를 수행한다.

모든 frontend behavior slice는 다음을 명시해야 한다.

- Goal/spec.
- API contract impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

선호하는 frontend RED 후보:

- API adapter가 canonical field와 temporary source variant를 normalize한다.
- Marker transform이 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`을 보존한다.
- Marker API failure가 stale marker를 clear하고, map을 usable하게 유지하며, non-blocking error state를 보여준다.
- Marker fetch, search, region navigation, detail, trade list에 loading, empty, error state가 존재한다.
- Fixture와 mock이 문서화된 V1 URL, field, type, unit, coordinate convention, empty-result behavior를 보존한다.

## Frontend TDD Usage

Valid RED를 만들 수 있는 frontend behavior 변경 전에는 `.agents/skills/tdd`를 사용한다.

Production behavior를 바꾸기 전에 다음을 명시한다.

- First RED test: 작성할 첫 failing behavior test.
- Public seam: test 대상인 externally observable UI 또는 API-client boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production 변경 전 failure reason.
- Minimum GREEN slice: RED를 통과시킬 가장 작은 frontend 변경.
- Verification commands: 좁은 command 먼저, 사용 가능하면 더 넓은 npm check.

No RED exception:

- 아직 valid RED를 만들 수 없으면 valid RED가 없는 이유, 필요한 public seam, temporary verification, 추가할 follow-up test를 명시한다.
- Temporary verification 또는 browser smoke verification만으로 frontend behavior completion을 주장하지 않는다.

다음 frontend public seam을 선호한다.

- API adapter normalization: V1 route, params, canonical fields, `id`, `latitude`, `longitude` 같은 temporary source variant.
- Marker transform: region marker fields, complex marker fields, coordinate normalization, `parcelId`, `latestDealAmount`, `unitCntSum`.
- Map failure fallback: marker API failure가 stale marker를 clear하고, map을 usable하게 유지하며, non-blocking error state를 보여준다.
- UI request state: marker fetch, search, region navigation, detail, trade list flow의 loading, empty, error state.
- Fixture/mock contract: mock과 fixture는 문서화된 V1 URL, field, type, coordinate convention, amount unit, empty-result behavior를 보존해야 한다.
- Detail/trade drawer: complex marker click은 `parcelId`를 사용하고 detail state를 열며, contract drift 없이 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를 호출한다.

Browser smoke verification은 map behavior에 유용하지만, deterministic test seam이 있을 때 first RED를 대체하지 않는다.

## Verification Rule

`apps/web/package.json`이 생긴 뒤에는 scripts를 확인하고 존재하는 command만 실행한다. 일반적인 check는 다음과 같다.

- `npm run lint`
- `npm run build`

Map UI behavior가 바뀌면 browser smoke verification을 사용한다.

## Frontend/Backend Conflict Prevention

Frontend는 UI redesign을 위해 backend contract change를 요구하지 않는다.

Backend response change가 필요해 보이면 implementation 전에 멈추고 `api-contract` skill을 사용한다.

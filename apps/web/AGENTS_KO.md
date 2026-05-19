# apps/web Agent Rules

## Scope

이 directory는 Home Search V1 frontend를 담당한다: Vite React runtime, Kakao map display, API clients/adapters, search, region navigation, filters, detail drawer, trade list/chart, frontend verification.

## Must Read

root `AGENTS.md`를 읽은 뒤 다음을 읽는다:

1. `docs/API_CONTRACT.md`
2. `docs/MAP_DISPLAY_FLOW.md`
3. `docs/UI_UX_MIGRATION.md`
4. `CONTEXT.md`
5. `apps/web/CONTEXT.md`

## Writable Scope

허용:

- `apps/web/**`

user가 명시적으로 승인하지 않는 한 `apps/web/**` 밖은 편집하지 않는다.

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

문서화된 대로 V1 API routes를 정확히 호출한다.

`id`, `latitude`, `longitude` 같은 temporary source field variants는 frontend adapters 안에만 둔다. 새 target code는 canonical fields를 선호해야 한다.

## Frontend Work Start Flow

frontend behavior 변경 전에는 다음 flow를 완료한다:

1. goal/spec과 영향을 받는 V1 UI/API surface를 확인한다.
2. root `AGENTS.md`, 위 canonical docs, `CONTEXT.md`, `apps/web/CONTEXT.md`를 읽는다.
3. existing frontend code 또는 source-reference flow가 변경에 영향을 주면 `code-mapper`로 current call flow를 map한다.
4. API clients, adapters, fixture/mock response shapes, request params, route usage 또는 `apps/api`와 조율되는 frontend behavior를 변경하기 전에 `contract-reviewer`로 contract checkpoint를 실행한다.
5. 변경이 API adapter normalization, marker transform, map fallback, loading/empty/error state, fixture/mock contract, detail/trade drawer behavior를 건드리면 `tdd-guide`로 first RED를 validate한다.
6. `apps/web/**` 안에서만 minimum GREEN slice를 구현한다.
7. 가장 좁은 available npm verification command를 먼저 실행하고, 그 다음 broader available checks를 실행한다.
8. completion을 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로 findings-first self-review를 수행한다.

## Frontend Execution Gate

frontend write 전에 작업을 다음 중 하나로 분류한다:

- Scaffold slice: V1 UI behavior를 변경하지 않고 Vite 또는 test environment를 만들거나 wiring한다.
- Behavior slice: API adapter normalization, marker transform, map fallback, loading/empty/error state, fixture/mock contract, detail drawer, trade list behavior를 변경한다.
- Debugging slice: failing command, API mismatch, marker rendering failure, map usability failure에서 시작한다.
- Review slice: completed changes를 편집 없이 inspect한다.

behavior slice의 경우 다음 순서로 gates를 실행한다:

1. API client, adapter, fixture, mock, params, route, field, type, unit, coordinate, error, empty-result behavior changes 전에는 `contract-reviewer`.
2. existing target code 또는 source frontend flow가 slice에 영향을 주면 `code-mapper`.
3. first RED, public seam, expected RED failure, minimum GREEN을 확인하기 위해 `tdd-guide`.
4. `apps/web/**` 안에서만 minimum GREEN 구현.
5. marker fetch, adapter, UI state, map fallback behavior가 실패하면 `.agents/skills/systematic-debugging`.
6. completion을 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`.

Browser smoke verification은 Kakao map behavior에 유용하지만, adapter, marker transform, UI state, fixture/mock, detail, trade behavior에 deterministic RED가 있는 경우 이를 대체하지 않는다. executable frontend test environment가 없으면 TDD gate decision을 `blocked/no test environment`로 설정하고, 필요한 scaffold slice와 follow-up First RED를 명명한다.

모든 frontend behavior slice는 다음을 명시해야 한다:

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

Preferred frontend RED candidates:

- API adapter가 canonical fields와 temporary source variants를 normalize한다.
- Marker transform이 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`을 보존한다.
- Marker API failure가 stale markers를 clear하고 map usable 상태를 유지하며 non-blocking error state를 보여준다.
- marker fetch, search, region navigation, detail, trade list flows에 loading, empty, error states가 존재한다.
- Fixtures와 mocks가 documented V1 URLs, fields, types, units, coordinate conventions, empty-result behavior를 보존한다.

## Frontend TDD Usage

valid RED를 만들 수 있는 frontend behavior changes 전에는 `.agents/skills/tdd`를 사용한다.

production behavior 변경 전에 다음을 말한다:

- First RED test: 먼저 작성할 failing behavior test.
- Public seam: test 대상인 externally observable UI 또는 API-client boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production changes 전 failure reason.
- Minimum GREEN slice: RED를 pass하게 만들 가장 작은 frontend change.
- Verification commands: narrow command 먼저, 이후 가능하면 broader npm checks.

No RED exception:

- valid RED를 아직 만들 수 없으면 이유, 필요한 public seam, temporary verification, follow-up test를 적는다.
- temporary verification 또는 browser smoke verification만으로 frontend behavior completion을 주장하지 않는다.

다음 frontend public seams를 선호한다:

- API adapter normalization: V1 route, params, canonical fields, `id`, `latitude`, `longitude` 같은 temporary source variants.
- Marker transform: region marker fields, complex marker fields, coordinate normalization, `parcelId`, `latestDealAmount`, `unitCntSum`.
- Map failure fallback: marker API failure가 stale markers를 clear하고 map usable 상태를 유지하며 non-blocking error state를 보여준다.
- UI request state: marker fetch, search, region navigation, detail, trade list flows의 loading, empty, error states.
- Fixture/mock contract: mocks와 fixtures는 documented V1 URLs, fields, types, coordinate conventions, amount units, empty-result behavior를 보존해야 한다.
- Detail/trade drawer: complex marker click이 `parcelId`를 사용하고 detail state를 열며 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를 contract drift 없이 호출한다.

Browser smoke verification은 map behavior에 유용하지만 deterministic test seam이 있을 때 first RED를 대체하지 않는다.

## Verification Rule

`apps/web/package.json`이 있으면 scripts를 inspect하고 existing commands만 실행한다. 일반적인 checks:

- `npm run lint`
- `npm run build`

map UI behavior가 바뀌면 browser smoke verification을 사용한다.

## Frontend/Backend Conflict Prevention

Frontend는 UI redesign을 위해 backend contract changes를 요구하면 안 된다.

backend response change가 필요해 보이면 implementation 전에 중단하고 `api-contract` skill을 사용한다.

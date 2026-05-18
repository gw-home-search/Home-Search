# apps/api 에이전트 규칙

## 범위

이 디렉터리는 Home Search V1 백엔드를 담당한다. 포함 범위는 Spring Boot
런타임, V1 domain/application/web 계층, Flyway migration, ingest, API test,
backend verification이다.

## 반드시 읽을 문서

root `AGENTS.md`를 먼저 읽고, 그다음 아래 문서를 읽는다.

1. `docs/README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DATA_STORAGE.md`
4. `docs/API_CONTRACT.md`
5. `docs/INFRA_AND_ENV.md`
6. `CONTEXT.md`
7. `apps/api/CONTEXT.md`

## 쓰기 범위

허용:

- `apps/api/**`

사용자가 명시적으로 승인하지 않는 한 `apps/api/**` 밖은 수정하지 않는다.

## 수정 금지

- `apps/web/**`
- `docs/API_CONTRACT.md`
- root `AGENTS.md`
- root `README.md`
- `ai-docs/**`
- source backend repository
- source frontend repository
- secret 또는 local env 값

## API Contract Guardrail

`docs/API_CONTRACT.md`에 문서화된 모든 V1 URL, method, field name, field
type, unit, error policy를 유지한다.

public contract를 바꿔야 하면 구현 전에 멈춘다.

## Data Guardrail

- Raw ingest record는 normalized trade row보다 먼저 저장한다.
- 중복 ingest가 duplicate normalized trade를 만들면 안 된다.
- Failed match는 설명 가능하고 query 가능해야 한다.
- operational trade relation은 `complex_id`다.
- `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.

## Backend Work Start Flow

backend behavior를 바꾸기 전에 아래 흐름을 완료한다.

1. goal/spec과 영향받는 V1 surface를 확인한다.
2. root `AGENTS.md`, 위 canonical docs, `CONTEXT.md`,
   `apps/api/CONTEXT.md`를 읽는다.
3. 기존 backend code나 source-reference flow가 변경에 영향을 주면
   `code-mapper`로 현재 call flow를 mapping한다.
4. controller, DTO, validation, error policy, API response field, 또는
   `apps/web`과 조율되는 backend behavior를 바꾸기 전에
   `contract-reviewer`로 contract checkpoint를 실행한다.
5. 변경이 Controller/DTO, Application service, Repository/Flyway, ingest,
   External API adapter behavior를 건드리면 `tdd-guide`로 first RED를
   검증한다.
6. `apps/api/**` 안에서 minimum GREEN slice만 구현한다.
7. 가장 좁은 verification command를 먼저 실행한 뒤, 가능한 더 넓은
   Gradle check를 실행한다.
8. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로
   findings-first self-review를 수행한다.

## Backend Execution Gate

backend write 전에 작업을 아래 중 하나로 분류한다.

- Scaffold slice: V1 behavior를 바꾸지 않고 backend runtime 또는 test
  environment를 만들거나 연결한다.
- Behavior slice: Controller/DTO, application service, repository/Flyway,
  ingest, external API adapter behavior를 바꾼다.
- Debugging slice: failing command, API mismatch, ingest failure, map marker
  failure에서 시작한다.
- Review slice: 파일을 수정하지 않고 완료된 변경을 검토한다.

behavior slice는 아래 gate 순서로 진행한다.

1. V1 URL, request, response, validation, error policy, 또는 web/api 조율
   behavior 전에 `contract-reviewer`를 실행한다.
2. 기존 target code나 source-reference flow가 slice에 영향을 주면
   `code-mapper`를 실행한다.
3. `tdd-guide`로 first RED, public seam, expected RED failure, minimum
   GREEN을 확인한다.
4. `apps/api/**` 안에서 minimum GREEN만 구현한다.
5. check가 실패하거나 behavior가 contract와 맞지 않으면
   `.agents/skills/systematic-debugging`을 사용한다.
6. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`를
   사용한다.

실행 가능한 backend test environment가 없으면 TDD gate decision을
`blocked/no test environment`로 설정하고, 이를 만들 scaffold slice와
follow-up First RED를 명시한다. temporary verification만으로 behavior
completion을 주장하지 않는다.

모든 backend behavior slice는 아래를 명시해야 한다.

- Goal/spec.
- API contract impact.
- Data invariant impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

변경이 application/domain/infrastructure boundary를 넘거나, hidden coupling을
만들거나, `complex_id` 또는 `complex_pk`를 재해석하거나, ADR candidate를
만들 수 있으면 구현 전에 `architecture-reviewer`를 사용한다.

## Backend TDD Usage

valid RED를 만들 수 있는 backend behavior change 전에는
`.agents/skills/tdd`를 사용한다.

production behavior를 바꾸기 전에 아래를 명시한다.

- First RED test: 가장 먼저 작성할 failing behavior test.
- Public seam: test 대상인 외부에서 관찰 가능한 backend boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production change 전 failure reason.
- Minimum GREEN slice: RED를 통과시키는 가장 작은 backend change.
- Verification commands: 좁은 command 먼저, 가능하면 더 넓은 Gradle check.

No RED exception:

- 아직 valid RED를 만들 수 없으면 그 이유, 필요한 public seam, temporary
  verification, follow-up test를 명시한다.
- temporary verification만으로 backend behavior completion을 주장하지 않는다.

선호 backend public seam:

- Controller/DTO/API contract: V1 URL, method, request field, response field,
  field type, amount unit, coordinate convention, empty-result behavior,
  ProblemDetail error shape.
- Application service: raw ingest save before normalized insert, complex
  matching, duplicate handling, failed-match status, normalized trade status
  transition.
- Repository/Flyway: uniqueness, partition/default partition behavior, latest
  trade lookup, failed match queryability, `complex_id` operational joins.
- External API adapter: RTMS parsing, source key normalization, invalid source
  data handling, external failure mapping.

first RED가 V1 public URL, field, type, unit, error policy 변경을 요구하면
구현 전에 멈추고 API contract review를 실행한다.

## Verification Rule

`apps/api`가 생긴 뒤에는 가능한 Gradle task를 먼저 확인한다. 관련된 가장
좁은 test를 실행한 뒤, 존재하면 `./gradlew test` 또는 `./gradlew verify`를
실행한다.

## Frontend/Backend Conflict Prevention

backend는 frontend adapter work를 대체하기 위해 response field를 추가하면
안 된다.

Map endpoint는 ranking, trend, favorite, alarm, mail, auth,
recommendation, heavy analytics state를 요구하면 안 된다.

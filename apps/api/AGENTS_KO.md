# apps/api Agent Rules

## Scope

이 directory는 Home Search V1 backend를 담당한다: Spring Boot runtime, V1 domain/application/web layers, Flyway migrations, ingest, API tests, backend verification.

## Must Read

root `AGENTS.md`를 읽은 뒤 다음을 읽는다:

1. `docs/README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DATA_STORAGE.md`
4. `docs/API_CONTRACT.md`
5. `docs/INFRA_AND_ENV.md`
6. `CONTEXT.md`
7. `apps/api/CONTEXT.md`

## Writable Scope

허용:

- `apps/api/**`

user가 명시적으로 승인하지 않는 한 `apps/api/**` 밖은 편집하지 않는다.

## Do Not Modify

- `apps/web/**`
- `docs/API_CONTRACT.md`
- Root `AGENTS.md`
- Root `README.md`
- `ai-docs/**`
- Source backend repository
- Source frontend repository
- Secrets or local env values

## API Contract Guardrail

`docs/API_CONTRACT.md`에 문서화된 모든 V1 URL, method, field name, field type, unit, error policy를 유지한다.

public contract 변경 전에는 중단한다.

## Data Guardrail

- Raw ingest records는 normalized trade rows보다 먼저 저장된다.
- Duplicate ingest는 duplicate normalized trades를 만들면 안 된다.
- Failed matches는 explainable하고 queryable해야 한다.
- operational trade relation은 `complex_id`다.
- `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.

## Backend Work Start Flow

backend behavior 변경 전에는 다음 flow를 완료한다:

1. goal/spec과 영향을 받는 V1 surface를 확인한다.
2. root `AGENTS.md`, 위 canonical docs, `CONTEXT.md`, `apps/api/CONTEXT.md`를 읽는다.
3. existing backend code 또는 source-reference flow가 변경에 영향을 주면 `code-mapper`로 current call flow를 map한다.
4. controllers, DTOs, validation, error policy, API response fields 또는 `apps/web`과 조율되는 backend behavior를 변경하기 전에 `contract-reviewer`로 contract checkpoint를 실행한다.
5. 변경이 Controller/DTO, Application service, Repository/Flyway, ingest, External API adapter behavior를 건드리면 `tdd-guide`로 first RED를 validate한다.
6. `apps/api/**` 안에서만 minimum GREEN slice를 구현한다.
7. 가장 좁은 verification command를 먼저 실행하고, 그 다음 더 넓은 Gradle checks를 실행한다.
8. completion을 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로 findings-first self-review를 수행한다.

## Backend Execution Gate

backend write 전에 작업을 다음 중 하나로 분류한다:

- Scaffold slice: V1 behavior를 변경하지 않고 backend runtime 또는 test environment를 만들거나 wiring한다.
- Behavior slice: Controller/DTO, application service, repository/Flyway, ingest, external API adapter behavior를 변경한다.
- Debugging slice: failing command, API mismatch, ingest failure, map marker failure에서 시작한다.
- Review slice: completed changes를 편집 없이 inspect한다.

behavior slice의 경우 다음 순서로 gates를 실행한다:

1. V1 URL, request, response, validation, error policy, web/api coordinated behavior 전에는 `contract-reviewer`.
2. existing target code 또는 source-reference flow가 slice에 영향을 주면 `code-mapper`.
3. first RED, public seam, expected RED failure, minimum GREEN을 확인하기 위해 `tdd-guide`.
4. `apps/api/**` 안에서만 minimum GREEN 구현.
5. check가 실패하거나 behavior가 contract와 맞지 않으면 `.agents/skills/systematic-debugging`.
6. completion을 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`.

executable backend test environment가 없으면 TDD gate decision을 `blocked/no test environment`로 설정하고, 필요한 scaffold slice와 follow-up First RED를 명명한다. temporary verification을 behavior completion으로 취급하지 않는다.

모든 backend behavior slice는 다음을 명시해야 한다:

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

변경이 application/domain/infrastructure boundaries를 넘거나, hidden coupling을 만들거나, `complex_id` 또는 `complex_pk`를 reinterpret하거나, ADR candidate를 만들 수 있으면 implementation 전에 `architecture-reviewer`를 사용한다.

## Backend TDD Usage

valid RED를 만들 수 있는 backend behavior changes 전에는 `.agents/skills/tdd`를 사용한다.

production behavior 변경 전에 다음을 말한다:

- First RED test: 먼저 작성할 failing behavior test.
- Public seam: test 대상인 externally observable backend boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production changes 전 failure reason.
- Minimum GREEN slice: RED를 pass하게 만들 가장 작은 backend change.
- Verification commands: narrow command 먼저, 이후 가능하면 broader Gradle checks.

No RED exception:

- valid RED를 아직 만들 수 없으면 이유, 필요한 public seam, temporary verification, follow-up test를 적는다.
- temporary verification만으로 backend behavior completion을 주장하지 않는다.

다음 backend public seams를 선호한다:

- Controller/DTO/API contract: V1 URL, method, request field, response field, field type, amount unit, coordinate convention, empty-result behavior, ProblemDetail error shape.
- Application service: raw ingest save before normalized insert, complex matching, duplicate handling, failed-match status, normalized trade status transition.
- Repository/Flyway: uniqueness, partition/default partition behavior, latest trade lookup, failed match queryability, `complex_id` operational joins.
- External API adapter: RTMS parsing, source key normalization, invalid source data handling, external failure mapping.

first RED가 V1 public URL, field, type, unit, error policy 변경을 요구한다면 implementation 전 중단하고 API contract review를 실행한다.

## Verification Rule

`apps/api`가 생기면 먼저 available Gradle tasks를 inspect한다. 가장 좁은 relevant test를 실행한 뒤, 있으면 `./gradlew test` 또는 `./gradlew verify`를 실행한다.

## Frontend/Backend Conflict Prevention

Backend는 frontend adapter work를 대신하기 위해 response fields를 추가하면 안 된다.

Map endpoints는 ranking, trend, favorite, alarm, mail, auth, recommendation, heavy analytics state를 요구하면 안 된다.

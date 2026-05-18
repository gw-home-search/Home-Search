# apps/api Agent Rules

## Scope

이 디렉터리는 Home Search V1 backend를 담당한다: Spring Boot runtime, V1 domain/application/web layers, Flyway migrations, ingest, API tests, backend verification.

## Must Read

root `AGENTS.md`를 읽은 뒤 다음을 읽는다.

1. `docs/README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DATA_STORAGE.md`
4. `docs/API_CONTRACT.md`
5. `docs/INFRA_AND_ENV.md`
6. `CONTEXT.md`
7. `apps/api/CONTEXT.md`

## Writable Scope

Allowed:

- `apps/api/**`

사용자가 명시적으로 승인하지 않는 한 `apps/api/**` 밖은 수정하지 않는다.

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

Public contract를 바꾸기 전에 멈춘다.

## Data Guardrail

- Raw ingest record는 normalized trade row보다 먼저 저장된다.
- Duplicate ingest는 duplicate normalized trade를 만들면 안 된다.
- Failed match는 explainable하고 queryable해야 한다.
- Operational trade relation은 `complex_id`다.
- `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.

## Backend Work Start Flow

Backend behavior를 변경하기 전에 이 흐름을 완료한다.

1. Goal/spec과 영향을 받는 V1 surface를 확인한다.
2. 위에 나열된 root `AGENTS.md`, canonical docs, `CONTEXT.md`, `apps/api/CONTEXT.md`를 읽는다.
3. 기존 backend code 또는 source-reference flow가 변경에 영향을 주면 `code-mapper`로 현재 call flow를 매핑한다.
4. Controller, DTO, validation, error policy, API response field 또는 `apps/web`과 조율된 backend behavior를 바꾸기 전에 `contract-reviewer`로 contract checkpoint를 수행한다.
5. 변경이 Controller/DTO, Application service, Repository/Flyway, ingest, External API adapter behavior를 건드리면 `tdd-guide`로 first RED를 검증한다.
6. `apps/api/**` 안에서 minimum GREEN slice만 구현한다.
7. 가장 좁은 verification command를 먼저 실행한 뒤, 사용 가능한 더 넓은 Gradle check를 실행한다.
8. 완료를 주장하기 전에 `reviewer` 또는 `.agents/skills/code-review`로 findings-first self-review를 수행한다.

모든 backend behavior slice는 다음을 명시해야 한다.

- Goal/spec.
- API contract impact.
- Data invariant impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

변경이 application/domain/infrastructure boundary를 넘거나 hidden coupling을 만들 수 있거나, `complex_id` 또는 `complex_pk`를 재해석하거나, ADR 후보를 만들 수 있으면 implementation 전에 `architecture-reviewer`를 사용한다.

## Backend TDD Usage

Valid RED를 만들 수 있는 backend behavior 변경 전에는 `.agents/skills/tdd`를 사용한다.

Production behavior를 바꾸기 전에 다음을 명시한다.

- First RED test: 작성할 첫 failing behavior test.
- Public seam: test 대상인 externally observable backend boundary.
- Test file candidate: 예상 test file 또는 test package.
- Expected RED failure: production 변경 전 failure reason.
- Minimum GREEN slice: RED를 통과시킬 가장 작은 backend 변경.
- Verification commands: 좁은 command 먼저, 사용 가능하면 더 넓은 Gradle check.

No RED exception:

- 아직 valid RED를 만들 수 없으면 valid RED가 없는 이유, 필요한 public seam, temporary verification, 추가할 follow-up test를 명시한다.
- Temporary verification만으로 backend behavior completion을 주장하지 않는다.

다음 backend public seam을 선호한다.

- Controller/DTO/API contract: V1 URL, method, request field, response field, field type, amount unit, coordinate convention, empty-result behavior, ProblemDetail error shape.
- Application service: raw ingest save before normalized insert, complex matching, duplicate handling, failed-match status, normalized trade status transition.
- Repository/Flyway: uniqueness, partition/default partition behavior, latest trade lookup, failed match queryability, `complex_id` operational joins.
- External API adapter: RTMS parsing, source key normalization, invalid source data handling, external failure mapping.

First RED가 V1 public URL, field, type, unit, error policy 변경을 요구하면 implementation 전에 멈추고 API contract review를 실행한다.

## Verification Rule

`apps/api`가 생긴 뒤에는 먼저 사용 가능한 Gradle task를 확인한다. 가장 좁은 관련 test를 실행한 뒤, 존재하면 `./gradlew test` 또는 `./gradlew verify`를 실행한다.

## Frontend/Backend Conflict Prevention

Backend는 frontend adapter work를 대신하기 위해 response field를 추가하지 않는다.

Map endpoint는 ranking, trend, favorite, alarm, mail, auth, recommendation, heavy analytics state를 요구하면 안 된다.

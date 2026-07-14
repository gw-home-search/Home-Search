# Java/Spring Boot Modernization Ledger

## 실행 기준

- baseline commit: `d601237`
- 현재 진행 PR: `PR 13 — quality gates and final docs`
- 전체 상태: `In Progress`
- 시작일: 2026-07-14
- 실행 원칙: 한 번에 하나의 PR만 진행하고, 선행 PR이 `Complete`인 경우에만 다음 PR을 시작한다.

## 고정 계약

- `docs/API_CONTRACT.md`의 property-data public URL, method, request/response field, unit, coordinate, error shape를 유지한다.
- `complexName`, optional `displayName`, map marker source `name`의 의미를 유지한다.
- parent 없음은 `404`, parent가 있으나 child/result 없음은 `200` empty list/page다.
- search, region, trade의 기존 default와 clamp를 유지한다.
- invalid parameter detail은 `"Invalid parameter format."`을 유지한다.
- `ProblemDetail.exception`과 UTC offset `timestamp`를 유지한다.
- user OAuth URL, refresh cookie flags, JWT issuer/audience/kid, favorite idempotency/pagination을 유지한다.
- admin Session/RBAC/CSRF/internal JWT 및 admin/source-data CLI operation/exit code를 유지한다.
- raw-first, duplicate-safe ingest, failed-match queryability, operational `complex_id` relation을 유지한다.

## 금지 사항

- public API URL, field, status, error detail, clamp 변경
- applied migration SQL/checksum/history 변경
- `complex_id`/`complex_pk` 재해석 또는 data-loss migration
- domain의 Spring/JPA/JDBC/web/infrastructure 의존
- runtime Flyway 재도입, 기존 env/secret 계약 변경, Docker volume 삭제
- 선행 PR 없이 stacked PR 진행

## Baseline

### Dependency graph

```text
property-data: api -> core -> libs/rtms-ingest-core
               api -> libs/security-jwt-core
               batch -> core
user-service:  app -> core
               app -> libs/security-jwt-core, libs/user-auth-contract
admin-service: api -> core, libs/security-jwt-core
               migration / ops are explicit run-and-exit modes
source-data:   single Spring Boot migration/operation application
```

Baseline runtime/build versions:

- property-data/admin/source-data: Spring Boot `3.5.7`, Java `17`
- user-service: Spring Boot `4.1.0`, Java `21`
- Gradle wrapper: `9.4.1`

### Structural metrics

Production Java source 기준이다. LOC는 production/test Java physical line 수다.

| Service | `@Configuration` class | `@Bean` | `@Value` | `ObjectProvider` token | production `Empty*` class | application framework import | production LOC | test LOC |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| property-data | 29 | 92 | 123 | 84 | 2 | 0 | 22,607 | 24,395 |
| user-service | 2 | 12 | 17 | 0 | 0 | 0 | 1,324 | 1,147 |
| admin-service | 2 | 7 | 9 | 0 | 0 | 0 | 1,301 | 920 |
| source-data | 0 | 0 | 0 | 0 | 0 | 0 | 312 | 284 |

property-data configuration file distribution:

- `api`: external complex 1, observability 1, scheduling 2, web 2
- `batch`: metadata 1, RTMS 1
- `core`: batch 1, external 7, observability/notification 2, persistence 12
- production `Empty*`: `EmptyPropertyReadRepository`, `EmptyMapUseCase`

## PR 상태

| PR | Slice | 상태 | 다음 진입 조건 |
| ---: | --- | --- | --- |
| 1 | governance와 contract baseline | Complete | fresh baseline, docs, characterization tests GREEN |
| 2 | property read capability split | Complete | PR 1 `Complete` |
| 3 | read snapshot과 DTO 경로 | Complete | PR 2 `Complete` |
| 4 | coordinate/ingest atomic workflows | Complete | PR 3 `Complete` |
| 5 | Java 21 / Boot 3.5 bridge | Complete | PR 4 `Complete` |
| 6 | format baseline | Complete | PR 5 `Complete` |
| 7 | Boot 4.1 / Jackson 3 | Complete | PR 6 `Complete` |
| 8 | map SQL readability | Complete | PR 7 `Complete` |
| 9 | property-data typed wiring | Complete | PR 8 `Complete` |
| 10 | error/validation/executor/observability | Complete | PR 9 `Complete` |
| 11 | user-service Spring/JPA cleanup | Complete | PR 10 `Complete` |
| 12 | admin/source-data modernization | Complete | PR 11 `Complete` |
| 13 | quality gates와 final docs | In Progress | PR 12 `Complete` |

## PR 1 Evidence

### TDD 근거

- 최초 RED: waiver
- 예상 RED 실패: 해당 없음
- waiver 이유: production behavior가 없는 governance/documentation 및 기존 API 동작 characterization 작업이다.
- 최소 GREEN: 기존 controller/application behavior를 테스트로 고정하고 production 코드는 변경하지 않는다.

### 검증 근거 확인

- `apps/property-data`: `./gradlew :core:test :api:test :batch:test --rerun-tasks --no-daemon --stacktrace` — `Pass` (2026-07-14)
- `apps/user/service`: `./gradlew :core:test :app:test --rerun-tasks --no-daemon --stacktrace` — `Pass` (2026-07-14)
- `apps/admin/service`: `./gradlew test --rerun-tasks --no-daemon --stacktrace` — `Pass` (2026-07-14, shared included-build 경합을 피한 단독 재실행)
- `apps/source-data`: `./gradlew test --rerun-tasks --no-daemon --stacktrace` — `Pass` (2026-07-14)
- `apps/property-data`: `./gradlew :api:apiContractTest --tests 'com.home.infrastructure.web.read.ReadApiControllerContractTest' --rerun-tasks --no-daemon --stacktrace` — `Pass`
- `apps/property-data`: `./gradlew backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 43s, persistence/coverage/fresh Flyway/REST Docs/OpenAPI/packaged Batch 포함)
- repository root: `git diff --check` — `Pass`
- repository root: `git diff --name-only d601237 -- '*/db/migration/**' 'apps/*/db/migration/**'` — 변경 0건
- repository root: diff credential/secret pattern 검사 — 지적사항 없음
- `python3 .codex/harness/pr_lint.py --self-test` — `Pass`
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`

### 검증 공백

- 없음

### 잔여 위험

- 여러 Gradle build가 같은 included build output을 동시에 `--rerun-tasks`로 갱신하면 consumer compile이 일시 실패할 수 있다. 서비스 gate는 공유 included build가 있을 때 순차 실행한다.

### 보안 영향

- 검증 범위: 변경된 canonical docs와 controller characterization tests, migration diff, credential/secret pattern을 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 없음
- 검증 근거 확인: narrow contract test와 `backendQualityCheck`가 GREEN이다.
- 검증 공백: 없음
- 잔여 위험: 후속 PR은 구조 변경마다 동일 contract/gate 검증을 반복해야 한다.

### Merge

- merge commit: `31f0b58b5597d0de9684d869588ce5325a0872be`

## PR 2 Evidence

### TDD 근거

- 최초 RED: waiver
- 예상 RED 실패: 해당 없음
- waiver 이유: SQL과 public behavior를 변경하지 않는 package/capability 구조 분할이며 기존 characterization tests가 회귀 seam이다.
- 최소 GREEN: search, region-navigation, property-detail, trade-history service/reader/adapter/controller로 분리하고 기존 contract/persistence tests를 유지한다.

### 계약 영향

- `none`: URL, method, field, unit, error, empty/404, clamp 계약을 변경하지 않는다.

### 검증 근거 확인

- 변경 전 `:core:test --tests com.home.application.read.PropertyReadUseCaseTest --rerun-tasks` — `Pass` (11s)
- 변경 전 `:api:apiContractTest --tests com.home.infrastructure.web.read.ReadApiControllerContractTest --rerun-tasks` — `Pass` (13s)
- 변경 전 `:core:persistenceTest --tests com.home.infrastructure.persistence.read.JdbcPropertyReadRepositoryTest --rerun-tasks` — `Pass` (1m 16s)
- `:core:test --tests com.home.application.read.ReadCapabilityServicesTest :api:apiContractTest --tests com.home.infrastructure.web.read.ReadApiControllerContractTest --rerun-tasks` — `Pass` (16s)
- `:core:persistenceTest --tests com.home.infrastructure.persistence.read.JdbcReadCapabilityReadersTest --tests com.home.infrastructure.persistence.ingest.IngestToReadPathJdbcIntegrationTest --tests com.home.infrastructure.persistence.ingest.RtmsStorageQualityJdbcIntegrationTest --rerun-tasks` — `Pass` (1m 41s)
- `:core:test :api:test :batch:test --rerun-tasks` — 첫 실행은 no-DB full context가 mandatory JDBC adapter를 생성하려 해 `Fail`; production fallback을 복원하지 않고 두 no-DB test context에 새 JDBC adapter mock을 추가한 뒤 `Pass` (1m 1s)
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 4s, persistence/coverage/fresh Flyway/REST Docs/OpenAPI/packaged Batch 포함)
- 제거 대상 참조 검사 — `PropertyReadUseCase`, `PropertyReadRepository`, `JdbcPropertyReadRepository`, production `Integer.MAX_VALUE` paging 모두 0건
- repository root `git diff --check` — `Pass`
- migration diff — 변경 0건
- credential/secret added-line pattern 검사 — 지적사항 없음
- `python3 .codex/harness/pr_lint.py --self-test` — `Pass`
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`

### 검증 공백

- 없음

### 잔여 위험

- parent 존재 확인, count, content가 아직 여러 statement를 사용하므로 concurrent snapshot 일관성은 PR 3에서 다룬다. 이번 PR은 기존 isolation과 SQL 의미를 변경하지 않았다.

### 보안 영향

- 검증 범위: 새 JDBC adapter의 named parameter binding, 동적 SQL 입력 부재, public error/DTO 불변, migration/credential diff를 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 없음
- 검증 근거 확인: capability별 service/reader/controller 의존, persistence characterization, API contract/OpenAPI, no-DB composition을 확인했다.
- 검증 공백: 없음
- 잔여 위험: snapshot consistency는 승인된 다음 slice인 PR 3 범위다.

### Merge

- implementation commit: `8ddc7c0512c09baed746ad2973d75c3aa673596f`
- merge commit: `393d0df43ac4e605e91dbd3ee621d13de240628d`

## PR 3 Evidence

### TDD 근거

- 최초 RED: `TradeHistorySnapshotConsistencyTest`에서 실제 PostgreSQL connection을 교차 실행했다. 첫 fixture 실행은 duplicate natural key로 assertion 전에 실패해 RED에서 제외했고, fixture 수정 후 유효한 RED를 확인했다.
- 예상 RED 실패: parent 확인 뒤 첫 trade commit, count 뒤 두 번째 trade commit을 강제하자 `totalElements=3`, content size `4`로 서로 다른 snapshot이 관찰됐다.
- 최소 GREEN: region/trade composite public service method에 read-only `REPEATABLE_READ` transaction을 적용했다. 같은 테스트에서 initial `totalElements=2`, content size `2`, active transaction, `repeatable read` isolation을 확인했다.

### 계약 영향

- `none`: Optional parent 의미, public URL/JSON, `complexName`/`displayName`, page/size/limit clamp를 유지한다.

### 검증 근거 확인

- 변경 전 `:core:test --tests com.home.application.read.ReadCapabilityServicesTest :api:apiContractTest --tests com.home.infrastructure.web.read.ReadApiControllerContractTest --rerun-tasks` — `Pass` (17s)
- 최초 유효 RED `:core:persistenceTest --tests com.home.infrastructure.persistence.read.TradeHistorySnapshotConsistencyTest --rerun-tasks` — `Fail` (예상대로 `3L` vs `4L`)
- 최소 GREEN 동일 snapshot test — `Pass` (44s)
- read persistence/API/REST Docs combined narrow gate — `Pass` (1m 28s)
- `:core:test :api:test :batch:test --rerun-tasks` — `Pass` (1m 1s)
- `:api:compileJava :batch:compileJava --rerun-tasks --warning-mode all` — `Pass`; `Isolation` annotation metadata 경고 제거를 위해 core의 `spring-tx`를 `api` dependency로 노출했다. 잔여 경고는 기존 Asciidoctor plugin의 Gradle 10 deprecation이다.
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 4s, persistence/coverage/fresh Flyway/REST Docs/OpenAPI/packaged Batch 포함)
- review 수정 후 transaction/API narrow gate — `Pass` (53s)
- controller의 read response 직접 constructor 호출 — 0건
- application production Spring import — `@Service`, `@Transactional`만 존재
- repository root `git diff --check` — `Pass`
- migration diff — 변경 0건
- credential/secret added-line pattern 검사 — 지적사항 없음
- `python3 .codex/harness/pr_lint.py --self-test` — `Pass`
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`

### 검증 공백

- 없음

### 잔여 위험

- `REPEATABLE_READ`가 긴 read transaction에서 만드는 MVCC 비용은 운영 지표로 관찰한다. 단일 CTE/`COUNT(*) OVER()` 최적화는 측정 근거가 생길 때 별도 PR로 검토한다.

### 보안 영향

- 검증 범위: transaction 범위가 read-only인지, production SQL/parameter binding과 public error가 변경되지 않았는지, migration/credential diff가 없는지 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: application의 허용 Spring import 경계를 벗어난 `Isolation` import 1건을 발견해 fully-qualified annotation value로 수정했다. 미해결 지적사항은 없다.
- 검증 근거 확인: concurrent snapshot RED/GREEN, annotation reflection, API contract/REST Docs/OpenAPI, full backend gate가 GREEN이다.
- 검증 공백: 없음
- 잔여 위험: 운영 MVCC 비용은 후속 관찰 대상이다.

### Merge

- implementation commit: `914a9585261b67bd66c79550d7b6e683f8100d14`
- merge commit: `3510ea7d72c7966dc0b432bc8fcdfcc004762aa8`

## PR 4 Evidence

### TDD 근거

- 최초 RED: PostgreSQL trigger로 display coordinate insert와 raw terminal status update에 예외를 주입했다.
- 예상 RED 실패: coordinate RED에서는 display coordinate 실패 뒤 `complex_building_link` 1건이 남았다. ingest RED에서는 raw가 `RECEIVED`인 상태에서 match evidence와 normalized trade가 이미 commit돼 rollback assertion이 실패했다.
- 최소 GREEN: 외부 좌표 계산 뒤 immutable command를 `CoordinateResolutionCommitter`가 한 transaction으로 확정한다. ingest는 `RawReceiptService.REQUIRES_NEW` 뒤 `TradeIngestFinalizer`가 dedupe/cancel/parse/match/evidence/normalized write/raw terminal transition을 한 transaction으로 확정한다.

### 계약 영향

- `none`: public URL/JSON/error/clamp, `complex_id`/`complex_pk`, source identity, raw-first와 failed-match queryability를 유지한다.
- migration SQL과 checksum은 변경하지 않았다.

### 검증 근거 확인

- 변경 전 coordinate/ingest unit/persistence narrow gate — `Pass` (1m 2s)
- coordinate 최초 RED — `Fail` (예상대로 link 1건이 partial commit됨, 48s)
- coordinate 최소 GREEN과 retry — `Pass` (47s)
- ingest 최초 RED — `Fail` (예상대로 normalized/evidence partial state가 남음, 46s)
- ingest 최소 GREEN — `Pass` (45s)
- coordinate/ingest/reconciliation combined narrow gate — `Pass` (1m 8s)
- constructor/wiring 정리 후 atomic workflow narrow gate — `Pass` (1m 19s)
- `:core:test :api:test :batch:test --rerun-tasks --no-daemon --stacktrace` — `Pass` (1m 2s)
- `BaselineRuntimeSmokeTest --rerun-tasks` — `Pass` (1m 1s)
- `ComplexCoordinatePersistenceConfigurationTest` — `Pass`; dual-port JDBC adapter를 single concrete bean으로 등록해 후보 모호성을 제거했다.
- ARM64에서 AMD64 PostGIS cold start가 기존 60초 timeout을 반복 초과한 환경 실패를 재현했고, test support timeout을 3분으로 확장한 뒤 기존 `JdbcBuildingMetadataEvidenceRepositoryTest`가 `Pass` (8m 22s)했다.
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 20s, full persistence/coverage/fresh Flyway/REST Docs/OpenAPI/packaged Batch 포함)
- quality gate 후 transaction propagation/proxy 가능 조건 reflection test — `Pass` (11s)
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`
- repository root `git diff --check` — `Pass`
- migration diff — 변경 0건
- production application Spring import — `@Service`, `@Transactional`만 존재
- removed active-trade-only reconciliation type/reference — 0건
- credential/secret added-line pattern 검사 — 지적사항 없음

### 검증 공백

- 없음

### 잔여 위험

- reconciliation은 한 row의 예상하지 못한 runtime exception에서 현재 batch를 중단하고 raw `RECEIVED`를 유지한다. 재시도 가능성은 보존되며, row별 실패 metric/log와 continue 정책은 PR 10 observability 범위에서 결정한다.
- ARM64에서는 AMD64 PostGIS migration fixture가 느리다. startup timeout만 늘렸으며 image/schema/test assertion은 변경하지 않았다.

### 보안 영향

- 검증 범위: raw payload가 log/응답에 노출되지 않는지, replay limit이 유지되는지, 새 SQL 문자열 결합이나 credential/migration 변경이 없는지, 실패 시 partial public trade/coordinate가 rollback되는지 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: coordinate dual-port adapter bean 후보 모호성 1건, test convenience constructor가 unproxied committer/finalizer를 만들 수 있는 위험 2건, ARM64 Testcontainers startup timeout 2곳을 발견해 수정했다.
- 검증 근거 확인: coordinate link/display/case rollback과 retry, raw-first/finalizer rollback, match evidence/cancellation/duplicate rollback, unrestricted recoverable `RECEIVED` replay를 실제 PostgreSQL에서 확인했다.
- 검증 공백: 없음
- 잔여 위험: reconciliation row별 continue/observability 정책은 PR 10에서 다룬다.

### Merge

- implementation commit: `1d374b6b455707dbcfa8c16ea0b7f3859e106022`
- merge commit: `f8dff667fe74ff46c02097821c6991a6cc33bcb9`

## PR 5 Evidence

### TDD 근거

- 최초 RED: waiver. 이 PR은 승인된 build/runtime platform bridge이며 production behavior를 변경하지 않는다.
- 예상 RED 실패: 해당 없음. 변경 전 네 Java service의 fresh test baseline을 먼저 고정한다.
- 최소 GREEN: Java toolchain/runtime을 21로 통일하고 property-data/admin/source-data만 Spring Boot 3.5.16으로 이동한다.

### 계약 영향

- `none`: public API, JSON, error, transaction, SQL, migration, env/secret 이름을 변경하지 않는다.

### 검증 근거 확인

- 변경 전 property-data `:core:test :api:test :batch:test --rerun-tasks` — `Pass` (1m 19s)
- 변경 전 user-service `:core:test :app:test --rerun-tasks` — `Pass` (41s)
- 변경 전 admin-service `test --rerun-tasks` — `Pass` (50s)
- 변경 전 source-data `test --rerun-tasks` — `Pass` (15s)
- 변경 후 property-data `:core:test :api:test :batch:test --rerun-tasks --warning-mode all` — `Pass` (1m 40s)
- 변경 후 user-service `:core:test :app:test --rerun-tasks --warning-mode all` — `Pass` (52s)
- 변경 후 admin-service `test --rerun-tasks --warning-mode all` — `Pass` (1m 6s)
- 변경 후 source-data `test --rerun-tasks --warning-mode all` — `Pass` (42s)
- property-data API/Batch packaged smoke와 runtime migration boundary — `Pass` (19s)
- admin API runtime migration boundary와 migration/ops packaged smoke — `Pass` (10s)
- source-data migration packaged smoke — `Pass` (10s)
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 43s)
- `userServiceQualityCheck --no-daemon --stacktrace` — `Pass` (2m 6s)
- admin `test persistenceTest apiContractTest securityTest --rerun-tasks` — `Pass` (42s)
- source-data `check --rerun-tasks` — `Pass` (1m 15s)
- Boot JAR manifest — property API/Batch, admin API/migration/ops, source migration `3.5.16`; user app `4.1.0`
- compiled production classfile — `major version 65` (Java 21)
- base + Batch override Compose `config --quiet` — `Pass`
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`
- Java 17 toolchain/CI/container reference — production config 0건
- migration diff — 변경 0건
- repository root `git diff --check` — `Pass`

### 검증 공백

- 실제 local service container recreate는 하지 않았다. Compose의 image contract와 packaged JAR를 검증했다.

### 잔여 위험

- Asciidoctor/Grolifant plugin이 Gradle 10에서 제거될 `StartParameter.isConfigurationCacheRequested`를 사용한다. project build script 경고는 아니며 PR 7/도구 전환 시 재검토한다.
- admin test 두 곳의 기존 deprecated API compiler note는 production API 제거 경고가 아니며 PR 12에서 test source와 함께 정리한다.

### 보안 영향

- 검증 범위: 인증/JWT/cookie/env/secret 계약과 runtime Flyway boundary가 바뀌지 않았는지, dependency·container 변경에 credential 추가가 없는지 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: source-data가 독립 CI job 없이 backend change로 분류되는 기존 검증 공백을 확인했다. 이번 변경은 local `check`와 packaged smoke로 검증했고, source/admin aggregate quality task 및 CI 연결은 계획된 PR 13에서 해소한다.
- 검증 근거 확인: Java 21 toolchain/CI/container 설정, Boot manifest, 네 service gate, packaged exit code, API/runtime migration boundary, migration diff를 확인했다.
- 검증 공백: 실제 container recreate 및 원격 CI는 실행하지 않았다.
- 잔여 위험: third-party Gradle deprecation과 source-data CI aggregate 공백은 위 후속 PR에서 추적한다.

### Merge

- implementation commit: `8d34383e3bfcaf06af234cb4e9f5e1bf41e9d74d`
- merge commit: `ad39968a51bf461dd803b2d39b39557441a06df8`

## PR 6 Evidence

### TDD 근거

- 최초 RED: formatter 설정만 추가한 뒤 네 service root에서 `spotlessCheck`를 실행했다.
- 예상 RED 실패: property-data 530개, user-service 84개를 포함해 기존 Java source의 format violation으로 실패했다. formatter configuration/runtime 오류는 없었다.
- 최소 GREEN: Spotless 8.8.0과 Palantir Java Format 2.96.0으로 Java source만 기계적으로 포맷하고, 모든 build의 `spotlessCheck`를 GREEN으로 만들었다.

### 계약 영향

- `none`: production behavior, public API, SQL, migration, dependency version, env/secret contract를 변경하지 않았다.

### Commit 분리

- formatter 설정: `b49f0af3556750160621cdfd9b8adbf8b67fc9a2`
- Java mechanical formatting: `6097e374e0dbcecb76f45db45610cdcb7780b615`
- `.git-blame-ignore-revs`: `914c5aaa9e8abd1a6e8434226e281677a25f3dc0`
- CI/gate 연결: `a673dd2c4762066275f736b10f2fb39f7928fe31`

### 검증 근거 확인

- formatter 대상 — Java 682개; mechanical commit의 non-Java 변경 0건
- property-data + included libraries `spotlessCheck --rerun-tasks` — `Pass` (17s)
- user-service + included libraries `spotlessCheck --rerun-tasks` — `Pass` (14s)
- admin-service + included library `spotlessCheck --rerun-tasks` — `Pass` (13s)
- source-data `check --dry-run` — `spotlessCheck` 연결 확인
- 포맷 후 property-data fresh compile/test + Spotless — `Pass` (1m 36s)
- 포맷 후 user-service fresh compile/test + Spotless — `Pass` (1m)
- 포맷 후 admin-service fresh test + Spotless — `Pass` (1m 6s)
- 포맷 후 source-data fresh test + Spotless — `Pass` (25s)
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 24s; Spotless/coverage/persistence/Flyway/REST Docs/OpenAPI/packaged Batch 포함)
- user-service 단독 `userServiceQualityCheck --rerun-tasks` — `Pass` (2m 6s)
- admin `spotlessCheck test persistenceTest apiContractTest securityTest --rerun-tasks` — `Pass` (39s)
- source-data `check --rerun-tasks` — `Pass` (1m 10s)
- `python3 .codex/harness/pr_lint.py --body-only --body-env PR_BODY` — `Pass`
- repository root `git diff --check` — `Pass`
- migration diff — 변경 0건

### 검증 공백

- 원격 CI는 실행하지 않았다. local task graph에서 property/user/admin/source와 세 included library의 Spotless 연결을 확인했다.

### 잔여 위험

- 여러 독립 Gradle build를 같은 worktree에서 동시에 `--rerun-tasks`로 실행하면 공유 included build output에 경합이 생길 수 있다. 실제 user/admin 병렬 실행에서 재현했고, user 단독 재실행은 GREEN이었다. GitHub job은 별도 checkout을 사용한다.

### 보안 영향

- 검증 범위: formatter/CI 외 dependency나 runtime 설정이 바뀌지 않았는지, secret/credential 파일이 포맷 대상에 포함되지 않았는지, API/security test가 동일하게 통과하는지 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 공용 included build를 공유하는 독립 Gradle process 병렬 실행 경합 1건을 확인했다. repository code 결함이 아니며 aggregate 검증은 단독/순차 실행으로 확정했다.
- 검증 근거 확인: mechanical commit이 Java file만 포함하는지, ignore-rev가 정확한 SHA인지, Spotless가 root/CI gate에 연결됐는지, 포맷 전후 compile/test/API spec이 동일하게 GREEN인지 확인했다.
- 검증 공백: 원격 CI 미실행.
- 잔여 위험: local automation은 공용 included build를 쓰는 service gate를 같은 worktree에서 병렬 `--rerun-tasks`하지 않아야 한다.

### Merge

- implementation ledger commit: `228ebce`
- merge commit: `bdeaae23cb2b845b5543a540038efe6159cc472b`

## PR 7 Evidence

### TDD 근거

- 최초 RED: property-data `:core:compileJava --rerun-tasks`가 Jackson 2 data-binding/core import 100건 이상을 찾지 못해 compile 실패했다.
- 예상 RED 실패: Jackson 3 package, Spring Batch 6 package/record API, Boot 4 auto-configuration/test slice package incompatibility다.
- 최소 GREEN: data binding을 `tools.jackson`으로 옮기고, Batch 6 API와 Boot 4 기능별 starter/test starter만 적용했다. 공개 DTO와 SQL은 변경하지 않았다.

### 계약 영향

- `none`: public URL, JSON field, status, error detail, clamp, OAuth/cookie/JWT claim, CLI operation/exit code를 변경하지 않았다.
- Jackson annotation은 공식 호환 package인 `com.fasterxml.jackson.annotation`을 유지한다.

### 검증 근거 확인

- property-data main/test compile — `Pass`; Spring Batch 6 `JobOperator`, named `JobParameter` record API, Boot 4 test slice를 확인했다.
- property-data `:core:test :api:test :api:apiContractTest :api:restDocsTest :api:verifyOpenApiSpec :batch:test` — `Pass`.
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (3m 27s; coverage, persistence, fresh Flyway, REST Docs, OpenAPI, packaged Batch 포함).
- user-service `:core:test :app:test --rerun-tasks` — `Pass` (24s).
- `userServiceQualityCheck --no-daemon --stacktrace` — `Pass` (1m 50s; shared JWT library와 migration/runtime boundary 포함).
- admin-service `test --rerun-tasks` — `Pass` (32s); 최종 aggregate와 runtime migration boundary — `Pass` (11s).
- admin migration/ops packaged process — `Pass`.
- source-data `test --rerun-tasks` — `Pass`; `check --no-daemon --stacktrace` — `Pass` (57s; packaged migration process 포함).
- property REST Docs snippet, Asciidoctor HTML, OpenAPI YAML 생성 및 required/forbidden token 검사 — `Pass`.
- Boot JAR manifest — property API/Batch, user app, admin API/migration/ops, source migration 모두 `Spring-Boot-Version: 4.1.0`, `Build-Jdk-Spec: 21`.
- runtime JAR — Jackson 3 core/databind만 존재하고 Jackson 2 data-binding compatibility module은 0건; 지원 annotation artifact만 유지한다.
- runtime Flyway/migration resource boundary — property API/Batch, user app, admin API/ops 0건; admin/source migration artifact만 소유 migration을 포함한다.
- `python3 .codex/harness/pr_lint.py --self-test` — `Pass`; PR body lint — `Pass`.
- staged secret/credential pattern 검사 — 지적사항 없음 (`gitleaks`는 local에 설치되지 않아 repository regex 검사를 사용).
- repository root `git diff --check` — `Pass`.
- migration diff — 변경 0건.

### 검증 공백

- 원격 CI와 실제 외부 provider 호출은 실행하지 않았다. provider parsing은 기존 fixture와 service gate로 검증했다.
- 생성 OpenAPI의 이전 파일 snapshot은 저장소에 없어서 byte/semantic file diff는 불가능했다. 기존 required URL/field 및 forbidden audit field 검사를 통과했다.

### 잔여 위험

- ePages `0.20.1`은 승인 계획대로 pre-release다. snippet/OpenAPI 생성과 재실행을 통과했으며 consumer defect는 발견되지 않았다.
- REST Docs 4 extension은 AsciidoctorJ 3.0.0을 요구하므로 API docs task에 version을 명시했다. 기존 Gradle/Grolifant deprecation warning은 남아 있으며 PR 13 품질 gate에서 계속 추적한다.

### 보안 영향

- JJWT JSON provider를 `jjwt-jackson`에서 같은 버전의 `jjwt-gson`으로 바꿔 Jackson 2 runtime을 제거했다. RS256 검증, issuer/audience/kid, internal admin authentication, user auth test를 다시 실행했다.
- dependency/runtime 변경에 새 credential·secret·외부 endpoint가 없는지 확인했다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 없음.
- 해소한 finding 1: Boot 4 REST Docs 4와 Asciidoctor Gradle plugin 기본 AsciidoctorJ 2.5.7의 ABI 충돌을 AsciidoctorJ 3.0.0 명시로 해결했다.
- 해소한 finding 2: `jjwt-jackson`이 Jackson 2 databind/core를 runtime에 재도입하는 것을 발견해 `jjwt-gson`으로 교체하고 JWT 계약을 회귀 검증했다.
- 검증 근거 확인: public API/OpenAPI, JSON/cache/evidence/provider fixture, Batch packaged process, runtime Flyway/Jackson JAR boundary를 확인했다.
- 검증 공백: 원격 CI와 실제 외부 provider 호출 미실행.
- 잔여 위험: 위 third-party Gradle deprecation warning 외 열린 correctness/security finding은 없다.

### Merge

- implementation commit: `2f5403a`
- merge commit: `2e83090`

## PR 8 Evidence

### TDD 근거

- 최초 RED: `ComplexNameNormalizerParityTest`가 `" 래미안! 1차:아파트 "`에서 실패했다.
- 예상 RED 실패: V8 `hs_normalize_complex_search_name`은 모든 공백·문장부호를 제거하지만 Java 구현은 일부 구분기호만 제거했다.
- 최소 GREEN: V8을 canonical로 유지하고 Java normalizer를 동일한 lowercase/공백·문장부호 제거 규칙으로 맞췄다. SQL resource 이동은 production 의미를 바꾸지 않는 구조 변경이므로 기존 JDBC characterization test를 회귀 seam으로 사용했다.

### 계약 영향

- `none`: map URL, query parameter, marker field, source `name`, `complexId`, price/unit, current-generation 정책을 변경하지 않았다.
- 검색 projection schema와 applied V8 migration은 변경하지 않았다.

### 검증 근거 확인

- 변경 전 `:core:persistenceTest --tests com.home.infrastructure.persistence.map.JdbcMapMarkerRepositoryTest --rerun-tasks` — `Pass` (1m 5s).
- 최초 RED `:core:persistenceTest --tests com.home.infrastructure.persistence.search.ComplexNameNormalizerParityTest --rerun-tasks` — 예상한 punctuation parity 1건만 `Fail` (45s).
- SQL catalog, map repository, V8 parity narrow suite — `Pass` (1m 13s).
- neutral unit/age variant parity, 가격·면적·연식·세대수 조합, marker identity/source name/current-generation fixture — `Pass`.
- `EXPLAIN (COSTS OFF)` smoke — `ix_parcel_geom`과 partition `complex_id_deal_date_id_deal_amount_excl_area_idx` 경로 확인, `Pass` (49s).
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 56s; persistence, coverage, fresh Flyway, API contract, REST Docs/OpenAPI, packaged Batch 포함).
- repository root `git diff --check` — `Pass`.
- migration diff — 변경 0건.
- staged secret/credential pattern 검사 — 지적사항 없음 (`gitleaks`는 local에 설치되지 않아 repository regex 검사를 사용).
- `python3 .codex/harness/pr_lint.py --self-test` 및 PR body lint — `Pass`.

### 검증 공백

- 원격 CI와 production 규모 데이터의 실제 query latency는 실행하지 않았다.

### 잔여 위험

- `EXPLAIN`은 index 경로 존재를 고정하지만 production cardinality별 planner 선택과 latency는 별도 관측이 필요하다.
- Java normalizer는 DB canonical 규칙과 같아졌으므로 기존 일부 punctuation을 보존하던 metadata 후보가 더 넓게 일치할 수 있다. ambiguous/tie 정책과 golden parity test는 그대로 유지한다.

### 보안 영향

- bounds와 모든 filter는 기존 `JdbcClient` named parameter binding을 유지하며 SQL 문자열 보간은 없다.
- SQL resource 이름은 compile-time constant이고 외부 입력으로 resource를 선택하지 않는다.
- migration, credential, endpoint, secret 계약 변경은 없다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 없음.
- 해소한 finding 1: Java complex-name normalization이 V8 search projection보다 좁아 동일 이름을 다르게 판단하던 불일치를 golden parity test와 최소 구현으로 해소했다.
- 해소한 finding 2: 최초 `EXPLAIN` fixture가 `Point`를 `MultiPolygon` column에 넣어 실패한 것을 실제 schema 타입의 작은 polygon fixture로 수정했다.
- 검증 근거 확인: 두 완성형 SQL resource, one-time load, variant selection/binding/mapping 책임, filter parity, index plan, public API gate를 확인했다.
- 검증 공백: 원격 CI와 production cardinality 성능 미측정.
- 잔여 위험: 열린 correctness/security finding은 없고 production query latency 관측만 남는다.

### Merge

- implementation commit: `1ed9cc6`
- merge commit: `25e4e2a`

## PR 9 Evidence

### TDD 근거

- 최초 RED: `NearbyPlaceConfigurationTest`에서 Kakao 기능 활성 상태의 API key 누락과 Redis bean 누락이 기존 fallback 때문에 startup failure가 되지 않음을 확인했다.
- 예상 RED 실패: enabled feature의 필수 secret/infrastructure가 없어도 context가 시작되는 실패다.
- 최소 GREEN: `NearbyPlaceProperties` validation과 mandatory `StringRedisTemplate` injection을 적용하고 동일 테스트를 GREEN으로 만들었다.
- 최초 RED: `PredictionUseCaseConfigurationTest`에서 prediction JDBC/Redis 필수 adapter가 없어도 fallback use case가 등록되는 실패를 확인했다.
- 예상 RED 실패: enabled prediction이 required persistence/cache 없이 시작되는 실패다.
- 최소 GREEN: `PricePredictionUseCase`를 component service로 등록하고 JDBC/Redis adapter를 mandatory dependency로 전환했다.
- 구조 이동 waiver: 나머지 service/repository annotation 전환과 configuration bean 제거는 production behavior를 바꾸지 않으며 기존 unit, persistence, API contract, Batch composition test를 회귀 seam으로 사용했다.

### 계약 영향

- `none`: public URL, method, JSON field, status, error detail, empty/404, clamp, `complexName`/`displayName` 의미를 변경하지 않았다.
- 기존 `APT_SERVICE_KEY`, `ODC_SERVICE_KEY`, `BLD_SERVICE_KEY`, `VW_SERVICE_KEY` 환경변수 이름과 canonical property key 우선순위를 유지한다.
- migration SQL, checksum, runtime Flyway boundary를 변경하지 않았다.

### 데이터 영향

- `none`: SQL, migration, persisted enum/state, `complex_id`/`complex_pk`, raw-first ingest 및 failed-match evidence를 변경하지 않았다.

### 검증 근거 확인

- Nearby/Prediction mandatory dependency 최초 RED와 최소 GREEN narrow tests — `Pass`.
- `ExternalApiCredentialPropertiesTest` — `Pass`; 기존 4개 external credential 환경변수 binding과 canonical property 우선순위를 확인했다.
- `HomeSearchApiApplicationTests` — `Pass`; no-DB context가 mandatory JDBC/transaction/Redis test dependency를 명시한다.
- `ObservabilityEndpointSmokeTest` 5건 — `Pass`; no-DB context에서 raw reconciliation runner를 명시적으로 비활성화했다.
- `PropertyDataBatchContextBoundaryTest` — `Pass`; Batch가 필요한 service/ingest-region adapter만 스캔하고 API feature를 유입하지 않음을 확인했다.
- `:core:test --rerun-tasks` — `Pass` (14s).
- `:batch:test --rerun-tasks` — `Pass` (16s).
- `:api:test --rerun-tasks` — `Pass` (2m 28s, 57 tests).
- `:api:apiContractTest` — `Pass`; `:api:restDocsTest :api:verifyOpenApiSpec --rerun-tasks` — `Pass` (49s).
- configuration component 전환에 맞춘 persistence context tests와 `IngestRecoveryRunnerTest` — `Pass`.
- `JdbcMapMarkerRepositoryTest` exact PostGIS/covering-index EXPLAIN assertion — 단독 및 전체 class `Pass`; planner 통계 순서 의존성을 범위 밖 bulk fixture와 `ANALYZE`로 제거했다.
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 6s; persistence 220 tests, coverage, Spotless, fresh Flyway, REST Docs/OpenAPI, packaged Batch 포함).
- production `@Value`, `ObjectProvider`, `DurationStyle.detectAndParse`, configuration의 application `Service`/`UseCase` 수동 생성 — 모두 0건.
- production structure — exact `@Configuration` 27개, `@Bean` 56개, `@ConfigurationProperties` 20개, `@Service` 25개, `@Repository` 28개.
- application Spring import — `org.springframework.stereotype.Service`, `org.springframework.transaction.annotation.Transactional`만 존재; domain Spring import 0건.
- legacy read type, production `Integer.MAX_VALUE` paging — 0건.
- `python3 .codex/harness/pr_lint.py --self-test` 및 PR body lint — `Pass`.
- repository root `git diff --check` — `Pass`; migration diff — 변경 0건.

### 검증 공백

- 원격 CI와 실제 external provider/Redis 호출은 실행하지 않았다. provider parsing과 startup binding은 local fixtures/context tests로 검증했다.
- `gitleaks`가 local에 설치되지 않아 repository added-line credential/secret pattern 검사를 사용했다.

### 잔여 위험

- Asciidoctor/Grolifant의 기존 Gradle 10 deprecation warning은 남아 있으며 project production API 경고는 아니다.
- 외부 provider credential 자체의 유효성·quota는 실제 운영 호출 전까지 검증되지 않는다.

### 보안 영향

- enabled Kakao/notification configuration의 secret 누락은 startup에서 차단하고, disabled feature는 blank secret을 허용한다.
- credential은 typed binding과 기존 env fallback으로만 전달하며 log, exception response, metric label에 값을 추가하지 않았다.
- JDBC는 기존 named parameter와 transaction 경계를 유지하고 SQL 문자열/외부 입력 보간을 추가하지 않았다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 열린 correctness/security finding 없음.
- 해소한 finding 1: Batch의 broad component scan이 API 전용 service까지 유입하던 문제를 exact application service와 ingest/region adapter scan으로 제한했다.
- 해소한 finding 2: typed binding 전환 중 누락될 수 있던 unprefixed external credential env 계약을 별도 typed properties와 회귀 테스트로 보존했다.
- 해소한 finding 3: no-DB API/REST Docs tests가 production fallback에 의존하지 않고 필수 mock/disabled runner를 명시하도록 수정했다.
- 해소한 finding 4: map EXPLAIN의 suite-order planner 통계 의존성을 production SQL/assertion 완화 없이 deterministic fixture로 제거했다.
- 검증 근거 확인: full API/Batch/core/persistence, public contract, OpenAPI, coverage, fresh migration, packaged process가 GREEN이다.
- 검증 공백: 원격 CI 및 실제 provider 호출 미실행.
- 잔여 위험: 위 third-party Gradle warning과 external credential 유효성 확인 외 열린 finding은 없다.

### Merge

- implementation commits: `94f2800`, `47c93d4`
- merge commit: `027f23e4ce9e3c3b49f82d0eb0969b37ada90fe3`

## PR 10 Evidence

### TDD 근거

- 변경 전 Nearby/Prediction executor와 use case narrow baseline — `Pass` (12s).
- 최초 RED 1: Nearby executor가 `ThreadPoolTaskExecutor`가 아니어서 type assertion이 실패했고, Prediction executor 포화 시 `RejectedExecutionException`이 전파되어 `PENDING` cache만 남았다.
- 예상 RED 실패 1: managed executor assertion failure와 `RejectedExecutionException: executor saturated`를 확인했다.
- 최소 GREEN 1: 기존 `threads` key를 유지한 bounded `ThreadPoolTaskExecutor`, typed queue/shutdown timeout, Spring lifecycle shutdown을 적용하고 Prediction rejection을 non-sensitive `FAILED` 결과로 확정했다.
- 최초 RED 2: internal JWT 401 body에 documented minimum field인 `exception`, `timestamp`가 없어 JSON assertion이 실패했다.
- 예상 RED 실패 2: `problem.get("exception")`이 `null`인 NPE를 확인했다.
- 최소 GREEN 2: `ApiProblemFactory`가 handler와 filter의 `ProblemDetail`을 생성하도록 통합하고 기존 internal `type`, `title`, `status`, `detail` 값을 유지했다.
- 최초 RED 3: `/api/v1/detail/0`이 controller validation을 통과해 service mock의 `null` 결과로 `500`을 반환했다.
- 예상 RED 실패 3: expected `400` but was `500`을 확인했다.
- 최소 GREEN 3: resource id와 page/limit 하한을 web validation으로 명시하고, `size > 100`/`limit > 100`의 application clamp는 그대로 유지했다.
- 최초 RED 4: 500 diagnostic log가 원본 exception의 cause/suppressed type을 잃어 contract assertion이 실패했다.
- 예상 RED 실패 4: `IllegalArgumentException`, `UnsupportedOperationException` diagnostic evidence가 log에 없었다.
- 최소 GREEN 4: cause/suppressed topology와 stack은 유지하되 exception message는 제거하는 sanitized diagnostic을 적용했다.

### 계약 영향

- public URL, method, JSON success field, status, error detail, empty/404, search/region/trade clamp 변경 없음.
- internal JWT 401은 기존 field 값을 유지하면서 canonical error minimum인 `exception`, UTC offset `timestamp`만 추가했다.
- Prediction provider/rejection failure message는 raw exception 대신 기존 REST Docs 정의인 non-sensitive status message를 반환한다.

### 데이터 영향

- migration, schema, persisted enum/state, SQL, `complex_id`/`complex_pk` 변경 없음.
- Prediction Redis cache의 `FAILED` message만 non-sensitive canonical 문구로 저장한다.

### 검증 근거 확인

- managed executor/rejection/cache/quota narrow core tests — `Pass` (12s).
- Prediction/Map configuration persistence narrow tests — `Pass` (12s).
- internal JWT filter tests — `Pass` (10s).
- read/map API contract tests — `Pass` (13s); non-positive id `400`, `Invalid parameter format.`, 500 sanitized cause/suppressed, 기존 response field를 확인했다.
- 최초 aggregate gate는 API/Core/Batch/persistence tests를 통과한 뒤 새 test assertion의 Spotless 미적용만 `Fail` (10m 30s); `spotlessApply` 후 동일 gate를 재실행했다.
- `backendQualityCheck --no-daemon --stacktrace` — `Pass` (12m 3s; API contract, API/Core/Batch, persistence 220 tests, coverage, Spotless, fresh Flyway, REST Docs/OpenAPI, packaged Batch 포함).
- production raw `Executors.newFixedThreadPool`, `new ThreadPoolExecutor(...)` — 0건.
- application Spring import — `@Service`, `@Transactional`만 존재; domain Spring import 0건.
- metric label — bounded `category` enum, `operation`, `result`만 사용하며 complex id, 좌표, 장소명, URL, credential 0건.
- repository root `git diff --check` — `Pass`; migration diff — 변경 0건.
- added-line credential/secret pattern 검사 — 지적사항 없음 (`gitleaks`는 local에 설치되지 않음).
- `python3 .codex/harness/pr_lint.py --self-test` 및 PR body lint — `Pass`.

### 검증 공백

- 원격 CI와 실제 Kakao/Prediction/Redis 장애 호출은 실행하지 않았다. provider/cache/rejection behavior는 fixture, mock, context test로 검증했다.

### 잔여 위험

- Nearby/Prediction executor의 thread/queue/shutdown timeout은 bounded default를 적용했으며 production 부하와 rejection metric에 따라 조정이 필요할 수 있다.
- 500 diagnostic은 secret/query exception message를 보존하지 않고 cause/suppressed type과 stack topology만 보존한다. 원본 message는 의도적으로 log/response에서 제외한다.

### 보안 영향

- internal JWT token/claim 검증 실패는 generic body만 반환하며 token, claim, request id를 log/response에 추가하지 않았다.
- cache/provider/quota log는 exception type만 기록하고 cache key, 좌표, URL, query, credential을 기록하지 않는다.
- executor queue는 bounded이고 rejection은 기존 503/FAILED degrade 계약으로 변환한다.
- credential, SQL binding, migration, runtime Flyway boundary 변경 없음.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 열린 correctness/security finding 없음.
- 해소한 finding 1: Prediction fixed pool의 unbounded queue와 두 executor의 Spring lifecycle 밖 shutdown을 bounded `ThreadPoolTaskExecutor`로 교체했다.
- 해소한 finding 2: Prediction rejection이 `PENDING`만 남기던 상태를 `FAILED` cache/response로 확정했다.
- 해소한 finding 3: internal JWT filter의 독립 error map과 handler의 중복 factory를 통합했다.
- 해소한 finding 4: metadata/cache/provider log의 raw exception/key 노출을 bounded error type log와 metric으로 교체했다.
- 해소한 finding 5: aggregate gate의 유일한 Spotless 실패를 수정하고 전체 gate를 재실행했다.
- 검증 근거 확인: narrow RED/GREEN, public/internal error contract, full backend gate, migration/secret/static boundary가 GREEN이다.
- 검증 공백: 원격 CI 및 실제 provider 호출 미실행.
- 잔여 위험: 위 executor 운영 tuning 외 열린 finding은 없다.

### Merge

- implementation commit: `98374cb`
- merge commit: `78aea144b2cc0fd3be018f52815143789b40fd60`

## PR 11 Evidence

### TDD 근거

- 변경 전 `:core:test :app:test --rerun-tasks --no-daemon --stacktrace` — `Pass` (24s).
- 최초 RED: `UserSpringJpaModernizationTest`가 application-owned favorite policy/service와 JDBC-only refresh persistence 구조를 요구하도록 추가했다.
- 예상 RED 실패: `FavoriteService`와 `JdbcRefreshTokenRepository`가 없어 2개 test가 모두 `Fail`했고, 기존 favorite port가 `FavoriteLimitPolicy`를 parameter로 받으며 JPA refresh 3개 type이 남아 있음을 확인했다.
- 최소 GREEN: 네 favorite use case를 transactional `FavoriteService`로 통합하고 user lock → existing → count/policy → idempotent save 순서를 application에 배치했다. refresh persistence는 명시적 JDBC upsert/lookup/row-count CAS rotate/revoke로 교체했다.
- 중간 GREEN: favorite 단위/구조 test는 먼저 `Pass`하고 refresh 구조 test만 예상대로 `Fail`; JDBC 교체 후 favorite/auth/구조 test가 모두 `Pass`했다.

### 계약 영향

- user OAuth URL, callback, access/logout, current-user, favorite URL/method/status/JSON/pagination 계약 변경 없음.
- refresh cookie name/`HttpOnly`/`Secure`/`SameSite=Lax`/`Path=/auth`, JWT issuer/audience/kid/15분 정책을 유지한다.
- favorite duplicate PUT idempotency와 최대 200개 정책을 유지한다. property-data public API와 `docs/API_CONTRACT.md` 변경 없음.

### 데이터 영향

- migration/schema/checksum/history 변경 없음. `users.refresh_token`, `users.favorite_complex`, `users.user_account`의 저장 의미를 변경하지 않는다.
- refresh token 원문은 계속 저장하지 않고 SHA-256 hash만 저장한다. 기존 user당 active token 1개와 version 증가 semantics를 동일 SQL로 유지한다.
- OAuth identity/user/favorite는 JPA를 유지하고 refresh token만 JPA entity/native query에서 명시적 `JdbcClient` adapter로 이동했다.

### 검증 근거 확인

- 구조 최초 RED — `:core:test --tests com.home.application.UserSpringJpaModernizationTest --rerun-tasks`가 예상대로 2 tests `Fail` (6s).
- favorite/auth/구조 narrow test — `Pass` (6s).
- typed configuration, cookie, security/JWT, OAuth handler, favorite/web narrow test — `Pass` (10s).
- 실제 PostgreSQL의 refresh replace/revoke/CAS와 favorite 200/201 concurrency 포함 app narrow test — `Pass` (18s).
- `:core:test :app:test --rerun-tasks --no-daemon --stacktrace` — `Pass` (23s).
- `verifyUserMigrationCatalog userServiceDependencyBoundaryCheck spotlessCheck` — `Pass` (6s).
- `userServiceQualityCheck --rerun-tasks --no-daemon --stacktrace` — `Pass` (2m 3s; core/app/library/API/persistence tests, coverage, fresh Flyway `EMPTY → READY`, runtime Flyway-free JAR, bootJar 포함).
- coverage denominator에 core/app production class를 모두 포함했으며 instruction은 covered `2878`, missed `262`, ratio 약 `91.66%`로 90% gate를 통과했다.
- production `@Value`, persistence adapter `@Transactional`, configuration의 use-case `new`, 제거 대상 favorite/JPA refresh production reference — 모두 0건.
- application Spring import는 `@Service`, `@Transactional`만 존재한다.
- repository root `git diff --check` — `Pass`; baseline 대비 migration diff — 변경 0건; `docs/API_CONTRACT.md` diff — 변경 0건.
- added-line credential/secret pattern 검사 — 지적사항 없음 (`gitleaks`는 local에 설치되지 않음).

### 검증 공백

- 원격 CI와 실제 Google/Kakao/Naver provider login은 실행하지 않았다. provider response parsing과 OAuth callback은 기존 stub/security-chain test로 검증했다.

### 잔여 위험

- provider별 client registration은 중복 custom wrapper를 만들지 않고 Spring Boot의 typed `spring.security.oauth2.client` binding을 유지한다. 실제 provider credential/redirect 조합은 배포 preflight와 운영 smoke에서 별도 확인해야 한다.

### 보안 영향

- refresh rotate는 old hash, non-revoked, non-expired 조건의 단일 `UPDATE` row count로 재사용 경쟁을 차단하며 실제 PostgreSQL 동시 test에서 정확히 하나만 성공했다.
- 모든 새 refresh/favorite SQL은 static statement와 named parameter를 사용하고 raw token, hash, credential을 log에 추가하지 않았다.
- typed JWT/cookie/auth/OAuth 설정은 기존 env/property key를 유지하고 필수값·positive duration을 startup validation한다. insecure production refresh cookie 거부도 회귀 test로 고정했다.
- runtime Flyway-free boundary, user/admin JWT issuer/audience 분리, cookie flags, key file 검증은 유지된다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 열린 correctness/security finding 없음.
- 해소한 finding 1: favorite adapter가 user lock, count, domain policy와 transaction을 소유하던 경계를 application `FavoriteService`로 이동했다.
- 해소한 finding 2: refresh CAS SQL의 JPA entity/Spring Data native-query 3단 경로를 단일 명시적 JDBC adapter로 축소했다.
- 해소한 finding 3: application use-case 수동 `@Bean` 조립과 production `@Value`를 제거하고 service/component scan 및 typed properties로 전환했다.
- 해소한 finding 4: core-only coverage denominator를 core/app 전체 production class로 수정해 controller, OAuth handler, security filter, cookie code를 포함했다.
- 검증 근거 확인: structural RED/GREEN, 실제 PostgreSQL concurrency, user API/security chain, full quality gate, migration/runtime/secret boundary가 GREEN이다.
- 검증 공백: 원격 CI 및 실제 OAuth provider 호출 미실행.
- 잔여 위험: 실제 provider credential/redirect 운영 smoke 외 열린 finding은 없다.

### Merge

- implementation commit: `430ef4f`
- merge commit: `6ab8bdb9c23213e6e80933605b24e2358243c4e3`

## PR 12 Evidence

### TDD 근거

- 변경 전 admin `./gradlew test --rerun-tasks --no-daemon --stacktrace` — `Pass` (28s).
- 변경 전 source-data `./gradlew test --rerun-tasks --no-daemon --stacktrace` — `Pass` (5s).
- 최초 RED 1: `AdminSpringModernizationTest`가 typed session/internal-client/internal-JWT properties와 공통 `AdminProblemFactory`를 요구하도록 추가했다.
- 예상 RED 실패 1: 네 production type이 없어 admin 구조 test가 `Fail` (6s).
- 최소 GREEN 1: `@Value`를 validated `@ConfigurationProperties`로 교체하고 MVC advice, login controller, Security handler가 동일 factory로 기존 `ProblemDetail`을 생성하도록 했다.
- 최초 RED 2: source-data runner가 지원 operation을 내부 enum으로 소유하는지 검사했다.
- 예상 RED 실패 2: `SourceDataMigrationRunner$Operation`이 없어 source-data 구조 test가 `Fail` (5s).
- 최소 GREEN 2: CLI 문자열/option/exit code는 그대로 유지하고 runner의 문자열 switch만 private enum parse/switch로 변경했다.

### 계약 영향

- admin Session/RBAC/CSRF URL, request/response, status/detail, internal JWT issuer/audience/kid/lifetime, downstream request id 계약 변경 없음.
- browser request body/path에서 actor id를 받지 않고 `@AuthenticationPrincipal AdminPrincipal`의 account id만 사용한다.
- source-data `info`, `validate`, `migrate`, `preflight-baseline`, `baseline-existing` operation과 option/confirmation/exit code 계약 변경 없음.

### 데이터 영향

- admin/source-data migration SQL, checksum, schema, Flyway history 변경 없음.
- `AdminAccountService`/`AdminAuthenticationService`의 직접 `JdbcClient`, transaction, advisory lock, last-admin 보호, session revoke/audit 원자성을 변경하지 않았다.
- source-data wrong-database guard와 legacy fingerprint/baseline confirmation을 그대로 유지한다.

### 검증 근거 확인

- admin typed config/problem/security narrow tests — `Pass` (9s).
- source-data operation/parse narrow test — `Pass` (5s).
- admin `spotlessApply test --rerun-tasks --no-daemon --stacktrace` — `Pass` (30s; API/core/migration/ops 포함).
- admin API 전체 regression 재실행 — `Pass` (10s); session/RBAC/CSRF/login/BFF error detail을 포함한다.
- admin `spotlessCheck :api:check :migration:check :ops:check` — `Pass` (6s); API runtime Flyway-free JAR boundary 포함.
- admin migration/ops packaged wrapper smoke — `Pass`; unknown operation과 forbidden inline password가 exit code `2`를 유지한다.
- source-data `spotlessApply check --rerun-tasks --no-daemon --stacktrace` — `Pass` (56s; unit, PostgreSQL/Flyway integration, packaged process 포함).
- production admin `@Value` — 0건; `ProblemDetail.forStatusAndDetail` production 생성 지점은 공통 factory 1건만 존재한다.
- repository root `git diff --check` — `Pass`; baseline 대비 admin/source migration diff — 변경 0건.
- added-line credential/secret pattern 검사 — 지적사항 없음 (`gitleaks`는 local에 설치되지 않음).
- `python3 .codex/harness/pr_lint.py --self-test` 및 PR body lint — `Pass`.

### 검증 공백

- 원격 CI와 실제 property-data internal endpoint 호출은 실행하지 않았다. RestClient URI/timeout과 JWT는 configuration/unit/BFF mock test로 검증했다.

### 잔여 위험

- 실제 배포 private key file mount와 property-data network route는 운영 smoke에서 확인해야 한다. enabled startup은 blank secret, unsafe URI, non-positive timeout/lifetime을 거부한다.

### 보안 영향

- internal feature disabled 시 secret 공백을 허용하고 enabled 시 issuer/audience/kid/private-key path와 최대 60초 TTL을 startup validation한다.
- RestClient base URI는 HTTP(S) host만 허용하고 user-info/query/fragment/non-root path를 거부하며 connect/read timeout은 positive duration으로 검증한다.
- private key regular-file/최대 16 KiB 검사, internal JWT actor principal, Session/RBAC/CSRF와 last-admin protection을 유지한다.
- CLI option/exit code와 data-sensitive `baseline-existing` confirmation을 변경하지 않았다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: 열린 correctness/security finding 없음.
- 해소한 finding 1: admin session/internal JWT/RestClient의 9개 `@Value`를 feature별 typed properties로 이동했다.
- 해소한 finding 2: MVC advice, login controller, Security handler의 독립 `ProblemDetail` 생성을 공통 factory로 통합했다.
- 해소한 finding 3: source-data 지원 operation을 private enum으로 명시하면서 외부 문자열/exit 계약은 유지했다.
- 검증 근거 확인: admin security/persistence/API와 source-data CLI/Flyway/packaged process, migration/runtime/secret boundary가 GREEN이다.
- 검증 공백: 원격 CI 및 실제 internal network 호출 미실행.
- 잔여 위험: 운영 key mount/network smoke 외 열린 finding은 없다.

### Merge

- implementation commit: `26660a7`
- merge commit: `3b0d6dfe94ef8198078a1075c5f60079064f71b0`

## PR 13 Evidence

### TDD 근거

- 최초 RED 1: admin aggregate coverage가 `LINE 0.69`, `INSTRUCTION 0.65`,
  `BRANCH 0.47`로 새 `0.90/0.90/0.65` 기준에 미달했다.
- 예상 RED 실패 1: account/authentication/BFF/CLI의 미검증 분기 때문에 세
  counter가 모두 coverage gate에서 거부된다.
- 최소 GREEN 1: controller forwarding, account mutation, authentication
  success/unknown/lock, migration/ops operation과 stdin 동작 테스트를 추가했다.
- 최초 RED 2: source-data aggregate coverage가 `LINE 0.72`,
  `INSTRUCTION 0.82`, `BRANCH 0.44`로 기준에 미달했다.
- 예상 RED 실패 2: operation parse와 `info`/`validate`/baseline interleaving이
  실행되지 않아 runner 분기 coverage가 부족하다.
- 최소 GREEN 2: wrong/missing/duplicate/unconfirmed operation과 fresh/legacy
  Flyway operation 순서를 실제 runner로 검증했다.
- 최초 RED 3: property-data의 broad `*Matcher*`/`*Configuration*` 제외를
  제거하자 `INSTRUCTION 27,874/31,038`(`89.81%`)로 실패했다.
- 예상 RED 실패 3: conditional nearby/internal-admin configuration 조립이
  coverage denominator에는 포함되지만 enabled success path가 실행되지 않는다.
- 최소 GREEN 3: enabled nearby provider/center reader와 internal JWT filter
  registration 테스트를 추가하고, 최종적으로 HTTP DTO와 실제 entrypoint만
  명시적으로 제외했다.

### 계약 영향

- public URL, method, request/response JSON, status, error detail, pagination과
  clamp 변경 없음.
- `docs/API_CONTRACT.md`는 이동 완료된 controller의 실제
  `apps/property-data/api/src/main/java` source path만 갱신했다.
- generated OpenAPI와 REST Docs token/semantic 검증은 기존 계약으로 통과했다.

### 데이터 영향

- SQL, schema, applied migration/checksum/history, persisted enum/state,
  `complex_id`/`complex_pk`, raw-first와 failed-match evidence 변경 없음.
- baseline `d601237` 대비 migration diff는 0건이다.

### 검증 근거 확인

- property-data broad coverage exclusion 제거 첫 실행 — 예상 `Fail`
  (`INSTRUCTION 89.81%`).
- property-data strict final coverage — `Pass`: `LINE 6,648/7,363`
  (`90.29%`), `INSTRUCTION 31,592/35,091`(`90.03%`),
  `BRANCH 1,917/2,823`(`67.91%`).
- user-service final coverage — `Pass`: `LINE 94.3%`, `INSTRUCTION 91.66%`,
  `BRANCH 68.9%`; `core`와 `app` production class를 모두 포함한다.
- admin-service fresh coverage — `Pass` (34s): `LINE 638/679`(`93.96%`),
  `INSTRUCTION 2,715/2,952`(`91.97%`), `BRANCH 170/259`(`65.64%`).
- source-data fresh coverage — `Pass`: `LINE 138/149`(`92.62%`),
  `INSTRUCTION 761/805`(`94.53%`), `BRANCH 28/38`(`73.68%`).
- property-data `backendQualityCheck --no-daemon --stacktrace` — `Pass`
  (12m 46s; persistence, fresh Flyway, API/OpenAPI, packaged Batch, coverage,
  architecture 포함).
- user-service `userServiceQualityCheck --no-daemon --stacktrace` — `Pass`
  (1m 50s; fresh Flyway, runtime boundary, coverage, architecture 포함).
- admin-service `adminServiceQualityCheck --no-daemon --stacktrace` — `Pass`;
  source-data `sourceDataQualityCheck --no-daemon --stacktrace` — `Pass`.
- web `npm run test` — `Pass` (32 files, 189 tests); `npm run build` — `Pass`.
- property/user architecture gate — `Pass`: domain purity, application Spring
  import allowlist, web→persistence 금지, transactional final 금지를 확인했다.
- runtime JAR inspection — property API/core/Batch, user app/core, admin API/ops는
  Flyway/migration resource 0건; admin migration은 admin SQL 1건,
  source-data migration은 소유 SQL 4건만 포함한다.
- `git diff --check`, project terms check, 네 harness self-test, valid PR body
  `pr-lint` — `Pass`.
- added-line secret pattern 검사 — test placeholder password/API key만 확인됐고
  실제 credential/private key는 없다. `gitleaks`/`trufflehog`는 local에 설치되지 않았다.

### 검증 공백

- 원격 CI, 실제 OAuth/Kakao/RTMS provider, 운영 private-key/Redis/network smoke는
  실행하지 않았다. 이번 PR은 provider 호출이나 production runtime behavior를
  변경하지 않는다.
- `pr_lint.py --body-only`의 invalid-body 오류 출력 경로는 기존
  `BodyCheckResult.errors` type mismatch로 traceback을 출력한다. valid body와
  self-test는 통과했으며 application/PR 13 변경 범위 밖의 후속 harness 정리다.

### 잔여 위험

- property-data instruction coverage는 `90.03%`로 기준에 가깝다. 이름 기반
  domain/configuration 제외는 제거했으므로 이후 production 분기 추가 시 해당
  동작 테스트를 함께 추가해야 한다.
- Gradle/Asciidoctor의 기존 deprecation warning은 남아 있으며 Gradle 10 전환
  전에 별도 dependency/tooling PR에서 해소해야 한다.

### 보안 영향

- production security/filter/configuration code와 authorization contract는
  변경하지 않았다.
- test credential은 고정 placeholder이며 source/runtime secret으로 사용되지
  않는다. private key, OAuth secret, raw refresh token, provider query를 추가하지 않았다.
- coverage/architecture gate는 security filter와 application boundary를 실제
  denominator/검사 범위에 포함한다.
- security-audit: 지적사항 = none

### Findings-first review

- 지적사항: PR 13 application/build/docs diff의 열린 correctness, contract,
  data-safety, security finding 없음.
- 해소한 finding 1: admin/source-data에 없던 aggregate quality/coverage task를
  추가하고 네 Java application에 같은 `90/90/65%` counter 기준을 적용했다.
- 해소한 finding 2: property-data의 `Matcher`, `Configuration`, domain
  `Status`/`Record` 등 이름 기반 broad exclusion을 제거하고 HTTP DTO와 실제
  entrypoint만 명시적으로 제외했다.
- 해소한 finding 3: property/user에 domain/application/web/transactional-final
  architecture gate를 추가했다.
- 검증 근거 확인: 네 service aggregate gate, frontend test/build, contract/OpenAPI,
  migration/JAR/secret boundary가 GREEN이다.
- 검증 공백: 원격 CI와 실제 운영 provider/network smoke 미실행.
- 잔여 위험: 기존 Gradle deprecation과 invalid-body lint error rendering은
  후속 tooling 범위다.

### Merge

- implementation commit: `c33abe5`
- merge commit: pending

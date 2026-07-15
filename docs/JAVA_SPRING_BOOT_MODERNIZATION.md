# Java/Spring Boot Modernization Ledger

## 실행 기준

- baseline commit: `d601237`
- 현재 진행 PR: `PR 7 — Boot 4.1 / Jackson 3`
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
| 8 | map SQL readability | Pending | PR 7 `Complete` |
| 9 | property-data typed wiring | Pending | PR 8 `Complete` |
| 10 | error/validation/executor/observability | Pending | PR 9 `Complete` |
| 11 | user-service Spring/JPA cleanup | Pending | PR 10 `Complete` |
| 12 | admin/source-data modernization | Pending | PR 11 `Complete` |
| 13 | quality gates와 final docs | Pending | PR 12 `Complete` |

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

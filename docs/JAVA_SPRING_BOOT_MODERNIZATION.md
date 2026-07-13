# Java/Spring Boot Modernization Ledger

## 실행 기준

- baseline commit: `d601237`
- 현재 진행 PR: `PR 1 — 구조 원칙과 계약 baseline 고정 (merge 대기)`
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
| 2 | property read capability split | Pending | PR 1 `Complete` |
| 3 | read snapshot과 DTO 경로 | Pending | PR 2 `Complete` |
| 4 | coordinate/ingest atomic workflows | Pending | PR 3 `Complete` |
| 5 | Java 21 / Boot 3.5 bridge | Pending | PR 4 `Complete` |
| 6 | format baseline | Pending | PR 5 `Complete` |
| 7 | Boot 4.1 / Jackson 3 | Pending | PR 6 `Complete` |
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

- merge commit: 미정

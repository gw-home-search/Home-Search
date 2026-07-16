# 통합 개선 Verification Evidence

## 판정

- 상태: `Fail`
- 계약 영향: 공개 API URL과 기존 response field 변경 없음. 기존 optional
  prediction field 문서화와 frontend numeric strictness만 반영했다.
- DB 영향: schema/Flyway 변경 없음. enum stored value와 Redis serialization 값
  유지.
- 보안 영향: `security-audit: 지적사항 = none`. Direct workflow에 존재하지
  않는 `job_workflow_ref` 조건은 최종 audit에서 제거했고, protected
  environment의 `sub`, repository, workflow, environment, ref 제한은 유지했다.

계획한 30개 논리 커밋 중 map SQL 변경은 실제 752만 trade DB benchmark에서
성능 인수 기준을 충족하지 못해 중단 조건에 따라 PR에서 제외했다. 현재 PR은
안전하게 유지된 29개 논리 커밋으로 구성된다. AWS plan/apply 이후 단계는 map
성능 gate 실패로 시작하지 않았다.

## 커밋별 근거

| # | 논리 커밋 | TDD 근거 | 계약·보안·성능 근거 | 판정 |
|---:|---|---|---|---|
| 1 | `fix(ci): make change classification and quality gates exact` | classifier fixture 최초 실패 후 exact route | self-test와 YAML parse | Pass |
| 2 | `refactor(place): centralize nearby provider execution` | waiver: behavior-preserving refactor | timeout/interrupt/rejection 기존 test | Pass |
| 3 | `refactor(domain): correct ownership of durable operational types` | waiver: type move | dependency/serialization/application test, stored value 유지 | Pass |
| 4 | `refactor(metadata): split public metadata provider adapters` | waiver: adapter split | provider priority/alias/partial failure fixture parity | Pass |
| 5 | `perf(prediction): make READY cache hits basis-only` | READY snapshot read RED, assembler parity RED | hit snapshot 0, miss query budget, `apiContractTest` | Pass |
| 6 | `perf(map): replace per-complex lateral scans with set-based ranking` (PR 제외) | 동일 DB 8,595-row SHA-256 parity Pass | old cold p95 4,781ms 대비 set-based cold p95 26,496ms, warm p95 27,179ms; 목표 미달로 중단 | Fail |
| 7 | `fix(web-search): abort and ignore stale search responses` | stale response overwrite RED | abort signal과 최신 결과 test | Pass |
| 8 | `fix(web-detail): isolate detail trade and trend failures` | 부분 실패가 sidebar를 지우는 RED, 새 단지 대기 중 이전 identity 노출 RED | detail/trade/trend 독립 error/retry와 선택 전환 stale-data 차단 test | Pass |
| 9 | `fix(web-contract): enforce numeric request and response contracts` | fractional integer/numeric string RED | frontend contract fixture와 adapter boundary | Pass |
| 10 | `fix(web-marker): display name and household count together` | marker label expectation RED | 가격·이름·세대수 표시 test | Pass |
| 11 | `fix(web-a11y): complete keyboard tab semantics` | keyboard semantics RED | ARIA linkage, roving focus, Home/End test | Pass |
| 12 | `chore(frontend): add shared ESLint quality gates` | tooling waiver | web/admin lint 0 error; 기존 warning 8개 | Pass |
| 13 | `test(web): split monolithic app integration tests` | test-only waiver | 분리 후 web 전체 214 tests, 통합 suite 의미 유지 | Pass |
| 14 | `refactor(web-map): separate Kakao runtime and overlay lifecycles` | refactor waiver | key reuse, selective remove, unmount cleanup fake SDK test | Pass |
| 15 | `security(edge): close actuator and local Redis exposure` | gateway contract RED | actuator 404, normal API pass, loopback Redis binding | Pass |
| 16 | `security(source-data): remove database passwords from process arguments` | sentinel argv RED | fake docker argv/stdout 비노출 self-test | Pass |
| 17 | `chore(repo): reconcile ignore rules docs and verification commands` | docs waiver | path/command/contract policy review | Pass |
| 18 | `ops(backup): add deterministic database backup and restore verification` | checksum/overwrite/restore invariant RED | fake S3와 실제 PostgreSQL ephemeral restore | Pass |
| 19 | `build(property): package property API batch and Flyway images` | image-boundary fixture | UID 10001, JAR/SQL/entrypoint smoke | Pass |
| 20 | `build(platform): package admin user and source-data workloads` | image-boundary fixture | service/migration/ops artifact 분리, non-root | Pass |
| 21 | `build(edge): package public web admin web and gateway` | edge smoke fixture | SPA/cache/proxy/actuator/admin route isolation | Pass |
| 22 | `build(images): define reproducible multi-image build manifest` | manifest fixture | 14 targets, SHA/SemVer, OCI label, architecture | Pass |
| 23 | `infra(bootstrap): provision remote state and GitHub OIDC trust` | Terraform test | KMS/versioning/public block/lockfile/direct-workflow 지원 OIDC claims | Pass |
| 24 | `infra(staging): provision network data and registry foundation` | Terraform test | private data, admin CIDR, TLS Valkey, ECR immutable | Pass |
| 25 | `infra(workloads): provision bootstrap tasks and ECS services` | Terraform + shell fixture | digest pin, service/one-shot 분리, secret argv 비노출, rollback circuit breaker | Pass |
| 26 | `infra(backup): schedule backup and restore verification` | Terraform + PostgreSQL integration | KST schedule, separate KMS, 30일, metrics/alarm, coordinate 제외 | Pass |
| 27 | `ci(release): publish immutable release images` | eligibility/manifest self-test | tag/main/check gate, OIDC, pinned Syft/Grype checksum; 실제 ECR 미실행 | Partial |
| 28 | `cd(staging): deploy migrate verify and rollback by release manifest` | plan allowlist/ECS helper self-test | migration-before-service, C401/actuator smoke, previous ARN rollback; 실제 staging 미실행 | Partial |
| 29 | `perf(staging): add scheduled map and prediction regression checks` | k6 `inspect` | release-linked cold/warm/READY/miss metrics; 실제 endpoint 미실행 | Partial |
| 30 | `docs(operations): publish final staging runbook and evidence index` | docs waiver | link/command/required-variable review | Pass |

## Local verification summary

| Gate | 결과 |
|---|---|
| backend `backendQualityCheck` / `apiContractTest` | Pass |
| `userServiceQualityCheck` | Pass |
| `adminServiceQualityCheck` / `securityTest` | Pass |
| `sourceDataQualityCheck` | Pass |
| web lint/test/build | Pass; 214 tests, lint error 0, 기존 warning 8 |
| admin-web lint/test/build | Pass |
| nginx와 ops self-test | Pass |
| image smoke/security boundary | Pass |
| backup fake S3 + actual PostgreSQL restore | Pass |
| Terraform bootstrap/staging `fmt`, `validate`, `test` | Pass; bootstrap 1, staging 4 tests |
| GitHub workflow actionlint | Pass |
| k6 script inspect | Pass |
| map SQL 동일 DB benchmark | Fail; old cold/warm p95 4,781/4,344ms, set-based 26,496/27,179ms |
| 실제 AWS staging plan/apply/smoke/k6 | not run — map 성능 중단 조건에서 실행 종료 |

## Map SQL 성능 중단 근거

- 환경: Docker Desktop ARM64 host의 `postgis/postgis:16-3.4` AMD64 container,
  43,499 parcels, 43,978 complexes, 7,527,143 trades.
- 요청: `seed-wide` bounds (`37.45,126.85` ~ `37.70,127.20`), filter 없음.
- 결과 parity: old/new 모두 8,595 rows, SHA-256
  `e2e6c411a9f5be44c7f942376ef803df8eaf6f433f5b47b947c411ed3cb42fd9`.
- cold 5회: old p95 `4,781.230ms`, set-based p95 `26,496.049ms`.
- 1회 예열 후 warm 10회: old p95 `4,343.613ms`, set-based p95
  `27,178.997ms`.
- 원인: bounds 내 약 109만 trade에 두 방향 `WindowAgg`/sort가 발생하고 temp
  spill이 생겼다. aggregate/latest-PK와 global `DISTINCT ON` 대안도 각각
  약 40.6초와 20.5초로 실패했다.
- 판정: schema/index 변경 없이 목표를 달성하지 못했으므로 계획의 중단 조건에
  따라 commit 6을 PR에서 제거했다.

## 검증 공백

- AWS remote state migration, foundation plan/apply, ECR push, ECS migration,
  backup schedule, restore rehearsal, rollback은 map 성능 중단 조건으로 실행하지
  않았다.
- ACM/DNS, OAuth/Kakao credential, ML model artifact의 운영 유효성은 staging에서
  확인해야 한다.
- k6 cold 값은 destructive cache clear가 아닌 first-request proxy다.

## 잔여 위험

- 최초 staging 배포는 이전 ECS revision이 없어 smoke 실패 시 자동 rollback할
  대상이 없다.
- Terraform release plan은 workload allowlist 밖 변경을 차단하므로 network/data
  변경은 별도 operator apply가 필요하다.
- map SQL은 schema/index 변경을 허용하는 별도 계획 또는 다른 query 전략이
  승인되기 전까지 기존 lateral 구현을 유지한다.
- map 성능 목표와 3회 안정 baseline이 확보되기 전 k6는 evidence-only다.

## PR body 필수 문구

```text
계약 영향: 공개 URL/기존 response field 변경 없음
TDD 근거: docs/VERIFICATION_EVIDENCE.md 커밋별 근거 참조
보안 영향: OIDC direct-workflow claim 호환성과 secret/IAM/ingress/backup 경계 검토 완료
security-audit: 지적사항 = none
검증 공백: map 성능 gate 실패로 실제 AWS staging plan/apply/smoke/k6 not run
잔여 위험: map SQL 최적화 재계획, 최초 배포 rollback 공백, 3회 baseline 필요
```

# 건축물대장 전국 profile 발행 실행 기록

## 목표와 범위

- 전국 건축물대장 83개 필드를 typed publication으로 보존한다.
- 검증된 complex 값과 충돌 없는 PNU fallback을 분리해 상세 API와 지도 필터에 공개한다.
- 기존 API URL·최상위 필드 의미와 기존 non-null 운영값은 유지한다.
- archive, raw, 기존 worktree/branch, `output/`, 임시 복원 DB는 삭제하지 않는다.

## 선행 Gate

- 기준 branch: `origin/main` (`501c4567`)
- 실행 branch: `feat/building-register-profile-publication`
- V33 source commit: `a718d371` (원본 `573d47c3` cherry-pick)
- `printNextApiMigrationVersion`: `NEXT_API_MIGRATION_VERSION=34`
- durable DB V33 Flyway checksum: `-2101973446`
- source V33 Flyway checksum: `-2101973446`
- `JdbcBuildingProfileRatioBackfillMigrationTest`: `Pass`
- `verifyPropertyFlywayFresh`: `Pass` (migration 1→33)
- 시작 시 사용 가능 공간: 약 `172 GiB`

## Slice 실행 근거

각 slice는 `최초 RED → 예상 RED 실패 확인 → 최소 GREEN → 대상 검증 → 지적사항 리뷰 → 재검증 → 로컬 커밋` 순서로 기록한다.

### Slice 1 — publication 저장

- 최초 RED: `JdbcBuildingRegisterProfilePublicationMigrationTest`
- 예상 RED 실패: V34/V35 table과 runtime grant가 아직 존재하지 않는다.
- 최소 GREEN: V34 typed publication 6개 table, 83개 field evidence, 단일 `PUBLISHED`
  제약, 원자 publication 전환 함수와 V35 최소 권한을 추가했다.
- 검증 근거 확인:
  - `BuildingProfilePublicationDomainTest`: `Pass`
  - `JdbcBuildingRegisterProfilePublicationMigrationTest`: `Pass`
  - 83개 필드 수: `SITE=35`, `BUILDING=39`, `HIERARCHY=9`
  - 불완전 publication 전환 거부 및 기존 publication 유지: `Pass`
- 지적사항: runtime role의 `DELETE`/`TRUNCATE` 권한은 부여하지 않았다.

### Slice 2 — effective summary와 운영 컬럼 보강

- 최초 RED: `BuildingProfileEffectiveValuePolicyTest`
- 예상 RED 실패: shared PNU consensus, complete `SUM`, partial `MAX` 정책 객체가 없었다.
- 최소 GREEN: `DECIMAL128` 기반 consensus/SUM/MAX decision과 nullable direct 컬럼의
  verified-complex-only backfill 함수를 추가했다.
- 검증 근거 확인:
  - `BuildingProfileEffectiveValuePolicyTest`: `Pass`
  - 기존 `unit_cnt=999`, `bc_rat=20.00` 보존: `Pass`
  - null `family_cnt`, `ho_cnt`만 보강: `Pass`
  - `PARTIAL` 최고층 direct 반영 차단: `Pass`
- 지적사항: PNU fallback은 summary/API 후보로만 유지하고 direct complex ratio에는 쓰지 않는다.

### Slice 3 — 상세 API 공개

- 최초 RED: 두 상세 URL의 `buildingProfile` scope/quality/count 0 및 내부 식별자 비노출 assertion.
- 예상 RED 실패: 기존 response에 `buildingProfile`이 없었다.
- 최소 GREEN: 두 URL이 공유하는 `ParcelDetailResult`와 response mapper에 nullable section DTO를 추가했다.
- 검증 근거 확인:
  - `:api:apiContractTest`: `Pass`
  - `:api:restDocsTest`: `Pass`
  - 기존 최상위 필드와 URL 변경: `0건`
  - management key, PNU, provider/raw 식별자 공개: `0건`

### Slice 4 — 건폐율·용적률 지도 필터

- 최초 RED: decimal range 전달/역전 400, direct 우선/PNU fallback persistence, filter chip test.
- 예상 RED 실패: request DTO, SQL parameter, cache key, frontend definition이 없었다.
- 최소 GREEN: 기존 shape-filter SQL 하나를 확장하고 cache prefix를 `schema-b`로 올렸다.
- 검증 근거 확인:
  - `JdbcMapMarkerRepositoryTest`: `Pass` (18 tests)
  - `MapControllerContractTest`: `Pass`
  - `MapApiRestDocsTest`: `Pass`
  - web 전체 test: `Pass` (69 files, 385 tests)
  - web lint: `Pass` (오류 0, 기존 warning 6)
  - web build: `Pass`
- 지적사항: fallback JOIN은 `scope=PARCEL`, `quality=PNU_FALLBACK`만 허용하며
  무필터 요청은 기존 trade-first SQL을 유지한다.

### Slice 5 — 생활·안전·에너지 상세 UI

- 최초 RED: nullable profile, valid count 0, fallback/partial badge, native `details`, energy zero 숨김.
- 예상 RED 실패: adapter type과 전용 표시 컴포넌트가 없었다.
- 최소 GREEN: `fetchComplexDetail`에서 한 번 정규화하고 101-line 전용 panel을 기존 sidebar에 조립했다.
- 검증 근거 확인:
  - detail adapter/panel target test: `Pass` (9 tests)
  - web 전체 test: `Pass` (Slice 4와 함께 385 tests)
  - web lint/build: `Pass`
  - 추가 HTTP 요청 및 새 전역 상태: `0건`
- 지적사항: 주·부속 건물, 주차 대수·면적, 층·높이, 승강기, 날짜·도로명주소를
  생활정보에 두고 안전·에너지는 native `<details>`로 접었다.

### Slice 6 — 실패·계층 gap 선별 재수집

- 최초 RED: `BatchJobArgumentsTest.parsesBuildingProfileRepairArguments`
- 예상 RED 실패: `complexBuildingRegisterProfileRepairJob`이 지원 job 목록과 인자 parser에 없었다.
- 최소 GREEN: source의 latest provider/parse failure와 명시적 hierarchy reason만 새 campaign에
  freeze하고, 완료 `PARSED|EMPTY` raw page는 body/record와 함께 복사해 provider 재호출을 막는다.
- 검증 근거 확인:
  - `BuildingProfileRepairServiceTest`: `Pass`
  - `JdbcBuildingProfileRepairRepositoryTest`: `Pass`
  - `JdbcBuildingRegisterEndpointSnapshotStoreTest.clonesCompletedSourcePageForRepairResume`: `Pass`
  - repair batch arguments/tasklet/context boundary: `Pass`
  - 동일 transient provider failure 3회 이후 추가 요청: `0건`
  - BASIC 호출: `includeBasicOverview=true`이지만 domain hierarchy policy가 reason을 요구한다.
  - advisory lock: 기존 `BuildingMetadataExecutionLock` 공유
- 실제 run: `not run` (운영 UUID·provider 인증·당일 quota 확인 전)
- 진행 조회: `psql -v collection_id=<UUID> -f ops/building-register-profile-repair-progress.sql`

### Slice 7 — archive와 데이터 검증

- read-only 확인 DB: `home-search-profile-analysis-postgis-arm64/home_search`
- 확인 행 수: profile record `1,239,950`, value `28,149,028`, hierarchy reason `88,816`,
  complex match `44,200`, parse page `118,971`, DB size `6,938,399,203 bytes`.
- field identifier: `SITE=35`, `BUILDING=39`, `HIERARCHY=9` (`총 83`).
- primary 기준: complex `44,217`, parcel `43,738`, projected complex `44,200`, building `19,859`.
- ratio PNU coverage: BC `84.47656732%`, VL `84.63667345%`.
- 기존 archive manifest:
  - archive id: `031150dc-9801-4c89-86d7-1df0fbc559d5`
  - recorded SHA-256: `5e7bf03df3304896cfe4ea77b07f14b39af654db6a25f1ee405a936be796402e`
  - recorded bytes: `2,985,838,267`
  - status: `CLEANED`
- 검증 공백: manifest의 `archive_uri` 파일이 이미 존재하지 않아 SHA 재계산과 ARM restore를
  실행할 수 없다. 이 작업에서는 archive, raw, temp DB를 삭제하거나 변경하지 않았다.
- publication 검증 query: `psql -v publication_id=<UUID> -f ops/building-register-profile-publication-verify.sql`

## 중단 조건

- V33 checksum 또는 archive SHA 불일치
- 기존 non-null 운영값 변경
- 저장공간 100 GiB 미만 또는 예상 신규 저장 40 GB 초과
- PNU conflict 임의 선택 필요
- destructive DB/Docker 조작 또는 secret/env 변경 필요
- 확정된 공개 API 의미 재변경 필요

## 보안 영향

보안 영향: 건축물대장 공개 read-only 필드와 내부 publication/repair 경로 추가

security-audit: 지적사항 = none

- SQL 숫자 필터는 named parameter만 사용한다.
- runtime role은 publication child/evidence/summary에 `SELECT,INSERT`만 가지며
  `UPDATE,DELETE,TRUNCATE`는 갖지 않는다.
- 실제 row count와 SHA-256 형식을 검사하는 `SECURITY DEFINER` 함수만
  `VALIDATED`/`PUBLISHED` 전환과 null-only backfill을 수행한다.
- provider key, management key, PNU, raw body/식별자는 public DTO에 포함하지 않는다.
- repair 로그에는 request URL, provider key, PNU, raw body를 기록하지 않는다.
- provider client의 기존 2MiB response limit과 authentication/quota fatal stop을 유지한다.

## 최종 리뷰 상태

- 상태: `Partial`
- reviewer: 지적사항 = `listed`
- 높음(High): source profile EAV를 V34 typed publication/summary로 구성하고 portable export/import하는
  실행 경로가 아직 없다. 따라서 schema, 정책, API/UI와 검증 함수는 구현됐지만 primary DB에 실제
  publication을 적재·발행하지 않았다.
- 높음(High): 기존 archive manifest는 `CLEANED`이고 기록된 `archive_uri` 파일이 없어 SHA-256 재검산,
  신규 archive 작성, ARM restore 인수 검증을 완료할 수 없다.
- 검증 공백: provider 인증·quota와 운영 UUID가 필요한 repair 실run, 후속 publication 전환,
  direct 컬럼 전후 운영 snapshot 비교는 `not run`이다.
- 잔여 위험: `buildingProfile`과 ratio fallback은 실제 `PUBLISHED` publication이 생기기 전까지 null 또는
  기존 direct 값만 제공한다.
- 다음 행동: 원본 archive 또는 동등한 portable export를 복구하고 source EAV→typed publication
  builder/importer를 구현한 뒤 repair→parse/analyze→후속 publication→archive/ARM restore 순으로 재개한다.
- 삭제·`TRUNCATE`·`dropdb`·volume 제거: `0건`.

### 최종 검증 근거

- `./gradlew :core:persistenceTest --tests '*JdbcCleanCoreReferenceDataMigrationTest' --no-daemon --stacktrace` = pass
  (V34/V35 migration 목록과 fresh schema fingerprint `9a0a688c12bed13792202cfa1bdee771` 확인)
- `./gradlew :core:persistenceTest --tests '*JdbcMarketNewsRepositoryIntegrationTest' --no-daemon --stacktrace` = pass
  (전체 gate의 최초 EOF 실패 class 단독 재현 실패, 2분 1초)
- `./gradlew verifyPropertyFlywayFresh --no-daemon --stacktrace` = pass
  (migration 1→35, preflight `before target=35 EMPTY`와 `after target=35 READY`, Flyway validate)
- `bash ops/test-property-deployment-preflight.sh` = pass
  (V34/V35 catalog, history, target contract)
- `./gradlew backendQualityCheck --no-daemon --stacktrace` = fail
  (29분 실행 중 서로 다른 Testcontainers PostgreSQL 두 개가 같은 시점에 외부 `signal 9`로 종료,
  `oom` event 없음; 이후 connection failure가 연쇄 발생)
- `npm run lint && npm run test && npm run build` = pass
  (lint 오류 0, 기존 warning 6, 69 files/385 tests, production build)
- `.github/scripts/test-classify-changes.sh` = pass
- `infra/postgres/verify-service-boundaries.sh` = pass
- `git diff --check` = pass

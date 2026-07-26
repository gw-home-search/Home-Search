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
- `verifyPropertyFlywayFresh`: `Pass` (V1→V33)
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

## 중단 조건

- V33 checksum 또는 archive SHA 불일치
- 기존 non-null 운영값 변경
- 저장공간 100 GiB 미만 또는 예상 신규 저장 40 GB 초과
- PNU conflict 임의 선택 필요
- destructive DB/Docker 조작 또는 secret/env 변경 필요
- 확정된 공개 API 의미 재변경 필요

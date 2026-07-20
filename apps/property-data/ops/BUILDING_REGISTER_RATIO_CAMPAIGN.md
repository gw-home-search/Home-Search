# 건축물대장 비율 수집·투영 runbook

이 절차는 건축HUB 총괄표제부를 우선 조회하고, 필요한 경우에만 표제부·기본개요를 수집해 `complex.bc_rat`와 `complex.vl_rat`의 NULL 값을 보완한다. 기존 non-null 비율과 metadata 상태·출처·면적 값은 변경하지 않는다.

## 1. 실행 전 확인

- DB에 property Flyway V11까지 적용되어 있어야 한다.
- `complexBuildingMetadataJob`과 새 캠페인 job을 동시에 실행하지 않는다. 두 job은 같은 PostgreSQL advisory lock을 사용한다.
- 건축HUB 승인 quota와 배포 환경의 `complex.metadata.daily-request-quota`를 확인한다. `maxRequests`는 둘 중 작은 값의 90% 이하여야 한다.
- 서비스키는 secret manager 또는 프로세스 환경변수 `BLD_SERVICE_KEY`로만 주입한다. 명령 기록, 로그, SQL, metric label에 키나 요청 URL을 남기지 않는다.
- live provider 호출과 projection은 각각 별도 운영 승인을 받은 뒤 실행한다.
- 최초 검증 캠페인은 `strategy=full-hierarchy`로 약 250개 PNU만 수집하고 projection하지 않는다.

사전 검증:

```bash
cd apps/property-data
./gradlew verifyPropertyFlywayFresh --no-daemon --stacktrace
./gradlew :batch:bootJar --no-daemon --stacktrace
```

필수 런타임 환경변수는 `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `BLD_SERVICE_KEY`, `PROPERTY_DATA_BATCH_JAR`다. 값을 출력하는 `env`, `set`, `echo` 명령을 운영 기록에 사용하지 않는다.

## 2. 수집 캠페인 생성

캠페인마다 고정 UUID `collectionId`를 하나 발급하고, 실행마다 새 UUID `requestId`를 발급한다. 첫 실행의 `mode`, `strategy`, `fromComplexId`, `toComplexId`는 target과 함께 동결되며 같은 `collectionId`에서 변경할 수 없다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterCollectJob

ops/run-batch-jar.sh \
  collectionId=<collection-uuid> \
  requestId=<request-uuid> \
  runDate=<yyyy-MM-dd> \
  mode=missing \
  strategy=full-hierarchy \
  maxRequests=<approved-90-percent-limit> \
  fromComplexId=<optional-positive-id> \
  toComplexId=<required-positive-id>
```

`missing|retry` 모두 캠페인 시작 시 두 비율 중 하나라도 NULL인 단지를 동결하며 mode 값은 캠페인 운영 분류로 보존된다. 이미 생성된 캠페인의 미완료 대상을 이어서 처리할 때는 최초 실행과 같은 mode를 사용한다. `adaptive`는 총괄 직접 비율이 완전하면 fallback endpoint를 호출하지 않으며, `full-hierarchy`는 검증 표본에서 세 endpoint를 모두 수집한다.

HTTP/provider/parser 실패는 정상 empty로 취급하지 않는다. HTTP `401|403|429` 또는 provider 인증·quota 코드 `20|21|22|30|31|32`가 발생하면 job을 실패시키고 후속 호출을 금지한다. 응답이 2MiB를 넘으면 endpoint snapshot을 증거로 남기고 `100 → 50 → 25 → 10` 순서의 새 snapshot으로 재시작한다.

## 3. 같은 캠페인 재개

날짜가 바뀌었거나 request budget이 소진된 경우 `collectionId`와 동결 범위·mode·strategy는 유지하고 `requestId`, `runDate`, `maxRequests`만 바꿔 다시 실행한다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterCollectJob

ops/run-batch-jar.sh \
  collectionId=<same-collection-uuid> \
  requestId=<new-request-uuid> \
  runDate=<new-yyyy-MM-dd> \
  mode=missing \
  strategy=full-hierarchy \
  maxRequests=<approved-90-percent-limit> \
  fromComplexId=<same-optional-id> \
  toComplexId=<same-required-id>
```

같은 날짜의 `PARSED`·`EMPTY` page는 다시 호출하거나 중복 저장하지 않는다. 날짜가 바뀐 미완성 endpoint snapshot은 보존하고 page 1부터 새 snapshot을 시작한다. 모든 target이 terminal match 상태가 되어야 campaign이 `COMPLETED`가 된다.

## 4. 수집 결과 검증

다음 SQL은 `psql -v collection_id='<collection-uuid>'`로 실행한다. PNU, 관리번호, raw body는 결과 보고서나 metric label에 포함하지 않는다.

```sql
SELECT status, count(*)
FROM building_register_collection_campaign
WHERE collection_id = :'collection_id'::uuid
GROUP BY status;

SELECT endpoint, status, page_size, count(*)
FROM building_register_endpoint_snapshot
WHERE collection_id = :'collection_id'::uuid
GROUP BY endpoint, status, page_size
ORDER BY endpoint, status, page_size;

SELECT p.status, count(*)
FROM building_register_raw_page p
JOIN building_register_endpoint_snapshot s ON s.id = p.endpoint_snapshot_id
WHERE s.collection_id = :'collection_id'::uuid
GROUP BY p.status
ORDER BY p.status;

SELECT status, match_path, scope, projectable, count(*)
FROM building_register_complex_match
WHERE collection_id = :'collection_id'::uuid
GROUP BY status, match_path, scope, projectable
ORDER BY status, match_path, scope, projectable;

SELECT c.field, c.method, c.status, c.selected, count(*)
FROM building_ratio_candidate c
JOIN building_register_complex_match m ON m.id = c.match_id
WHERE m.collection_id = :'collection_id'::uuid
GROUP BY c.field, c.method, c.status, c.selected
ORDER BY c.field, c.method, c.status, c.selected;
```

다음 인수 기준 SQL은 모두 `0`이어야 한다.

```sql
-- 선택 candidate에 source input이 없는 건수
SELECT count(*)
FROM building_ratio_candidate c
JOIN building_register_complex_match m ON m.id = c.match_id
WHERE m.collection_id = :'collection_id'::uuid
  AND c.selected
  AND NOT EXISTS (
    SELECT 1 FROM building_ratio_candidate_input i WHERE i.candidate_id = c.id
  );

-- shared recap인데 projectable로 판정된 건수
SELECT count(*)
FROM building_register_complex_match
WHERE collection_id = :'collection_id'::uuid
  AND scope = 'SHARED_RECAP'
  AND projectable;

-- 선택 candidate가 필드당 둘 이상인 건수
SELECT count(*)
FROM (
  SELECT c.match_id, c.field
  FROM building_ratio_candidate c
  JOIN building_register_complex_match m ON m.id = c.match_id
  WHERE m.collection_id = :'collection_id'::uuid AND c.selected
  GROUP BY c.match_id, c.field
  HAVING count(*) > 1
) duplicated_selection;
```

검증 캠페인에서는 method마다 최대 30건을 건축HUB 원문과 수동 대조한다. 다음도 기록한다.

- 총괄 직접값과 구성요소 계산값의 0.01 percentage point 이내 일치율
- 표제부 직접값 distinct 분포와 완전한 aggregate 생성 비율
- `INCOMPLETE_HIERARCHY`, orphan, `AMBIGUOUS_GENERATION`, `SOURCE_CONFLICT` 분포
- raw 저장량과 전체 캠페인 예상 DB 용량
- `SHARED_RECAP` candidate가 존재해도 `projectable=false`인지

raw 응답에 소유자정보 또는 예상하지 못한 개인정보·민감 필드가 보이면 즉시 캠페인을 중단하고 security review를 수행한다. 해당 body를 티켓이나 채팅에 복사하지 않는다.

## 5. projection

`COMPLETED` 캠페인만 투영할 수 있다. 최초 100건에서 DB와 상세 API의 `bcRat`, `vlRat`를 대조한 뒤 1,000건, 전체 고정 범위 순으로 확대한다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRatioProjectJob

ops/run-batch-jar.sh \
  collectionId=<completed-collection-uuid> \
  requestId=<new-projection-request-uuid> \
  runDate=<yyyy-MM-dd> \
  maxTargets=100 \
  fromComplexId=<optional-positive-id> \
  toComplexId=<optional-positive-id>
```

각 비율은 독립 transaction에서 단지 row를 잠그고 NULL일 때만 갱신한다. 같은 `requestId`, match, field를 재실행하면 기존 projection 결과를 반환하며 duplicate update를 만들지 않는다. candidate가 없는 필드도 `candidate_id IS NULL`, `outcome='SOURCE_MISSING'`으로 남긴다.

projection 검증:

```sql
SELECT p.field, p.outcome, count(*)
FROM building_ratio_projection p
JOIN building_register_complex_match m ON m.id = p.match_id
WHERE m.collection_id = :'collection_id'::uuid
GROUP BY p.field, p.outcome
ORDER BY p.field, p.outcome;

-- 아래 세 값은 모두 0이어야 한다.
SELECT count(*) FROM building_ratio_projection
WHERE outcome = 'APPLIED' AND previous_value IS NOT NULL;

SELECT count(*)
FROM building_ratio_projection p
JOIN building_register_complex_match m ON m.id = p.match_id
WHERE m.collection_id = :'collection_id'::uuid
  AND m.scope = 'SHARED_RECAP'
  AND p.outcome = 'APPLIED';

SELECT count(*)
FROM building_ratio_projection p
JOIN building_ratio_candidate c ON c.id = p.candidate_id
JOIN building_register_complex_match m ON m.id = p.match_id
WHERE m.collection_id = :'collection_id'::uuid
  AND c.status = 'SOURCE_CONFLICT'
  AND p.outcome = 'APPLIED';
```

## 6. 중단과 복구

- 새 job은 자동 스케줄링하지 않는다. 실행 중지가 1차 rollback이다.
- raw, snapshot, match, candidate, projection 증거를 삭제하거나 V11 table을 drop하지 않는다.
- Docker volume을 삭제하거나 DB를 초기화하지 않는다.
- 기존 non-null 비율, `metadata_status`, `metadata_source`, 관리번호, 면적, 동수·세대수·사용승인일을 수정하지 않는다.
- 잘못 적용된 값은 `previous_value IS NULL`이고 현재 단지 값이 `applied_value`와 같은 projection 행으로만 식별한다.
- 실제 NULL 복원은 DB backup, 영향 행 목록, 사용자 승인을 확보한 뒤 별도 보상 migration 또는 update로 수행한다. 이 runbook은 자동 보상 명령을 제공하지 않는다.

public API URL, 응답 필드, 타입, 퍼센트 단위는 변경하지 않는다. 이 전제가 깨지거나 shared recap 복제, `totArea` 기반 용적률 계산, 기존 non-null 덮어쓰기가 필요해지면 rollout을 중단한다.

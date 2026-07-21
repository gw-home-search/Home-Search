# 건축물대장 Profile discovery runbook

이 절차는 운영 projection 전에 총괄표제부·표제부의 전체 문서화 필드를 versioned typed staging으로 보존하고 품질을 측정한다. `complex`, 기존 ratio candidate/projection, 공개 API는 변경하지 않는다.

## 안전 원칙

- DB와 Docker volume, 기존 campaign/raw/evidence를 삭제하거나 초기화하지 않는다.
- `complexBuildingRegisterCollectJob`, `complexBuildingMetadataJob`, profile collect/replay/analyze/import job을 동시에 실행하지 않는다. 모든 job은 같은 PostgreSQL advisory lock을 사용한다.
- replay와 analyze는 외부 API를 호출하지 않는다.
- collect만 `BLD_SERVICE_KEY`를 사용하며 키·keyed URL·raw body를 로그나 보고서에 남기지 않는다.
- 소유자정보·전유부 endpoint는 이 job의 endpoint 목록에 없다.
- V13 적용 전 `./gradlew verifyPropertyFlywayFresh --no-daemon --stacktrace`를 통과해야 한다.
- 출력 directory는 Git worktree 밖의 untracked 절대 경로를 사용한다.

## 1. 법정동코드 mapping import

공식 변경 파일은 검토 후 다음 canonical UTF-8 CSV로 변환한다. header가 다르거나 구 코드가 중복되거나 시행일이 비어 있으면 job이 거절한다.

```text
old_legal_dong_code,new_legal_dong_code,effective_date
2811010100,2814010100,2026-07-01
```

```bash
export SPRING_BATCH_JOB_NAME=legalDongCodeMappingImportJob

ops/run-batch-jar.sh \
  importId=<mapping-import-uuid> \
  effectiveDate=2026-07-01 \
  sourceFile=<reviewed-absolute-csv-path>
```

source SHA-256와 시행일은 `legal_dong_code_import`에 고정된다. import는 `parcel.pnu`, `complex`, 기존 alias를 수정하지 않는다.

## 2. 기존 raw offline replay

기존 campaign UUID와 새 parse UUID를 사용한다. `maxPages`만큼 처리한 뒤 같은 인자로 재실행하면 완료된 raw page는 건너뛴다. 새 parser version은 반드시 새 `parseRunId`를 사용하며 이전 typed record를 수정하지 않는다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterProfileReplayJob

ops/run-batch-jar.sh \
  sourceCollectionId=<existing-collection-uuid> \
  parseRunId=<new-parse-run-uuid> \
  parserVersion=PROFILE_V2 \
  maxPages=<positive-resume-unit>
```

`building_register_profile_parse_page`의 `PARSED|EMPTY|PROVIDER_FAILED|PARSE_FAILED`를 확인한다. BASIC은 관리번호·상위번호·대장 종류·신구대장만 typed 저장한다. unknown key와 type/parse 문제는 `building_register_profile_schema_observation`에서 조회한다.

## 3. 신규 1,500 PNU 표본 수집

첫 실행에서 seed·strata·weight·PNU와 같은 PNU의 모든 complex를 동결한다. 같은 `collectionId`에서는 seed와 sample size를 바꿀 수 없다. `parallelism` 허용 범위는 `1..4`이고 기본값은 `2`다. PNU worker만 bounded 병렬 실행하며 한 PNU 내부 endpoint와 pagination은 순차 처리한다. 실제 HTTP attempt가 공유 atomic request budget을 하나씩 사용한다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterProfileCollectJob

ops/run-batch-jar.sh \
  collectionId=<profile-collection-uuid> \
  requestId=<execution-uuid> \
  runDate=<yyyy-MM-dd> \
  purpose=profile-discovery \
  targetScope=validation-sample \
  strategy=compare-recap-title \
  sampleSize=1500 \
  selectionSeed=<fixed-reviewed-seed> \
  maxRequests=<approved-limit> \
  parallelism=2
```

총괄과 표제부는 항상 조회한다. 동일 PNU 복수 complex/root, 총괄 없는 복수 title, parent 누락·충돌, 신구대장 불명확, title 배정 불가가 있을 때만 BASIC을 조회하며 사유를 `building_register_profile_hierarchy_reason`에 남긴다.

법정동코드 mapping 영향 PNU는 기존 PNU와 앞 10자리만 치환한 candidate PNU를 독립 조회한다. HTTP/provider/parse 실패는 코드 불일치로 판정하지 않는다. 관리번호 원문은 보고서 대신 DB 내부 hash 집합으로 비교한다.

## 4. 수집 재개

날짜 또는 quota가 바뀌면 `collectionId`, `sampleSize`, `selectionSeed`는 유지하고 새 `requestId`, `runDate`, 남은 `maxRequests`로 다시 실행한다. 완료 page는 재호출하지 않는다. 인증·quota 오류는 즉시 실패하며 정상 empty로 바꾸지 않는다.

```bash
ops/run-batch-jar.sh \
  collectionId=<same-profile-collection-uuid> \
  requestId=<new-execution-uuid> \
  runDate=<new-yyyy-MM-dd> \
  purpose=profile-discovery \
  targetScope=validation-sample \
  strategy=compare-recap-title \
  sampleSize=1500 \
  selectionSeed=<same-fixed-seed> \
  maxRequests=<remaining-approved-limit> \
  parallelism=2
```

## 5. Profile parse와 분석

신규 collection이 `COMPLETED`가 되면 해당 raw를 `PROFILE_V2`로 replay한 뒤 분석한다. analysis는 완료된 collection/parse run만 허용하며 같은 `analysisRunId` 재실행은 duplicate evidence를 만들지 않는다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterProfileAnalyzeJob

ops/run-batch-jar.sh \
  collectionId=<completed-profile-collection-uuid> \
  parseRunId=<completed-profile-parse-run-uuid> \
  analysisRunId=<analysis-uuid> \
  rulesVersion=PROFILE_V1 \
  outputDirectory=<untracked-absolute-directory>
```

생성 파일:

- `building-register-profile-quality.csv`
- `building-register-profile-quality.json`
- `building-register-profile-quality.md`

파일에는 PNU·관리번호·raw body·서비스키가 포함되지 않는다. 상세 assignment와 code-transition evidence는 DB에서만 제한적으로 조회한다.

## 6. 판정과 검증

- `PROMOTE_CANDIDATE`는 site의 weighted PNU coverage/projectable readiness 또는 building의 weighted building/PNU coverage가 모두 90% 이상이고 invalid 0.1% 이하, comparable conflict 0.5% 이하일 때만 추천한다.
- 낮은 coverage는 typed 저장 거절 사유가 아니다. 유효한 희소 값은 `RETAIN_PROFILE`로 남긴다.
- `SHARED_SCOPE`, `INCOMPLETE_HIERARCHY`, `SOURCE_CONFLICT`, `AMBIGUOUS_GENERATION`은 profile에는 남지만 projectable하지 않다.
- 건폐율은 `SUM(archArea) / platArea × 100`, 용적률은 `SUM(vlRatEstmTotArea) / platArea × 100`이다. `totArea`를 용적률 계산에 사용하지 않는다.

```sql
SELECT stratum,population_count,sample_count,sampling_weight
FROM building_register_profile_sample_stratum
WHERE collection_id=:'collection_id'::uuid
ORDER BY stratum;

SELECT endpoint,status,count(*)
FROM building_register_profile_parse_page page
JOIN building_register_raw_page raw ON raw.id=page.raw_page_id
JOIN building_register_endpoint_snapshot snapshot ON snapshot.id=raw.endpoint_snapshot_id
WHERE page.parse_run_id=:'parse_run_id'::uuid
GROUP BY endpoint,status ORDER BY endpoint,status;

SELECT field_id,quality_tier,pnu_coverage,building_coverage,
       projectable_complex_readiness,invalid_rate,conflict_rate,wilson_low,wilson_high
FROM building_register_profile_field_quality
WHERE analysis_run_id=:'analysis_run_id'::uuid
ORDER BY field_id;
```

전국 수집, 운영 schema projection, 새 공개 API 필드 추가는 이 보고서와 최대 30건 수동 대조를 검토한 뒤 별도 승인으로 진행한다.

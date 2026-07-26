# 건축물대장 Profile discovery runbook

이 절차는 총괄표제부·표제부의 전체 문서화 필드를 versioned typed staging으로 보존하고 품질을 측정한 뒤, 검토된 55개 필드를 별도 정규화 테이블로 projection한다. `complex`, 기존 ratio candidate/projection, 공개 API는 변경하지 않는다.

## 안전 원칙

- DB와 Docker volume을 삭제하거나 초기화하지 않는다. 상세 profile evidence 정리는 archive의 SHA-256·행 수·ARM 복원이 모두 검증된 뒤에만 승인된 cleanup 절차로 수행한다.
- `complexBuildingRegisterCollectJob`, `complexBuildingMetadataJob`, profile collect/replay/analyze/import job을 동시에 실행하지 않는다. 모든 job은 같은 PostgreSQL advisory lock을 사용한다.
- replay와 analyze는 외부 API를 호출하지 않는다.
- collect만 `BLD_SERVICE_KEY`를 사용하며 키·keyed URL·raw body를 로그나 보고서에 남기지 않는다.
- 소유자정보·전유부 endpoint는 이 job의 endpoint 목록에 없다.
- 새 migration 적용 전 `./gradlew verifyPropertyFlywayFresh --no-daemon --stacktrace`를 통과해야 한다.
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

검증 표본에서는 총괄과 표제부를 항상 조회한다. 동일 PNU 복수 complex/root, 총괄 없는 복수 title, parent 누락·충돌, 신구대장 불명확, title 배정 불가가 있을 때만 BASIC을 조회하며 사유를 `building_register_profile_hierarchy_reason`에 남긴다.

법정동코드 mapping 영향 PNU는 기존 PNU와 앞 10자리만 치환한 candidate PNU를 독립 조회한다. HTTP/provider/parse 실패는 코드 불일치로 판정하지 않는다. 관리번호 원문은 보고서 대신 DB 내부 hash 집합으로 비교한다.

## 4. 전국 staging 수집

전국 수집은 `sampleSize`를 받지 않는다. 최초 실행 시 유효한 19자리 PNU 전체를 `NATIONWIDE_CENSUS`, weight `1`로 동결하며 이후 신규 complex가 생겨도 같은 `collectionId`의 대상은 바뀌지 않는다. 운영 `complex`와 기존 projection은 수정하지 않는다.

전국 선저장 단계에서는 총괄표제부와 표제부만 수집하고 기본개요는 호출하지 않는다. 계층 사유는 총괄·표제부 evidence로 계속 기록하되, 계층이 불완전한 PNU를 운영 projection 대상으로 사용하지 않는다. 기본개요는 전국 총괄·표제부 수집 완료 후 해당 사유가 있는 PNU만 별도 backfill campaign으로 수집한다.

```bash
ops/run-batch-jar.sh \
  collectionId=<nationwide-profile-collection-uuid> \
  requestId=<execution-uuid> \
  runDate=<yyyy-MM-dd> \
  purpose=profile-discovery \
  targetScope=nationwide-staging \
  strategy=compare-recap-title \
  selectionSeed=<fixed-reviewed-seed> \
  maxRequests=<approved-limit> \
  parallelism=3
```

`parallelism=3`으로 시작하고 인증·quota 오류가 없으며 provider failure가 안정적인 경우에만 새 execution에서 `4`로 올린다. 허용 범위는 계속 `1..4`다.

## 5. 수집 재개

날짜 또는 quota가 바뀌면 `collectionId`, `targetScope`, `selectionSeed`는 유지하고 새 `requestId`, `runDate`, 남은 `maxRequests`로 다시 실행한다. 검증 표본만 같은 `sampleSize=1500`을 유지한다. 전국 수집에는 `sampleSize`를 넣지 않는다. 완료 PNU는 건너뛰고, 중단 당시 `ACTIVE` endpoint snapshot과 이미 완료된 page도 다음 `runDate`에서 그대로 재개한다. 인증·quota 오류는 즉시 실패하며 정상 empty로 바꾸지 않는다.

```bash
ops/run-batch-jar.sh \
  collectionId=<same-profile-collection-uuid> \
  requestId=<new-execution-uuid> \
  runDate=<new-yyyy-MM-dd> \
  purpose=profile-discovery \
  targetScope=<validation-sample|nationwide-staging> \
  strategy=compare-recap-title \
  selectionSeed=<same-fixed-seed> \
  maxRequests=<remaining-approved-limit> \
  parallelism=2
```

검증 표본을 재개할 때만 위 명령에 `sampleSize=1500`을 추가한다.

## 6. Profile parse와 분석

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

## 7. 판정과 검증

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

## 8. 55개 필드 정규화 projection

완료된 전국 analysis를 입력으로 사용한다. projection은 `complex`를 수정하지 않으며 실행 전후 전체 `complex` 행의 SHA-256이 같지 않으면 transaction 전체를 실패시킨다. 동일 `projectionRunId` 재실행은 완료 결과를 그대로 반환한다.

```bash
export SPRING_BATCH_JOB_NAME=complexBuildingRegisterProfileProjectJob

ops/run-batch-jar.sh \
  projectionRunId=<projection-uuid> \
  analysisRunId=<completed-analysis-uuid> \
  projectionVersion=PROFILE_PROJECTION_V1
```

저장 위치:

- `complex_building_register_profile`: 전체 complex 1행씩, site 값·projectable 상태·원천 근거
- `complex_building_register_building`: 단일 scope에 안전하게 배정된 title별 building 값
- `building_register_profile_projected_quality`: 55개 필드의 compact 품질 판정
- `building_register_profile_projection_run`: 입력 run, 행 수, 실행 전후 `complex` SHA-256

## 9. Archive와 ARM 복원 검증

archive는 Git worktree 밖의 절대 경로에 보관한다. `PROPERTY_DB_PASSWORD`는 shell 환경에만 설정하며 파일이나 명령 인자에 기록하지 않는다. `archive`는 PostgreSQL custom-format 전체 DB dump와 SHA-256·byte count manifest를 만들고, `restore-verify`는 별도 ARM DB에 복원해 상세 evidence와 projection 행 수를 정확히 센다.

```bash
export PROFILE_COLLECTION_ID=<nationwide-collection-uuid>
export PROFILE_PARSE_RUN_ID=<completed-parse-uuid>
export PROFILE_ANALYSIS_RUN_ID=<completed-analysis-uuid>
export PROFILE_PROJECTION_RUN_ID=<completed-projection-uuid>
export PROFILE_ARCHIVE_ID=<archive-uuid>
export PROFILE_ARCHIVE_DIRECTORY=<untracked-absolute-directory>

ops/building-register-profile-archive.sh archive
ops/building-register-profile-archive.sh verify
ops/building-register-profile-archive.sh restore-verify
```

복원 검증이 끝나면 manifest는 `RESTORE_VERIFIED`가 되고 archive 시점의 정확한 행 수가 `row_counts`에 남는다. 검증용 임시 DB만 제거하며 archive 파일과 Docker volume은 제거하지 않는다.

## 10. 상세 staging 정리와 공간 회수

`cleanup`은 manifest가 `RESTORE_VERIFIED`이고 모든 profile campaign과 parse/analysis run이 terminal 상태일 때만 실행된다. 상세 EAV·비교·assignment·schema observation을 비우고 `PROFILE_DISCOVERY` raw body만 `NULL` 처리한다. campaign, sample, parse/analysis run, compact field quality, 정규화 projection, archive manifest는 유지하며 기존 ratio campaign raw body는 보존한다.

```bash
ops/building-register-profile-archive.sh cleanup
```

정리 후에는 다음을 다시 확인한다.

```sql
SELECT status,eligible_field_count,complex_count,projectable_complex_count,building_count,
       complex_checksum_before,complex_checksum_after
FROM building_register_profile_projection_run
WHERE projection_run_id=:'projection_run_id'::uuid;

SELECT status,row_counts,archive_sha256,archive_byte_count,restore_verified_at,cleaned_at
FROM building_register_profile_archive_manifest
WHERE archive_id=:'archive_id'::uuid;
```

새 공개 API 필드 추가와 기존 운영 column 반영은 compact 품질 결과와 수동 대조를 검토한 뒤 별도 승인으로 진행한다.

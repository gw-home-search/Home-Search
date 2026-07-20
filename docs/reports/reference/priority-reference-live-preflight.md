# 생활 인프라 priority reference live preflight

실행일: 2026-07-20

상태: `Partial`

실제 provider full import와 capability activation은 완료되지 않았다. source별 실패는
safe reason code와 count만 기록했고 provider body, API key, DSN은 기록하지 않았다.

## 실행 결과

| 순서 | sourceId | 결과 | 검증 근거 확인 |
|---:|---|---|---|
| 1 | `edu.school-location` | exit `1` | JSON media type은 확인했으나 JSON parse 이전 `API_ENVELOPE_INVALID`; dataset별 활용승인/key 또는 gateway 응답 확인 필요, acquisition `0` |
| 1b | `edu.school-location` | `Pass` | decimal-string pagination 반영 후 12,011행/rejected 0/17개 교육청; lock namespace·validated resume 보완, active datasetVersion `2026-03-20-b148752f1e38` |
| 1c | `edu.school-location` | `Pass` | 두 번째 13-page refresh가 동일 acquisition/publication을 재사용; acquisition 총 2개는 이전 incomplete 1개와 published 1개, publication은 계속 1개 |
| 2a | `edu.academy-registry` | exit `1` | `Accept: application/json`에 대한 NEIS gateway `HTTP 500 text/html`을 `API_SERVER_ERROR`로 안전 중단; key·body 비노출 |
| 2b | `edu.academy-registry` | exit `1` | gateway header 수정 후 138,412행을 수집했으나 live status `개원`과 명칭 공란 1건을 quality gate에서 차단; publication 없음 |
| 2c | `edu.academy-registry` | `Pass` | v4 full refresh 17개 교육청/146 pages/138,412 accepted/rejected 0; active datasetVersion `20260720-3bb7d33261d5` |
| 2d | `edu.academy-registry` | `NoChange` | 같은 날 두 번째 146-page refresh의 semantic checksum 동일; staging 0, v4 publication 1, active pointer 유지 |
| 3 | `place.sbiz-academy` | exit `1` | key 수정 후 인증 통과, live unscoped taxonomy `25/266/1,255`가 공식 포털·가이드 `10/75/247`과 불일치해 `TAXONOMY_CHANGED`; acquisition `0` |
| 3b | `place.sbiz-academy` | `Pass` | tracked official taxonomy artifact와 실제 18개 partition code/name exact 검증; 201 pages, 191,250 accepted, 좌표 100%, publication 1 |
| 3c | `place.sbiz-academy` | `NoChange` | 같은 날 전량 재수집 semantic checksum 동일; staging 0, active pointer 유지, 최신 raw S3 byte 복구 일치 |
| 4 | `retail.large-store` | exit `1` | 재시도에도 첫 page `API_AUTHENTICATION_FAILED`; dataset `15154948` 활용신청 미반영 가능성, acquisition `0` |
| 4b | `retail.large-store` | `Pass` | 공식 fileData CSV/CP949로 전환; 4,176 accepted, spatial 3,497·non-spatial 679, publication 1 |
| 4c | `retail.large-store` | `NoChange` | 두 번째 official file 수집 staging 0·active 유지·최신 raw S3 byte 복구 일치; 좌표 83.7404%로 activation 차단 |
| 5a | `transport.rail-station` | exit `2` | 최초 실행은 license `PENDING`으로 `CONFIGURATION_INVALID`; provider body 요청 전 중단 |
| 5b | `transport.rail-station` | exit `1` | 승인 후 `User-Agent` 없는 GET이 `200 text/html`; `FILE_MEDIA_TYPE_INVALID`로 안전 중단 |
| 5c | `transport.rail-station` | exit `1` | 실제 최신 header와 fixture 불일치를 `SOURCE_SCHEMA_MISMATCH`로 차단; exact alias와 `rail-station-v2` 적용 |
| 5d | `transport.rail-station` | exit `1` | 전체 1,099행·좌표 누락 0행을 읽었으나 occurrence key 5개/10행 중복, row 기준일 혼재·공란·비정상 값으로 `DUPLICATE_UNIQUE_KEY`, `SOURCE_DATE_MIXED`, `REJECTED_ROW_RATIO_EXCEEDED`; publication 없음 |
| 5e | `transport.rail-station` | exit `1` | row 기준일을 nullable provenance로 분리한 `rail-station-v3`에서 1,094행 유효·5행 중복 거부; 동일 재수집은 같은 acquisition을 재사용했고 publication 없음 |
| 5f | `transport.rail-station` | exit `1` | malformed row 날짜 6건을 warning으로 남긴 `rail-station-v4`; 1,094행 유효·5행 중복 거부, 두 번째 실행은 acquisition `d246e9c4-cb19-4cda-862a-92e8a260adbd` 재사용 |
| 5g | `transport.rail-station` | `Pass` | v5 occurrence identity·유일 최신일 규칙으로 1,097 accepted/rejected 0·좌표 100%, publication 1 |
| 5h | `transport.rail-station` | `Pass` | 같은 release 재수집이 동일 acquisition/publication을 재사용; raw S3 checksum·313,741 bytes 복구 일치 |

실행 명령:

```bash
apps/ai/ops/run-local-reference-refresh.sh --source edu.school-location
apps/ai/ops/run-local-reference-refresh.sh --source edu.academy-registry
apps/ai/ops/run-local-reference-refresh.sh --source place.sbiz-academy
apps/ai/ops/run-local-reference-refresh.sh --source retail.large-store
apps/ai/ops/run-local-reference-refresh.sh --source transport.rail-station
apps/ai/ops/run-local-reference-inspection.sh status
apps/ai/ops/run-local-reference-inspection.sh audit \
  --source edu.academy-registry --limit 3
```

NEIS body-free 진단에서 key 문제가 아니라 gateway의 `Accept` header 호환 문제가
원인임을 분리했다. 동일 실제 key의 B10 `pSize=1000`, `Accept: */*` 요청은
`HTTP 200/INFO-000`, 총 25,522행 중 1,000행을 반환했다. 전국 raw aggregate에서 status
전부가 `개원`이고 명칭 공란 1건만 존재함을 확인해 status mapping과 fail-closed exact
match를 보완했다. 최종 runtime status는 active datasetVersion
`20260720-3bb7d33261d5`, projection 138,412건을 반환한다.

Sbiz는 legacy taxonomy endpoint를 현행 공식 계약으로 사용하지 않고 checksum 고정된
공식 taxonomy artifact를 bundle에 포함하며 실제 store 행의 18개 code/name을 exact
검증한다. 대규모점포는 승인되지 않은 OpenAPI 대신 공공데이터포털이 연결한 공식 CSV
download를 provider key 없이 사용한다. 두 source 모두 local publication과 재수집을
완료했지만 capability activation은 별도 readiness 기준으로 계속 금지한다.

철도는 source contract 승인 후 실제 XLSX download와 verified raw 저장을 완료했다.
raw 중복을 재검토해 공식 `line_name`을 occurrence identity에 포함하고 동일 확장 identity는
유일 최신 유효일만 유지하는 v5 계약을 추가했다. 1,097 occurrence를 게시했고 두 번째
동일 release 수집은 같은 acquisition/publication을 재사용했다. 이전 v2~v4 failure와
warning evidence는 삭제하지 않았다.

## live에서 발견해 수정한 공백

- 최초 RED: `home-ai-reference-status --source ...`가 nullable parameter의 PostgreSQL
  type inference 실패로 `INSPECTION_UNAVAILABLE`을 반환했다.
- 최소 GREEN: nullable filter를 `%s::text`로 고정했다.
- 최초 RED: acquisition 생성 전 실패가 `reference_read.acquisition_audit`에서 누락됐다.
- 최소 GREEN: additive migration `0010_pre_acquisition_failure_audit.sql`이 failed
  refresh item을 빈 acquisition ID, 0 counts, safe reason code로 노출한다.
- local inspection wrapper는 runtime role만 사용하고 3초 read-only query를 유지하며,
  fake Docker test에서 runtime password 비노출을 검증했다.
- 학교 client는 non-JSON media type과 envelope 구조 단계를 body 비노출 reason code로
  분리하고 additive provider field를 허용한다. live 응답은 JSON media type이지만 JSON
  parse 이전에 실패해 schema 완화 대상이 아닌 provider/승인 확인 대상으로 좁혀졌다.
- 후속 safe 진단에서 정상 `resultCode='00'`과 decimal-string pagination을 확인해 parser를
  제한적으로 수정했다. actual full refresh는 12,011행을 게시했고, 이 과정에서 발견된
  refresh/publish advisory self-deadlock과 validated acquisition resume도 회귀 테스트로
  보완했다. 두 번째 actual refresh도 동일 raw checksum의 기존 acquisition/publication을
  재사용해 `Pass`했고 duplicate publication은 생성하지 않았다.
- NEIS gateway의 `Accept: application/json` 고정이 모든 요청을 `HTTP 500`으로 만들던
  원인을 재현하고 `Accept: */*`로 제한 수정했다. JSON query·parser·envelope 검증은
  그대로 유지한다.
- live status `개원` 138,412건은 `OPEN`으로 고정했고 provider 명칭 공란 1건은
  `명칭 미제공`과 `nameMissing=true`로 보존하되 exact-match key를 `NULL`로 만들었다.
- `OBSERVED_AT` exact timestamp가 normalized row hash에 섞여 같은 날 재수집에도 새
  publication을 만들던 문제를 실제로 재현했다. 정확한 시각은 evidence에 보존하고
  semantic hash에서만 제외해 v4 두 번째 actual refresh가 `NoChange` 및 staging 0임을
  확인했다.
- 철도 row 기준일을 release date와 비교해 전 행을 거부하던 오류를 수정하고 nullable
  provenance projection migration을 추가했다. occurrence identity 중복은 완화하지 않았다.

검증:

```text
reference inspection + migration focused: 11 passed
local inspection wrapper: Pass
AI full: 589 passed, coverage 90.05%
reference docs: Pass
```

## 활성화 판단

- `academy_registry_summary`: readiness `8.0/10 Partial`, 활성화 금지
- `academy_lookup`: readiness `3.0/10 Partial`, 활성화 금지
- `retail_location`: readiness `2.0/10 Partial`, 활성화 금지
- `rail_station_lookup`: readiness `3.0/10 Partial`, 활성화 금지

학교와 NEIS의 검증된 publication만 active pointer가 가리킨다. 실패·이전 publication,
raw, quarantine은 삭제하지 않았고 Docker volume 초기화도 수행하지 않았다.

`api-contract: compatible`

`security-audit: 지적사항 = none`

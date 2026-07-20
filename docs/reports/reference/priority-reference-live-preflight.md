# 생활 인프라 priority reference live preflight

실행일: 2026-07-20

상태: `Partial`

실제 provider full import와 capability activation은 완료되지 않았다. source별 실패는
safe reason code와 count만 기록했고 provider body, API key, DSN은 기록하지 않았다.

## 실행 결과

| 순서 | sourceId | 결과 | 검증 근거 확인 |
|---:|---|---|---|
| 1 | `edu.school-location` | exit `1` | JSON media type은 확인했으나 JSON parse 이전 `API_ENVELOPE_INVALID`; dataset별 활용승인/key 또는 gateway 응답 확인 필요, acquisition `0` |
| 2 | `edu.academy-registry` | exit `1` | 재시도에도 configured NEIS key는 첫 page `API_SERVER_ERROR`; acquisition `0`, active datasetVersion 없음 |
| 3 | `place.sbiz-academy` | exit `1` | key 수정 후 인증 통과, live unscoped taxonomy `25/266/1,255`가 공식 포털·가이드 `10/75/247`과 불일치해 `TAXONOMY_CHANGED`; acquisition `0` |
| 4 | `retail.large-store` | exit `1` | 재시도에도 첫 page `API_AUTHENTICATION_FAILED`; dataset `15154948` 활용신청 미반영 가능성, acquisition `0` |
| 5a | `transport.rail-station` | exit `2` | 최초 실행은 license `PENDING`으로 `CONFIGURATION_INVALID`; provider body 요청 전 중단 |
| 5b | `transport.rail-station` | exit `1` | 승인 후 `User-Agent` 없는 GET이 `200 text/html`; `FILE_MEDIA_TYPE_INVALID`로 안전 중단 |
| 5c | `transport.rail-station` | exit `1` | 실제 최신 header와 fixture 불일치를 `SOURCE_SCHEMA_MISMATCH`로 차단; exact alias와 `rail-station-v2` 적용 |
| 5d | `transport.rail-station` | exit `1` | 전체 1,099행·좌표 누락 0행을 읽었으나 occurrence key 5개/10행 중복, row 기준일 혼재·공란·비정상 값으로 `DUPLICATE_UNIQUE_KEY`, `SOURCE_DATE_MIXED`, `REJECTED_ROW_RATIO_EXCEEDED`; publication 없음 |
| 5e | `transport.rail-station` | exit `1` | row 기준일을 nullable provenance로 분리한 `rail-station-v3`에서 1,094행 유효·5행 중복 거부; 동일 재수집은 같은 acquisition을 재사용했고 publication 없음 |
| 5f | `transport.rail-station` | exit `1` | malformed row 날짜 6건을 warning으로 남긴 `rail-station-v4`; 1,094행 유효·5행 중복 거부, 두 번째 실행은 acquisition `d246e9c4-cb19-4cda-862a-92e8a260adbd` 재사용 |

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

runtime inspection 결과는 source를 `Partial`로 표시했고 active
datasetVersion과 dataAsOf가 비어 있었다. NEIS audit는 빈 `acquisitionId`, row count
모두 `0`, `reasonCodes: API_SERVER_ERROR`를 반환했다. 별도 body-free 진단에서 NEIS
no-key sample은 `HTTP 200/INFO-000`이지만 `pSize=1000`에도 총 25,522행 중 5행만
반환했고, 명백한 invalid key는 `HTTP 200/ERROR-290`이었다. sample을 full acquisition
대안으로 사용하지 않으며 `HOME_AI_NEIS_SERVICE_KEY`의 NEIS 발급 상태를 다시 확인해야 한다.

키 문자열은 Sbiz taxonomy endpoint 인증을 통과했으므로 유효하다. Sbiz collector는
공식 가이드의 parent scoped 중·소분류 요청으로 수정했지만 첫 대분류부터 공식
taxonomy count와 달라 `TAXONOMY_CHANGED`로 fail-closed한다. 대규모점포는 동일 키에 새 API 활용신청이
연결되기 전까지 `API_AUTHENTICATION_FAILED`로 유지한다.

철도는 source contract 승인 후 실제 XLSX download와 verified raw 저장까지 진행했다.
최신 header는 exact alias로 고정했고 같은 raw의 이전 parse failure를 삭제하지 않고
새 normalization schema에서 재처리했다. release date는 dataset `SOURCE_DATE`, 각 row
기준일은 nullable provenance로 분리해 `rail-station-v3`에서 1,099행 중 1,094행을
유효화했다. 최종 quality gate는 provider의 필수 occurrence key 중복 5행을 pointer
전환 전에 중단했다. `rail-station-v4`는 같은 raw를 새 acquisition으로 재처리해 nonblank
malformed 날짜 6건을 `RAIL_ROW_REFERENCE_DATE_INVALID` warning으로 기록했다. 즉시 두 번째
실행은 같은 v4 acquisition을 재사용했으며 publication이 없어 `NoChange`로 승격되지는 않았다.

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
- 철도 row 기준일을 release date와 비교해 전 행을 거부하던 오류를 수정하고 nullable
  provenance projection migration을 추가했다. occurrence identity 중복은 완화하지 않았다.

검증:

```text
reference inspection + migration focused: 11 passed
local inspection wrapper: Pass
AI full: 564 passed, coverage 90.10%
reference docs: Pass
```

## 활성화 판단

- `academy_registry_summary`: readiness `3.0/10 Partial`, 활성화 금지
- `academy_lookup`: readiness `3.0/10 Partial`, 활성화 금지
- `retail_location`: readiness `2.0/10 Partial`, 활성화 금지
- `rail_station_lookup`: readiness `3.0/10 Partial`, 활성화 금지

기존 active pointer를 변경한 source는 없고 raw/publication 삭제나 Docker volume
초기화도 수행하지 않았다.

`api-contract: compatible`

`security-audit: 지적사항 = none`

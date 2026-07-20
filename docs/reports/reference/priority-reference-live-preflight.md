# 생활 인프라 priority reference live preflight

실행일: 2026-07-20

상태: `Partial`

실제 provider full import와 capability activation은 완료되지 않았다. source별 실패는
safe reason code와 count만 기록했고 provider body, API key, DSN은 기록하지 않았다.

## 실행 결과

| 순서 | sourceId | 결과 | 검증 근거 확인 |
|---:|---|---|---|
| 1 | `edu.school-location` | exit `2` | 최초 실행에서 `HOME_AI_DATA_GO_KR_SERVICE_KEY` exact-once 설정 실패, 외부 호출 전 중단 |
| 2 | `edu.academy-registry` | exit `1` | DB role·MinIO·migration preflight 통과 후 첫 page `API_SERVER_ERROR`; acquisition `0`, active datasetVersion 없음 |
| 3 | `place.sbiz-academy` | exit `1` | key 수정 후 인증 통과, 공식 taxonomy fingerprint 불일치로 `TAXONOMY_CHANGED`; acquisition `0` |
| 4 | `retail.large-store` | exit `1` | 새 공식 API·license 계약 적용 후 첫 page `API_AUTHENTICATION_FAILED`; dataset `15154948` 활용신청 미반영 가능성, acquisition `0` |
| 5a | `transport.rail-station` | exit `2` | 최초 실행은 license `PENDING`으로 `CONFIGURATION_INVALID`; provider body 요청 전 중단 |
| 5b | `transport.rail-station` | exit `1` | 승인 후 `User-Agent` 없는 GET이 `200 text/html`; `FILE_MEDIA_TYPE_INVALID`로 안전 중단 |
| 5c | `transport.rail-station` | exit `1` | 실제 최신 header와 fixture 불일치를 `SOURCE_SCHEMA_MISMATCH`로 차단; exact alias와 `rail-station-v2` 적용 |
| 5d | `transport.rail-station` | exit `1` | 전체 1,099행·좌표 누락 0행을 읽었으나 occurrence key 5개/10행 중복, row 기준일 혼재·공란·비정상 값으로 `DUPLICATE_UNIQUE_KEY`, `SOURCE_DATE_MIXED`, `REJECTED_ROW_RATIO_EXCEEDED`; publication 없음 |

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
모두 `0`, `reasonCodes: API_SERVER_ERROR`를 반환했다.

키 문자열은 Sbiz taxonomy endpoint 인증을 통과했으므로 유효하다. Sbiz publication은
`TAXONOMY_CHANGED`로 fail-closed했고, 대규모점포는 동일 키에 새 API 활용신청이
연결되기 전까지 `API_AUTHENTICATION_FAILED`로 유지한다.

철도는 source contract 승인 후 실제 XLSX download와 verified raw 저장까지 진행했다.
최신 header는 exact alias로 고정했고 같은 raw의 이전 parse failure를 삭제하지 않고
새 normalization schema에서 재처리했다. 최종 quality gate는 provider의 필수 occurrence
key 중복과 서로 다른 row 기준일을 확인해 pointer 전환 전에 중단했다.

## live에서 발견해 수정한 공백

- 최초 RED: `home-ai-reference-status --source ...`가 nullable parameter의 PostgreSQL
  type inference 실패로 `INSPECTION_UNAVAILABLE`을 반환했다.
- 최소 GREEN: nullable filter를 `%s::text`로 고정했다.
- 최초 RED: acquisition 생성 전 실패가 `reference_read.acquisition_audit`에서 누락됐다.
- 최소 GREEN: additive migration `0010_pre_acquisition_failure_audit.sql`이 failed
  refresh item을 빈 acquisition ID, 0 counts, safe reason code로 노출한다.
- local inspection wrapper는 runtime role만 사용하고 3초 read-only query를 유지하며,
  fake Docker test에서 runtime password 비노출을 검증했다.

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

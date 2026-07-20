# 생활 인프라 priority reference live preflight

실행일: 2026-07-20

상태: `Partial`

실제 provider full import와 capability activation은 완료되지 않았다. source별 실패는
safe reason code와 count만 기록했고 provider body, API key, DSN은 기록하지 않았다.

## 실행 결과

| 순서 | sourceId | 결과 | 검증 근거 확인 |
|---:|---|---|---|
| 1 | `edu.school-location` | exit `2` | `HOME_AI_DATA_GO_KR_SERVICE_KEY` 미설정, 외부 호출·DB 변경 전 중단 |
| 2 | `edu.academy-registry` | exit `1` | DB role·MinIO·migration preflight 통과 후 첫 page `API_SERVER_ERROR`; acquisition `0`, active datasetVersion 없음 |
| 3 | `place.sbiz-academy` | exit `2` | `HOME_AI_DATA_GO_KR_SERVICE_KEY` 미설정, 외부 호출·DB 변경 전 중단 |
| 4 | `retail.large-store` | exit `2` | license `PENDING`으로 `CONFIGURATION_INVALID`; provider body 요청 전 중단 |
| 5 | `transport.rail-station` | exit `2` | license `PENDING`으로 `CONFIGURATION_INVALID`; provider body 요청 전 중단 |

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

runtime inspection 결과는 학교와 NEIS를 `Partial`로 표시했고 두 source 모두 active
datasetVersion과 dataAsOf가 비어 있었다. NEIS audit는 빈 `acquisitionId`, row count
모두 `0`, `reasonCodes: API_SERVER_ERROR`를 반환했다.

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
AI full: 554 passed, coverage 90.18%
reference docs: Pass
```

## 활성화 판단

- `academy_registry_summary`: readiness `3.0/10 Partial`, 활성화 금지
- `academy_lookup`: readiness `3.0/10 Partial`, 활성화 금지
- `retail_location`: readiness `3.0/10 Partial`, 활성화 금지
- `rail_station_lookup`: readiness `2.0/10 Partial`, 활성화 금지

기존 active pointer를 변경한 source는 없고 raw/publication 삭제나 Docker volume
초기화도 수행하지 않았다.

`api-contract: compatible`

`security-audit: 지적사항 = none`

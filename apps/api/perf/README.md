# Map marker k6 load test

## 목적

`POST /api/v1/map/complexes`와 `POST /api/v1/map/regions`의 현재 성능 기준선을 남긴다.
이 테스트는 읽기 전용 public API 호출만 수행하며 DB reset, seed 변경, Docker volume 삭제를 하지 않는다.

## 사전 조건

- `k6`가 로컬에 설치되어 있어야 한다.
- API가 실행 중이어야 한다.
- local profile 기준 smoke 데이터는 `classpath:db/seed/local`의 `R__sample_home_search_data.sql`를 사용한다.

```bash
docker compose -f <local-compose-yml> up -d postgis api
curl -f http://localhost:8080/actuator/health
```

## Smoke

계약과 기본 응답 shape를 빠르게 확인한다.

```bash
k6 run -e SCENARIO=smoke -e BASE_URL=http://localhost:8080 apps/api/perf/k6/map-marker-baseline.js
```

## Baseline

현재 기준 성능을 측정한다. 첫 기준선은 fail gate보다 p95, max, error rate, RPS 기록을 우선한다.

```bash
k6 run \
  -e SCENARIO=baseline \
  -e BASE_URL=http://localhost:8080 \
  -e TARGET_RPS=1 \
  -e SUMMARY_EXPORT=apps/api/perf/results/map-marker-baseline.json \
  apps/api/perf/k6/map-marker-baseline.js
```

## Stress

병목 후보를 찾기 위한 선택 실행이다. baseline 결과를 먼저 남긴 뒤 사용한다.

```bash
k6 run \
  -e SCENARIO=stress \
  -e BASE_URL=http://localhost:8080 \
  -e TARGET_RPS=5 \
  apps/api/perf/k6/map-marker-baseline.js
```

## 옵션

- `BASE_URL`: API base URL. 기본값은 `http://localhost:8080`.
- `SCENARIO`: `smoke`, `baseline`, `stress`. 기본값은 `baseline`.
- `TARGET_RPS`: baseline/stress 요청 도착률. 기본값은 `1`.
- `COMPLEX_WEIGHT`: `/api/v1/map/complexes` 호출 가중치. 기본값은 `4`.
- `REGION_WEIGHT`: `/api/v1/map/regions` 호출 가중치. 기본값은 `1`.
- `REQUEST_TIMEOUT`: request timeout. 기본값은 `60s`.
- `GRACEFUL_STOP`: ramp 종료 후 진행 중 요청을 기다리는 시간. 기본값은 `1m`.
- `SMOKE_ITERATIONS`: smoke iteration 수. 기본값은 `6`.
- `SMOKE_MAX_DURATION`: smoke 최대 실행 시간. 기본값은 `2m`.
- `P95_THRESHOLD_MS`: 지정하면 endpoint별 `http_req_duration p(95)` threshold를 추가한다.
- `SUMMARY_EXPORT`: 지정하면 k6 summary JSON을 해당 경로에 저장한다.
- `COMPLEX_CASE`: `seed-wide`, `seed-narrow`, `price-filter`, `unit-filter` 중 하나로 고정한다.
- `REGION_CASE`: `si-do`, `si-gun-gu`, `eup-myeon-dong` 중 하나로 고정한다.
- `RAMP_UP`, `STEADY`, `RAMP_DOWN`, `STRESS_STEADY`: stage duration override.

endpoint를 따로 보고 싶으면 한쪽 weight를 `0`으로 둔다.

```bash
k6 run -e SCENARIO=smoke -e COMPLEX_WEIGHT=1 -e REGION_WEIGHT=0 apps/api/perf/k6/map-marker-baseline.js
k6 run -e SCENARIO=smoke -e COMPLEX_WEIGHT=0 -e REGION_WEIGHT=1 apps/api/perf/k6/map-marker-baseline.js
```

Redis cache hit 효과를 분리해서 보고 싶으면 같은 complex 요청을 고정한다.

```bash
k6 run \
  -e SCENARIO=baseline \
  -e BASE_URL=http://localhost:8080 \
  -e TARGET_RPS=1 \
  -e COMPLEX_WEIGHT=1 \
  -e REGION_WEIGHT=0 \
  -e COMPLEX_CASE=seed-wide \
  apps/api/perf/k6/map-marker-baseline.js
```

## 계약 확인

스크립트는 다음을 체크한다.

- status `200`
- JSON array 응답
- complex marker canonical fields: `parcelId`, `complexId`, `name`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`
- region marker canonical fields: `id`, `name`, `lat`, `lng`, `trend`
- audit fields 비노출: `complexPk`, `complex_pk`, `aptSeq`, `apt_seq`, `source`, `sourceKey`, `source_key`

## 결과 기록

실행 결과를 비교할 때는 최소한 다음을 같이 기록한다.

- 실행 명령과 `BASE_URL`
- 데이터 규모 또는 seed 상태
- `http_reqs`, `http_req_failed`, `checks`
- `dropped_iterations`
- `http_req_duration p(95)`
- `complex_marker_duration p(95)`
- `region_marker_duration p(95)`
- `complex_marker_count avg`
- `region_marker_count avg`

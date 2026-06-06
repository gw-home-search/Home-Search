# Map marker performance analysis

## 분석 범위

- 대상: `POST /api/v1/map/complexes`
- 비교: `POST /api/v1/map/regions`
- 환경: local API `http://localhost:8080`, local PostGIS `home_search`
- 데이터 규모:
  - `parcel`: 43,465
  - `complex`: 43,943
  - `trade`: 7,489,152
  - `complex_coordinate_case`: 0
  - `complex_display_coordinate`: 1

## 재현 증상

| case | response count | response size | time |
| --- | ---: | ---: | ---: |
| complex seed-wide | 8,701 | 1,194,825 bytes | 5.47s |
| complex seed-narrow | 314 | 43,957 bytes | 0.25s |
| complex price-filter | 1,967 | 276,146 bytes | 5.81s |
| complex unit-filter | 1 | 133 bytes | 5.70s |
| region si-gun-gu | 1 | 73 bytes | 0.04s |

`unit-filter`가 1건만 반환해도 5초 이상 걸린다. 따라서 단순 JSON 전송량만의 문제가 아니라,
필터 적용 전에 비싼 marker 후보 계산과 trade lookup이 이미 수행되는 구조가 주된 병목이다.

## DB 후보 수

| bounds | bounded parcels | joined complexes |
| --- | ---: | ---: |
| seed-wide | 8,716 | 8,807 |
| seed-narrow | 320 | 326 |

순수 bounds와 `complex` join은 broad bounds에서도 수백 ms 수준이다. 병목은 bounds lookup이 아니다.

## 확인된 병목

[JdbcMapMarkerRepository.java](/Users/gwongwangjae/home-search/apps/api/src/main/java/com/home/infrastructure/persistence/map/JdbcMapMarkerRepository.java:53)의 `complex_base`는 bounds 안의 모든 complex에 대해 두 번의 `LATERAL` trade lookup을 수행한다.

- 최신 거래: `ORDER BY trade.deal_date DESC, trade.id DESC LIMIT 1`
- 최초 거래: `ORDER BY trade.deal_date ASC, trade.id ASC LIMIT 1`

EXPLAIN evidence:

- latest trade LATERAL only, seed-wide: 약 3.1s
- first trade LATERAL only, seed-wide: 약 2.7s
- latest trade LATERAL with `jit=off`: 약 2.0s

계획상 8,807개 complex 각각에 대해 partitioned `trade` index scan이 반복된다. 연도별 partition index는 존재하지만,
반복 횟수가 커져 latency가 누적된다.

## 제외한 가설

- `region` endpoint 병목: 아님. 같은 API runtime에서 0.04s 수준.
- PostGIS bounds 자체 병목: 아님. broad bounds parcel 후보 계산은 약 0.25s, complex join은 약 0.15s.
- set-based `DISTINCT ON`으로 단순 교체: 부적합. 후보 검증 쿼리가 trade partition scan으로 바뀌어 약 51s가 걸렸다.

## 우선순위 제안

1. `first_complex_trade` 조회를 재건축 후보가 있는 parcel에만 수행한다.
   - 현재 local data는 `complex_coordinate_case=0`이라 `first_deal`이 marker 결정에 실질 기여하지 않지만 모든 complex에 대해 조회된다.
   - 기존 redevelopment repository tests로 회귀 보호가 필요하다.
2. `unit`/`age` 필터를 trade lookup 이전 구조 단계로 당긴다.
   - `unit-filter`가 최종 1건이어도 현재는 5.70s이므로 가장 명확한 낭비다.
3. 최신 거래 read model을 검토한다.
   - 예: `complex_latest_trade` 또는 marker 전용 projection.
   - map display 범위라 later-scope ranking/trend와 다르지만, ingest/cancel 시 정합성 테스트가 필요하다.
4. `jit=off`를 map query connection/session 또는 local runtime에서 검토한다.
   - latest lookup 단독 기준 약 3.1s -> 약 2.0s로 개선됐다.
   - 다만 전역 설정 변경 전 다른 쿼리 영향 확인이 필요하다.

## 다음 slice 기준

- TDD first RED: broad bounds + `unitMin/unitMax` 요청이 불필요한 trade lookup을 줄이는지 repository-level performance regression test 또는 query plan smoke로 검증한다.
- public contract impact: 없음. URL, request, response field는 유지한다.
- data invariant impact: 없음. 읽기 경로 최적화만 수행한다.

## 1차 SQL 개선 결과

적용 변경:

- `first_complex_trade` LATERAL 조회를 `REDEVELOPED/HIGH` parcel에서만 실행하도록 제한.
- `trade.deleted_at IS NULL` 최신 조회용 partial covering index 추가:
  - `ix_trade_complex_latest_active_covering`
  - `(complex_id, deal_date DESC, id DESC) INCLUDE (deal_amount, excl_area) WHERE deleted_at IS NULL`

검증 결과:

| case | before | after | change |
| --- | ---: | ---: | ---: |
| complex seed-wide curl | 5,472ms | 4,564ms | 약 17% 개선 |
| complex unit-filter curl | 5,698ms | 4,367ms | 약 23% 개선 |
| k6 `TARGET_RPS=1` complex p95 | 60,000ms timeout | 4,227ms | timeout 제거 |
| k6 `TARGET_RPS=1` fail rate | 10.5% | 0% | 실패 제거 |

남은 병목:

- wide bounds는 여전히 4초대라 지도 UX 목표치에는 부족하다.
- `unit-filter`는 아직 최종 1건임에도 4초대이므로 marker 후보/필터 조기 적용 또는 marker read model이 다음 개선 대상이다.

## 2차 SQL 개선 결과

적용 변경:

- `unit`/`age` 필터가 있는 요청은 marker 후보를 먼저 만든 뒤, 반환 후보에 대해서만 최신 거래를 조회하도록 분리.
- `unitCntSum` 계약을 보존하기 위해 fallback marker는 complex 단위가 아니라 반환 marker의 세대수 합계 기준으로 필터링.
- `unit`/`age` 필터가 없는 wide/price 요청은 기존 trade-first 경로를 유지해 wide no-filter 회귀를 피함.

검증 결과:

| case | before | after | change |
| --- | ---: | ---: | ---: |
| complex seed-wide curl | 5,472ms | 3,630ms | 약 34% 개선 |
| complex seed-narrow curl | 247ms | 247ms | 유지 |
| complex price-filter curl | 5,810ms | 4,201ms | 약 28% 개선 |
| complex unit-filter curl | 5,698ms | 1,444ms | 약 75% 개선 |
| k6 `TARGET_RPS=1` complex p95 | 60,000ms timeout | 3,371ms | timeout 제거, 약 94% 개선 |
| k6 `TARGET_RPS=1` fail rate | 10.5% | 0% | 실패 제거 |

이력서 한 줄 후보:

- k6와 `EXPLAIN` 기반으로 지도 marker API 병목을 분석하고 SQL 실행계획을 개선해 `/api/v1/map/complexes` p95 timeout을 제거했으며, 필터 요청 응답시간을 5.7s에서 1.4s로 약 75% 단축.

남은 병목:

- broad bounds에서 8천 개 이상 marker를 반환하는 요청은 여전히 3초대다.
- Redis 또는 marker read model은 이 지점 이후의 다음 단계로 보는 것이 타당하다.

## 3차 Redis cache 결과

적용 변경:

- `/api/v1/map/complexes` read path에 Redis read-through cache decorator 추가.
- cache key는 bounds와 `pyeong`, `priceEok`, `age`, `unit` 전체 필터를 포함한다.
- 기본값은 `home.map.marker-cache.enabled=false`이며, 활성화 시 `home.map.marker-cache.ttl`로 stale window를 제한한다.
- 운영 후보 TTL 기본값은 `5m`로 둔다. `60s`는 실험 반복에는 안전하지만 실제 지도 이동/재조회 cache hit window가 짧다.
- Redis read/write 장애는 API 실패로 전파하지 않고 JDBC 조회로 fallback한다.
- Redis cache 결과는 `home.search.map.marker.cache.requests` metric으로 기록한다.
  - tags: `endpoint=complexes`, `result=hit|miss|fallback`
- k6에 `COMPLEX_CASE`, `REGION_CASE` 옵션을 추가해 cache hit 효과를 같은 요청으로 분리 측정할 수 있게 했다.

검증 결과:

| case | p95 | reqs | fail rate | checks |
| --- | ---: | ---: | ---: | ---: |
| Redis off, k6 `TARGET_RPS=1`, `COMPLEX_CASE=seed-wide`, 10s | 4,062.09ms | 11 | 0% | 100% |
| Redis cold miss, Redis `FLUSHALL` 후 k6 smoke 1회 | 3,995.38ms | 1 | 0% | 100% |
| Redis warm hit, k6 `TARGET_RPS=1`, `COMPLEX_CASE=seed-wide`, 10s | 52.75ms | 10 | 0% | 100% |

측정 조건:

- API: local API `http://localhost:18080`
- Redis: temporary `redis:7.4-alpine`, `localhost:16379`
- data: local `home_search`, complex marker 평균 `8,701`건
- warm/off: k6 10 requests, `COMPLEX_WEIGHT=1`, `REGION_WEIGHT=0`, `RAMP_UP=1s`, `STEADY=10s`, `RAMP_DOWN=1s`
- cold: Redis `FLUSHALL` 후 k6 smoke 1 request

성능 해석:

- Redis cold miss는 DB 조회가 그대로 수행되어 off와 같은 비용 구간에 있다.
- Redis warm hit는 반복 broad-bounds 요청 p95를 `4,062.09ms`에서 `52.75ms`로 약 98.7% 단축했다.

주의:

- cache miss는 여전히 DB 조회 비용을 그대로 가진다.
- TTL `5m`은 지도 반복 이동/재조회에는 충분한 cache hit window를 주지만, 데이터 변경 직후 stale marker 허용 시간을 의미한다.
- 데이터 변경 직후 stale marker가 노출될 수 있으므로 production 적용 시 TTL과 ingest 후 invalidation 정책을 별도로 결정해야 한다.

이력서 한 줄 후보:

- k6 고정 케이스와 Redis read-through cache를 도입해 지도 marker API의 반복 broad-bounds 요청 p95를 4.06s에서 52.75ms로 약 98.7% 단축하고, Redis 장애 시 DB fallback과 cache hit/miss/fallback metric으로 API 가용성과 관측 가능성을 보강.

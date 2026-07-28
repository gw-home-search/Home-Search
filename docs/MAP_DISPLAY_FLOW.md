# Map Display Flow


## Goal

Display real-estate trade data on a Kakao map using stable public APIs.

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target frontend:

- `/Users/gwongwangjae/home-search/apps/web`

## Current Source Flow

Current central file:

- `src/App.jsx`

Current behavior:

1. Kakao Map is created by `KakaoMap.jsx`.
2. `onCreate` and `onIdle` call `fetchMarkers`.
3. `fetchMarkers` reads map center, level, and bounds.
4. Map level chooses endpoint:
   - `level <= 4`: `/api/v1/map/complexes`
   - otherwise: `/api/v1/map/regions`
5. The response is normalized into marker coordinates.
6. Complex markers or region markers are rendered.

## Project Flow

```text
Kakao map idle
  -> read bounds and level
  -> abort the previous in-flight marker request
  -> if detailed level, call /api/v1/map/complexes
  -> else call /api/v1/map/regions
  -> render markers
  -> complex marker click
  -> open detail drawer
  -> call /api/v1/detail/{parcelId}?complexId={complexId} when marker has complexId
  -> call /api/v1/trade/{parcelId}?complexId={complexId} when marker has complexId
```

## Level Rules

Keep current source behavior for compatibility:

- `level <= 4`: show complex markers.
- `level >= 10`: request `si-do`.
- `level >= 7`: request `si-gun-gu`.
- `level >= 4`: request `eup-myeon-dong`.

The level thresholds can be tuned later, but project baseline should preserve them
until map display is stable.

## Complex Marker Contract

Complex markers need:

- `parcelId`
- `complexId` when the marker is scoped to a specific complex
- `lat`
- `lng`
- `latestDealAmount`
- `unitCntSum`

Marker display:

- Price label from `latestDealAmount`.
- Name label from the marker response `name`, which is the source complex name
  without the locality-combined display projection.
- Unit label from `unitCntSum`. Markers without a household-count sum are excluded
  by the backend, so every returned marker has a non-null `unitCntSum`. Complexes
  with no household-count metadata stay visible in the metadata admin surface
  instead of the public map.
- Click opens detail drawer for `parcelId` and optional `complexId`.

## Parcel And Complex Policy

`/api/v1/map/complexes` keeps the same URL but can return complex-scoped
markers when the backend has enough coordinate confidence:

- Normal parcels use one representative marker.
- Concurrent same-PNU complexes with resolved building-footprint coordinates can
  return one marker per complex.
- Stored `complex_display_coordinate` rows from `BUILDING_FOOTPRINT` can also
  split same-PNU complexes when confidence is high enough and the parcel is not
  a redevelopment candidate.
- Split markers must still use coordinates inside the requested map bounds;
  an in-bounds parcel does not justify returning an out-of-bounds complex marker.
- Same-PNU complexes without trusted per-complex coordinates stay as one parcel
  fallback marker instead of guessed complex markers.
- Coordinate-pending parcels can keep matched trade data in storage, but they
  are not marker-safe until a display coordinate is available.
- Redeveloped parcels return the current-generation complex marker.
- Ambiguous or unresolved same-PNU cases fall back to one representative marker.
- Marker-pending or fallback cases are sent to the admin coordinate queue with
  one of the minimal reasons documented in `DATA_STORAGE.md`; the map does not
  guess missing complex coordinates.
- Marker click uses `parcelId` plus optional `complexId`.
- The detail drawer shows the selected complex when `complexId` is present;
  otherwise it shows the parcel representative complex.
- The trade list is scoped to the selected `complexId` when present; otherwise
  it shows normalized trades for all complexes under the parcel.
- Search results remain complex-level results, so multiple search results may
  point to the same `parcelId` and should pass their `complexId` into the
  detail/trade flow.

## Backend Query Boundary

`/api/v1/map/complexes` reads only the immutable generation referenced by
`map_marker_active_generation`. Request-time SQL must not aggregate `trade`,
`complex`, `parcel`, or building-profile source tables. It applies bounds,
price, area, household-count, building-age, and ratio filters to
`map_complex_marker_projection`; `/api/v1/map/regions` applies bounds and level
filters to `map_region_marker_projection`.

The frontend aborts obsolete requests on viewport change or unmount while still
keeping its request-sequence guard against stale responses. The API rejects
complex bbox spans above `1.0` latitude degrees or `1.5` longitude degrees and
region bbox spans above `10.0`/`15.0` degrees. Public ingress separately applies
a per-IP `10r/s`, burst `30` limit to only the two map bbox endpoints; this
protects the query path without adding a rate-limit library to application code.

The projection builder owns `map-marker-projection-build.sql`. After RTMS ingest
and region household synchronization, Batch:

1. creates a `BUILDING` generation with a source WAL/raw/trade watermark;
2. builds complex and region rows without changing the active pointer;
3. validates row counts and a deterministic SHA-256 marker hash;
4. changes the singleton pointer only after `VALIDATED`;
5. retains the immediately previous `RETIRED` generation for rollback.

A build or activation failure records `FAILED`, marks the Batch job failed for
deployment gating, and leaves the prior active generation serving traffic. The Redis marker cache key
contains the active generation id, so pointer activation invalidates stale
entries without `FLUSHALL` or a key scan. Redis failure still falls back to the
same projection query, never to source-table aggregation.

The two former request-time SQL resources remain as parity references for the
projection persistence fixtures:

- `complex-marker-trade-first.sql` is used when unit-count and building-age shape filters are absent.
- `complex-marker-shape-filter.sql` applies unit-count and building-age filters before resolving the latest marker trade.

They preserve the marker identity, source `name`, latest-price,
current-generation, and household-count oracle used by the generation parity
tests. Bounds and filter values remain named JDBC parameters. V8's
`hs_normalize_complex_search_name` remains the canonical complex search-name
normalizer, with Java-to-database golden parity tests. Applied V8 migration SQL
is not modified.

Do not require:

- Ranking tables.
- Trend tables.
- 30-day aggregate tables.
- Mail or favorite state.

## Frontend Error Behavior

On marker API failure:

- Clear current markers.
- Keep map usable.
- Do not navigate away from the map.
- Show a small non-blocking error state in the redesigned UI.

## Nearby Facility Overlay Flow

`주변시설`은 단지 선택과 무관한 viewport overlay다. 거리 측정·지적편집도·
지도 종류와 동시에 사용할 수 있고, 거리뷰가 열린 동안만 request와 overlay를
정리한다. `시설` 버튼은 overlay toggle이 아니라 category picker 설정 버튼이다.

```text
Kakao runtime ready -> category default []
  -> 0개: API 0회, POI·상태 안내 없음
  -> 1..3개 선택: picker 열림 여부와 무관하게 자동 조회
  -> level > MAX_COMPLEX_MARKER_LEVEL(4): API 호출 없이 확대 안내와 POI overlay 정리
  -> level <= MAX_COMPLEX_MARKER_LEVEL(4): map idle 이후 400ms debounce
  -> 선택 category만 public API를 category별로 1회씩 병렬 조회
  -> browser 5분 LRU cache hit category는 network 0회
  -> backend 1시간 Redis hit category는 Kakao 0회
  -> miss category마다 Kakao category search 최대 1회
  -> actual bounds 안에서 category당 최대 5개, 전체 최대 15개 symbol tile overlay
  -> category 추가는 새 category만 조회하고 해제는 해당 overlay만 정리
  -> marker 선택은 compact 장소 정보 bar를 표시
  -> 빈 지도 클릭은 장소 선택만 해제하고 category는 유지
  -> 거리뷰 진입·unmount는 request·overlay·listener 정리, 거리뷰 종료 후 자동 복원
```

이동 중 이전 request는 abort하고 sequence가 늦은 응답을 무시한다. 선택은 0개까지
해제할 수 있고 4번째 category 선택은 안내 후 차단한다. 지도 기본 8종은
`대형마트`, `편의점`, `음식점`, `어린이집·유치원`, `학교`, `학원`, `지하철역`, `병원`
순서다. loading, empty, partial/provider failure와 retry는 compact 정보 bar에서
처리하며 기본 지도는 계속 사용할 수 있다. property marker는 숨기지 않고 기존
선택·클릭 동작을 유지한다.

## News Rail Flow

```text
/insights/news route
  -> keep MapApp and current map viewport mounted
  -> normalize scope/root SIDO/category URL state
  -> read one published PostgreSQL snapshot page
  -> render FRESH, STALE, UNAVAILABLE, empty, or isolated error state
  -> cursor append without replacing existing rows on failure
  -> external article opens in a new tab
```

Category or region changes abort the previous request and increment a sequence
guard. Re-entry and `visibilitychange=visible` refresh only after a five-minute
TTL or KST date change; there is no polling. Opening and closing complex detail
does not recreate the map. Complex news uses a separate top-five snapshot read,
so its failure does not affect detail, trade, or trend state.

## Acceptance Criteria

- Moving or zooming the map triggers marker refresh.
- Wide zoom shows region markers.
- Detailed zoom shows complex markers.
- Complex marker click opens detail and trade data.
- Map display works with only project data tables.
- Cache miss and Redis failure read the active projection without source-table aggregation.
- A failed generation does not replace the active marker hash or response.
- The release performance gate requires at least three cold and three warm
  samples, p95 below 2 seconds, error rate below 1%, marker count/hash parity,
  and twice the committed expected peak request rate.
- Nearby facility overlay uses the viewport endpoint, stores no POI table, and does
  not change the ordinary marker or complex nearby-place endpoint.

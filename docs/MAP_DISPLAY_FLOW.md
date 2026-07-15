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

`/api/v1/map/complexes` should only do enough work for map display:

- Filter parcels by PostGIS bounds.
- Join complexes under each parcel.
- Compute or select latest trade amount.
- Apply simple filters for unit count, price, area, and age.

The frontend aborts obsolete requests on viewport change or unmount while still
keeping its request-sequence guard against stale responses. The API rejects
complex bbox spans above `1.0` latitude degrees or `1.5` longitude degrees and
region bbox spans above `10.0`/`15.0` degrees. Public ingress separately applies
a per-IP `10r/s`, burst `30` limit to only the two map bbox endpoints; this
protects the query path without adding a rate-limit library to application code.

The property-data map adapter owns two complete, feature-local SQL resources:

- `complex-marker-trade-first.sql` is used when unit-count and building-age shape filters are absent.
- `complex-marker-shape-filter.sql` applies unit-count and building-age filters before resolving the latest marker trade.

`ComplexMarkerSql` loads both resources once when the JDBC repository is created. The repository only selects the variant,
binds named parameters, and maps rows. Bounds and filter values are never interpolated into SQL text. Both variants keep the
same marker identity, source `name`, latest-price, current-generation, and household-count policies; persistence fixtures
verify their neutral-filter parity. V8's `hs_normalize_complex_search_name` remains the canonical complex search-name
normalizer, with Java-to-database golden parity tests. Applied V8 migration SQL is not modified.

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

## Nearby Commerce Tool Flow

`상권` is an exclusive map work mode alongside Roadview and distance measurement;
the cadastral overlay remains independent.

```text
detail-complexId ?? selected-marker-complexId
  -> GET /api/v1/complex/{complexId}/nearby-places once for six categories
  -> default CAFE category
  -> keep selected complex marker, hide non-selected property markers
  -> render at most five selected-category POI overlays
  -> marker click selects and scrolls the matching list row
  -> row click selects the marker and centers the map
  -> category switch reuses the same response without another API request
  -> complex switch, mode exit, or unmount aborts/cleans overlays and listeners
```

Parcel fallback markers with `complexId == null` do not enable the tool until
the detail response provides a canonical complex id. Loading, empty, provider
failure and retry stay inside the nearby-place panel; the base map remains
usable. Counts are always labeled as Kakao place-search counts.

## Acceptance Criteria

- Moving or zooming the map triggers marker refresh.
- Wide zoom shows region markers.
- Detailed zoom shows complex markers.
- Complex marker click opens detail and trade data.
- Map display works with only project data tables.
- Nearby commerce mode uses the canonical complex coordinate and does not add a
  POI table or change the ordinary marker endpoint.

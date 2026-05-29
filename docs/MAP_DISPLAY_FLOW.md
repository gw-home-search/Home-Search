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
  -> if detailed level, call /api/v1/map/complexes
  -> else call /api/v1/map/regions
  -> render markers
  -> complex marker click
  -> open detail drawer
  -> call /api/v1/detail/{parcelId}
  -> call /api/v1/trade/{parcelId}
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
- `lat`
- `lng`
- `latestDealAmount`
- `unitCntSum`

Marker display:

- Price label from `latestDealAmount`.
- Unit label from `unitCntSum`.
- Click opens detail drawer for `parcelId`.

## Parcel And Complex Policy

`/api/v1/map/complexes` returns one marker per parcel, not one marker per
complex. When a parcel contains multiple apartment complexes:

- `unitCntSum` is the sum of household counts for all complexes under the
  parcel.
- `latestDealAmount` is selected from the newest normalized `trade` under any
  complex in the parcel.
- Price and area filters use the same trade row selected for the parcel marker's
  latest trade policy. Unit and age filters use parcel-level aggregate or
  representative values.
- Marker click still uses `parcelId`.
- The detail drawer shows a representative complex for the parcel.
- The trade list shows normalized trades for all complexes under the parcel.
- Search results remain complex-level results, so multiple search results may
  point to the same `parcelId`.

## Backend Query Boundary

`/api/v1/map/complexes` should only do enough work for map display:

- Filter parcels by PostGIS bounds.
- Join complexes under each parcel.
- Compute or select latest trade amount.
- Apply simple filters for unit count, price, area, and age.

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

## Acceptance Criteria

- Moving or zooming the map triggers marker refresh.
- Wide zoom shows region markers.
- Detailed zoom shows complex markers.
- Complex marker click opens detail and trade data.
- Map display works with only project data tables.

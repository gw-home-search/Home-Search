# API Contract


## Purpose

This document is the Home Search public API contract.
It fixes the HTTP URLs, request shapes, response shapes, units, error behavior,
and compatibility rules that the target backend and frontend must follow.

Use this document as the baseline for:

- Backend controllers, DTOs, repository projections, and controller tests.
- Frontend API clients, marker adapters, detail panels, and search behavior.
- Migration decisions when source backend fields conflict with source frontend
  expectations.

Home Search keeps the main API URLs stable so the frontend can display the map without
new route names. Internal implementation may change to support safer storage,
but those changes must not leak into the public API unless this contract is
updated first.

## Current Work Package

This work package clarifies the API contract only. It does not implement
backend code, frontend adapters, OpenAPI YAML, or new tests.

The next implementation batches should use this document to build:

- Backend request/response DTOs and controller tests.
- Repository projections that emit the canonical response fields.
- Frontend adapters that normalize temporary source-field variants.
- Integration tests for the map, search, region, detail, and trade flows.

## Fixed Paths

Source backend:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target repository:

- `/Users/gwongwangjae/home-search`

## Contract Authority

The source backend and source frontend are read-only references. If source code
and this target contract disagree, the Home Search implementation should follow
this document and keep the source mismatch as a migration note.

Do not widen Home Search to include rankings, favorites, alarms, mail batches,
recommendations, auth flows, or heavy analytics unless the migration scope is
explicitly changed.

## Common Conventions

- Canonical URLs include a leading slash, for example `/api/v1/map/regions`.
- Requests and responses use JSON.
- Coordinates use WGS84 / EPSG:4326.
- `lat` and `latitude` mean latitude. `lng` and `longitude` mean longitude.
- Dates use `YYYY-MM-DD`.
- `dealAmount` and `latestDealAmount` are integer values in 10,000 KRW units.
- `priceEokMin` and `priceEokMax` are filter inputs in eok units. The backend
  converts them to 10,000 KRW units before comparing with stored trade amounts.
- Nullable filter fields mean "do not apply this filter".
- Empty list results should return `200` with `[]` whenever the request itself
  is valid.
- Optional response fields may be `null` or omitted.

## Compatibility Policy

Compatible changes:

- Adding an optional response field.
- Returning `null` or omitting an optional field documented as optional.
- Accepting legacy frontend field variants inside a temporary adapter.

Breaking changes:

- Removing a documented response field.
- Renaming a documented field.
- Changing a documented field type or unit.
- Requiring a field that was previously optional.
- Changing a public URL or HTTP method.

Target Home Search should expose canonical fields. Legacy variants from the source code
may be accepted temporarily by the frontend adapter, but backend responses
should converge on the canonical fields below.

## Error Policy

Home Search uses Spring `ProblemDetail` style error bodies.

Minimum error fields:

- `type`
- `title`
- `status`
- `detail`
- `exception`
- `timestamp`: ISO-8601 offset date-time in UTC.

Status rules:

- Invalid request body, invalid query parameter, or invalid enum: `400`.
- Missing region, parcel, complex, detail, or trade parent resource: `404`.
- Unexpected server error or external integration failure: `500`.

Example:

```json
{
  "type": "/docs/index.html#error-code-list",
  "title": "C401",
  "status": 400,
  "detail": "Invalid parameter format.",
  "exception": "MapApiException",
  "timestamp": "2026-05-18T10:30:00Z"
}
```

## Public APIs

### POST `/api/v1/map/regions`

Purpose:

- Return region-level markers inside the current map bounds.
- Used when the Kakao map is zoomed out.

Source controller:

- `src/main/java/com/home/infrastructure/web/map/MapController.java`

Frontend source consumer:

- `src/App.jsx`
- `src/components/map/RegionMarkers.jsx`

Request:

```json
{
  "swLat": 37.45,
  "swLng": 126.85,
  "neLat": 37.70,
  "neLng": 127.20,
  "region": "si-gun-gu"
}
```

Request fields:

- `swLat`: required number.
- `swLng`: required number.
- `neLat`: required number.
- `neLng`: required number.
- `region`: required string.

Allowed `region` values:

- `si-do`
- `si-gun-gu`
- `eup-myeon-dong`

Response:

```json
[
  {
    "id": 1,
    "name": "Seoul",
    "lat": 37.5663,
    "lng": 126.9780,
    "trend": null
  }
]
```

Response fields:

- `id`: region id.
- `name`: display name.
- `lat`: marker latitude.
- `lng`: marker longitude.
- `trend`: optional regional trend value. Home Search may return `null` or omit it.

Status:

- `200`: successful lookup. May be `[]`.
- `400`: invalid bounds or unsupported `region`.
- `500`: unexpected server error.

Migration notes:

- Regional trend calculation is not required for map display.
- Source repository aliases may not match the target field names exactly.
  Target Home Search should expose `name`, `lat`, and `lng`.
- `unitCntSum` is not required for Home Search region markers. Frontend code must not
  require it to render region markers.

### POST `/api/v1/map/complexes`

Purpose:

- Return apartment complex markers inside the current map bounds.
- Used when the Kakao map is zoomed in enough to show detailed markers.

Source controller:

- `src/main/java/com/home/infrastructure/web/map/MapController.java`

Frontend source consumer:

- `src/App.jsx`
- `src/components/map/ComplexMarkers.jsx`

Request:

```json
{
  "swLat": 37.45,
  "swLng": 126.85,
  "neLat": 37.70,
  "neLng": 127.20,
  "pyeongMin": null,
  "pyeongMax": null,
  "priceEokMin": null,
  "priceEokMax": null,
  "ageMin": null,
  "ageMax": null,
  "unitMin": null,
  "unitMax": null
}
```

Request fields:

- `swLat`: required number.
- `swLng`: required number.
- `neLat`: required number.
- `neLng`: required number.
- `pyeongMin`: optional integer pyeong lower bound.
- `pyeongMax`: optional integer pyeong upper bound.
- `priceEokMin`: optional number eok lower bound.
- `priceEokMax`: optional number eok upper bound.
- `ageMin`: optional integer building age lower bound.
- `ageMax`: optional integer building age upper bound.
- `unitMin`: optional integer household count lower bound.
- `unitMax`: optional integer household count upper bound.

Response:

```json
[
  {
    "parcelId": 1001,
    "complexId": 501,
    "name": "Sample Apartment",
    "lat": 37.5123,
    "lng": 127.0456,
    "latestDealAmount": 125000,
    "unitCntSum": 740
  }
]
```

Response fields:

- `parcelId`: parcel id used by detail and trade APIs.
- `complexId`: optional complex id used to scope detail and trade APIs. This is
  present for complex-level markers and may be `null` for parcel representative
  fallback markers.
- `name`: optional apartment complex name for marker display.
- `lat`: marker latitude.
- `lng`: marker longitude.
- `latestDealAmount`: optional latest trade amount in 10,000 KRW units.
- `unitCntSum`: household count represented by this marker.

Status:

- `200`: successful lookup. May be `[]`.
- `400`: invalid bounds or invalid filter type/range.
- `500`: unexpected server error.

Migration notes:

- Source code has mixed naming around `parcelId`, `id`, `latitude`, `lat`,
  `longitude`, and `lng`.
- Target Home Search backend should return the canonical fields above.
- Frontend adapters may temporarily accept `id`, `latitude`, and `longitude`
  while source code is being migrated, but new target code should prefer
  `parcelId`, `lat`, and `lng`.
- Normal parcels still return one representative marker. Concurrent same-PNU
  complexes with resolved high-confidence building coordinates may return one
  marker per complex. Redeveloped parcels return the current-generation complex
  marker. Unresolved or ambiguous cases fall back to one representative marker.
- `unitCntSum` is the household count for a complex-level marker and the sum of
  household counts for a representative fallback marker.
- `latestDealAmount` is the newest normalized `trade` amount for the marker's
  `complexId` when present, otherwise the newest trade under the parcel.
- Price, area, unit, and age filters are applied to the marker row actually
  returned by this endpoint.
- Map marker APIs must not require ranking, trend, favorite, alarm, mail, or
  auth state.

### GET `/api/v1/search/complexes?q=`

Purpose:

- Search apartment complexes by user-entered text.
- Used by the left sidebar search flow.

Source controller:

- `src/main/java/com/home/infrastructure/web/search/SearchController.java`

Frontend source consumers:

- `src/components/sidebar/LeftSidebar.jsx`
- `src/store/uiSlice.js`

Request:

- Query parameter `q`: required string, trim before search.

Response:

```json
[
  {
    "complexId": 501,
    "complexName": "Sample Apartment",
    "parcelId": 1001,
    "latitude": 37.5123,
    "longitude": 127.0456,
    "address": "Sample address"
  }
]
```

Response fields:

- `complexId`
- `complexName`
- `parcelId`
- `latitude`
- `longitude`
- `address`

Status:

- `200`: successful lookup. Empty or no-match searches return `[]`.
- `400`: invalid query parameter type.
- `500`: unexpected server error.

Migration notes:

- This endpoint keeps `latitude` and `longitude` for source frontend
  compatibility. Do not rename them to `lat` and `lng` in Home Search unless the
  frontend adapter is updated in the same batch.
- Search results are complex-level results. Multiple results may have the same
  `parcelId` when several complexes share one parcel.
- Search may use preserved `complex_name_alias` rows and normalized alias text
  internally, but those audit/search helper fields are not exposed in this
  response.

### GET `/api/v1/region`

Purpose:

- Load root regions for region navigation.

Source controller:

- `src/main/java/com/home/infrastructure/web/region/RegionController.java`

Frontend source consumer:

- `src/components/sidebar/region/RegionNavSidebar.jsx`

Response:

```json
[
  {
    "id": 1,
    "name": "Seoul"
  }
]
```

Response fields:

- `id`: root region id.
- `name`: display name.

Status:

- `200`: successful lookup. May be `[]`.
- `500`: unexpected server error.

### GET `/api/v1/region/{regionId}`

Purpose:

- Load region detail, child regions, and center coordinates.
- Used for region navigation and map recentering.

Source controller:

- `src/main/java/com/home/infrastructure/web/region/RegionController.java`

Frontend source consumer:

- `src/components/sidebar/region/RegionNavSidebar.jsx`

Response:

```json
{
  "id": 1,
  "name": "Seoul",
  "latitude": 37.5663,
  "longitude": 126.9780,
  "children": [
    {
      "id": 11,
      "name": "Gangnam-gu"
    }
  ]
}
```

Response fields:

- `id`: region id.
- `name`: display name.
- `latitude`: center latitude.
- `longitude`: center longitude.
- `children`: child region list.

Child response fields:

- `id`
- `name`

Status:

- `200`: successful lookup.
- `404`: region id does not exist.
- `500`: unexpected server error.

### GET `/api/v1/detail/{parcelId}`

Purpose:

- Return parcel and complex details for the selected marker.

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Frontend source consumer:

- `src/components/sidebar/detail/DetailSidebar.jsx`

Request:

- Optional query parameter `complexId`: selected complex id. When omitted, the
  backend returns the deterministic representative complex for the parcel.

Response:

```json
{
  "parcelId": 1001,
  "complexId": 501,
  "latitude": 37.5123,
  "longitude": 127.0456,
  "address": "Sample address",
  "tradeName": "Sample trade name",
  "name": "Sample complex name",
  "dongCnt": 8,
  "unitCnt": 740,
  "platArea": 12345.67,
  "archArea": 2345.67,
  "totArea": 98765.43,
  "bcRat": 22.5,
  "vlRat": 199.8,
  "useDate": "2015-03-20"
}
```

Response fields:

- `parcelId`
- `complexId`: selected or representative complex id.
- `latitude`
- `longitude`
- `address`
- `tradeName`
- `name`
- `dongCnt`
- `unitCnt`
- `platArea`
- `archArea`
- `totArea`
- `bcRat`
- `vlRat`
- `useDate`

Status:

- `200`: successful lookup.
- `404`: parcel or representative complex does not exist.
- `500`: unexpected server error.

Migration notes:

- Source DTO omits `null` values. Target Home Search may omit nullable fields rather
  than returning explicit `null`.
- If `complexId` is provided, this endpoint returns that complex only when it
  belongs to the requested `parcelId`; otherwise it returns `404`. If omitted,
  this endpoint returns one representative complex detail for the selected
  `parcelId`.

### GET `/api/v1/trade/{parcelId}`

Purpose:

- Return trade list for the selected parcel or selected complex.

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Frontend source consumer:

- `src/components/sidebar/detail/TradeSidebar.jsx`

Request:

- Optional query parameter `complexId`: selected complex id. When omitted, the
  backend returns trades for all complexes under the parcel.

Response:

```json
{
  "parcelId": 1001,
  "complexId": 501,
  "trades": [
    {
      "tradeId": 9001,
      "dealDate": "2025-12-01",
      "exclArea": 84.93,
      "dealAmount": 125000,
      "aptDong": "101",
      "floor": 12
    }
  ]
}
```

Response fields:

- `parcelId`
- `complexId`: selected complex id when scoped; otherwise nullable.
- `trades`

Trade item fields:

- `tradeId`: trade id.
- `dealDate`: `YYYY-MM-DD`.
- `exclArea`: exclusive area in square meters.
- `dealAmount`: trade amount in 10,000 KRW units.
- `aptDong`: optional apartment building name or number.
- `floor`: optional floor.

Status:

- `200`: successful lookup. If the parcel exists but has no trades, return an
  empty `trades` list.
- `404`: parcel or complex parent path does not exist.
- `500`: unexpected server error.

Migration notes:

- The Home Search query path should work through `complex_id`.
- If `complexId` is provided, the response includes normalized trades only for
  that complex after verifying it belongs to the requested `parcelId`. If
  omitted, the response includes normalized trades for all complexes under the
  requested `parcelId`, ordered newest first.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key` for audit,
  matching, and deduplication, but do not expose them in this public response.
- Default ordering should be newest first: `dealDate` descending, then
  `tradeId` descending when dates are equal.

## Admin APIs

### GET `/api/v1/admin/coordinates/pending`

Purpose:

- Return coordinate-pending complexes that have stored identity/trade data but
  no marker-safe parcel coordinates.
- This endpoint is an operational correction surface and is disabled unless
  coordinate override admin is explicitly enabled.
- Requires the `X-Admin-Access-Code` request header when enabled.

Query parameters:

- `limit`: optional page size. Defaults to `50`.
- `offset`: optional zero-based row offset for paging. Defaults to `0`.
- Invalid `limit` values below `1` or invalid `offset` values below `0`
  return `400` with the standard `ProblemDetail` body.

Errors:

- Missing or invalid `X-Admin-Access-Code` returns `401` with the standard
  `ProblemDetail` body.

Response:

```json
[
  {
    "parcelId": 1001,
    "complexId": 501,
    "pnu": "1168010300101400001",
    "aptSeq": "APT-501",
    "aptName": "Pending Apartment",
    "address": "Pending address",
    "reason": "PNU_COORDINATE_MISSING",
    "tradeCount": 3,
    "createdAt": "2026-06-03T00:00:00Z"
  }
]
```

`reason` is an operational correction code for the admin surface:

- `PNU_COORDINATE_MISSING`: the parcel/PNU has no marker-safe coordinate.
- `SAME_PNU_MULTI_COMPLEX`: one PNU has multiple complexes and no trusted
  building-footprint display coordinates.
- `COMPLEX_DISPLAY_COORDINATE_MISSING`: another same-PNU complex has a trusted
  building-footprint display coordinate, but this complex still needs one.

### GET `/api/v1/admin/coordinates/pending/summary`

Purpose:

- Return whole-query coordinate-pending counts for the admin correction surface.
- This endpoint is an operational summary for the paged pending list. It is
  disabled unless coordinate override admin is explicitly enabled.
- Requires the `X-Admin-Access-Code` request header when enabled.

Request:

- No request body.
- No query parameters.

Errors:

- Missing or invalid `X-Admin-Access-Code` returns `401` with the standard
  `ProblemDetail` body.

Response:

```json
{
  "totalCount": 1429,
  "reasonCounts": {
    "PNU_COORDINATE_MISSING": 579,
    "SAME_PNU_MULTI_COMPLEX": 850,
    "COMPLEX_DISPLAY_COORDINATE_MISSING": 0
  }
}
```

Response fields:

- `totalCount`: total number of coordinate-pending complexes in the whole
  pending query, not the current page.
- `reasonCounts`: object keyed by the documented coordinate pending `reason`
  values. Missing data should be represented as `0`, not by omitting a reason
  key.

Status:

- `200`: successful summary lookup. If no coordinate-pending complexes exist,
  return `totalCount: 0` and `0` for every documented reason key.
- `401`: missing or invalid admin access code.
- `500`: unexpected server error.

### PUT `/api/v1/admin/coordinates/{pnu}/override`

Purpose:

- Approve a manual coordinate override for an identity-safe PNU.
- The override updates the existing `parcel` coordinates and does not create a
  new parcel, complex, or trade row.
- Requires the `X-Admin-Access-Code` request header when enabled.

Errors:

- Missing or invalid `X-Admin-Access-Code` returns `401` with the standard
  `ProblemDetail` body.

Request:

```json
{
  "latitude": 37.5123,
  "longitude": 127.0456,
  "reason": "operator verified missing coordinate",
  "approvedBy": "local-operator"
}
```

Response:

```json
{
  "pnu": "1168010300101400001",
  "latitude": 37.5123,
  "longitude": 127.0456,
  "parcelUpdated": true
}
```

Status:

- `200`: override approved.
- `400`: invalid PNU or coordinate range.
- `500`: unexpected server error.

Migration notes:

- This is not part of the public map/search/detail/trade user flow.
- Existing `/api/v1/map/complexes`, `/api/v1/detail/{parcelId}`, and
  `/api/v1/trade/{parcelId}` response shapes do not change.

## later-scope APIs

Keep these out of the current critical path:

- `/api/v1/rankings/top-price-30d`
- `/api/v1/rankings/top-volume-30d`
- `/api/v1/favorites`
- `/api/v1/users/me`
- `/auth/access`
- `/admin/batch/trade-alarm/run`

They should not be deleted from source knowledge, but they must not block
collection, storage, and map display.

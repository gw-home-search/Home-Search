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

Do not widen property-data to include rankings, favorites, alarms, mail batches,
recommendations, auth flows, or heavy analytics. The additive authenticated
favorite APIs documented below are owned by user-service and do not change the
unauthenticated property-data map/search/detail/trade contract.

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

### Approved breaking change — trade list pagination (2026-06)

`GET /api/v1/trade/{parcelId}` and `GET /api/v1/complex/{complexId}/trades`
moved from a bare `trades` array to a page envelope: the trade array is now
`content`, and `page`, `size`, `totalElements`, and `totalPages` were added.
Both endpoints accept optional `page` (default `0`) and `size` (default `25`,
server-capped at `100`) query parameters. `dealAmount` stays in 10,000 KRW
units and default ordering is preserved within each page. This reshape was
explicitly approved because the only consumer (the Home Search web client) is
updated in the same change.

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
    "trend": null,
    "unitCntSum": 1200
  }
]
```

Response fields:

- `id`: region id.
- `name`: display name.
- `lat`: marker latitude.
- `lng`: marker longitude.
- `trend`: optional regional trend value. Home Search may return `null` or omit it.
- `unitCntSum`: household count represented by this region marker. Region markers
  whose subtree has no household-count metadata are excluded from the response, so
  a returned region marker always carries a non-null `unitCntSum`.

Status:

- `200`: successful lookup. May be `[]`.
- `400`: invalid bounds or unsupported `region`.
- `500`: unexpected server error.

Migration notes:

- Regional trend calculation is not required for map display.
- Source repository aliases may not match the target field names exactly.
  Target Home Search should expose `name`, `lat`, and `lng`.
- A region marker is returned only when its subtree has a household-count sum;
  regions with no household-count metadata are omitted rather than returned with a
  `null` `unitCntSum`. The backend must not turn missing metadata into `0`.

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
- `unitCntSum`: household count represented by this marker. Markers with no
  household-count metadata are excluded from the response, so a returned marker
  always carries a non-null `unitCntSum`.

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
  household counts for a representative fallback marker. A marker with no
  household-count metadata (for example a not-yet-enriched complex or a parcel
  whose complexes all lack `unitCnt`) is omitted from the response rather than
  returned with a `null` `unitCntSum`. The backend must not turn missing metadata
  into `0`. Such complexes remain visible in the metadata admin surface.
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

- Query parameter `q`: required string, trim before search; maximum 100 Unicode
  characters and 8 unique whitespace-separated tokens. Repeated tokens are
  compared case-insensitively and only the first form is searched.

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
- `400`: invalid query parameter type, more than 100 characters, or more than
  8 unique tokens.
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

### GET `/api/v1/search/complexes/suggestions?q=`

Purpose:

- Return lightweight autocomplete candidates for the complex search box.
- This endpoint is optimized for suggestion text and selection identity, not
  full map focusing.

Request:

- Query parameter `q`: required string, trim before search. Blank queries return
  `[]`. The same 100-character and 8-unique-token limits as complex search
  apply, and repeated tokens are searched once.

Response:

```json
[
  {
    "complexId": 501,
    "complexName": "Sample Apartment",
    "parcelId": 1001,
    "address": "Sample address"
  }
]
```

Response fields:

- `complexId`
- `complexName`
- `parcelId`
- `address`

Status:

- `200`: successful lookup. Empty or no-match searches return `[]`.
- `400`: invalid query parameter type, more than 100 characters, or more than
  8 unique tokens.
- `500`: unexpected server error.

Migration notes:

- This endpoint must not expose `complex_pk`, `apt_seq`, `source`, or
  `source_key`.

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

### GET `/api/v1/region/{regionId}/complexes`

Purpose:

- Return a paged list of complexes under the selected region and its child
  regions.
- Used by the region navigation panel to select a complex without relying on
  marker visibility.

Request:

- Path parameter `regionId`: selected region id.
- Query parameter `limit`: optional page size. Defaults to `50`; values above
  the server cap may be reduced.
- Query parameter `offset`: optional zero-based row offset. Defaults to `0`.

Response:

```json
[
  {
    "complexId": 701,
    "complexName": "Region Complex",
    "parcelId": 2001,
    "latitude": 37.5123,
    "longitude": 127.0456,
    "address": "Region address",
    "dongCnt": 8,
    "unitCnt": 740,
    "useDate": "2018-05-01"
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
- `dongCnt`
- `unitCnt`
- `useDate`

Status:

- `200`: successful lookup. A valid region with no complexes returns `[]`.
- `400`: invalid `limit` or `offset`.
- `404`: region id does not exist.
- `500`: unexpected server error.

Migration notes:

- Region complex list is still a map/read-path helper. It must not depend on
  ranking, trend, favorite, alarm, mail, recommendation, or auth state.
- This endpoint must not expose audit/source fields.

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

### GET `/api/v1/detail/{parcelId}/complexes`

Purpose:

- Return all selectable complexes under one parcel.
- Used by the detail drawer to switch between same-PNU or same-parcel
  complexes.

Response:

```json
[
  {
    "complexId": 501,
    "complexName": "Tower A",
    "parcelId": 1001,
    "latitude": 37.5123,
    "longitude": 127.0456,
    "address": "Sample address",
    "dongCnt": 5,
    "unitCnt": 320,
    "useDate": "2015-03-20"
  }
]
```

Status:

- `200`: successful lookup. A valid parcel with no complexes returns `[]`.
- `404`: parcel id does not exist.
- `500`: unexpected server error.

Migration notes:

- Coordinates may be `null` for coordinate-pending parcels.
- This endpoint must not expose audit/source fields.

### GET `/api/v1/complex/{complexId}`

Purpose:

- Return the same canonical detail shape as `/api/v1/detail/{parcelId}`, but
  addressed directly by `complexId`.
- Used for direct URL state restoration and search/region list selection.

Response:

- Same body shape as `GET /api/v1/detail/{parcelId}`.

Status:

- `200`: successful lookup.
- `404`: complex id does not exist.
- `500`: unexpected server error.

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
- Optional query parameter `page`: zero-based page index. Defaults to `0`.
- Optional query parameter `size`: page size. Defaults to `25` and is
  server-capped at `100`.

Response:

```json
{
  "parcelId": 1001,
  "complexId": 501,
  "content": [
    {
      "tradeId": 9001,
      "dealDate": "2025-12-01",
      "exclArea": 84.93,
      "dealAmount": 125000,
      "aptDong": "101",
      "floor": 12
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Response fields:

- `parcelId`
- `complexId`: selected complex id when scoped; otherwise nullable.
- `content`: trades on the requested page, newest first.
- `page`: zero-based index of the returned page.
- `size`: page size used for the response.
- `totalElements`: total trade count across all pages for the request scope.
- `totalPages`: total page count for the request scope.

Trade item fields (`content[]`):

- `tradeId`: trade id.
- `dealDate`: `YYYY-MM-DD`.
- `exclArea`: exclusive area in square meters.
- `dealAmount`: trade amount in 10,000 KRW units.
- `aptDong`: optional apartment building name or number.
- `floor`: optional floor.

Status:

- `200`: successful lookup. If the parcel exists but has no trades, return an
  empty `content` list with `totalElements` `0`.
- `400`: `page` is negative or `size` is less than `1`.
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
- Default ordering should be newest first within each page: `dealDate`
  descending, then `tradeId` descending when dates are equal.

### GET `/api/v1/complex/{complexId}/trades`

Purpose:

- Return the same canonical trade-list shape as `/api/v1/trade/{parcelId}`, but
  addressed directly by `complexId`.
- Used by direct complex detail flows and chart/list refreshes.

Request:

- Optional query parameter `page`: zero-based page index. Defaults to `0`.
- Optional query parameter `size`: page size. Defaults to `25` and is
  server-capped at `100`.

Response:

- Same page-envelope body shape as `GET /api/v1/trade/{parcelId}`
  (`content`, `page`, `size`, `totalElements`, `totalPages`). `complexId` is
  always the requested complex id when the response is successful.

Status:

- `200`: successful lookup. If the complex exists but has no active trades,
  return an empty `content` list with `totalElements` `0`.
- `400`: `page` is negative or `size` is less than `1`.
- `404`: complex id does not exist.
- `500`: unexpected server error.

Migration notes:

- The query path must use normalized `trade.complex_id` and exclude
  soft-deleted rows where `deleted_at IS NOT NULL`.
- This endpoint must not expose audit/source fields.

### GET `/api/v1/trade/{parcelId}/trend`

Purpose:

- Return the **monthly average trade price series** for a parcel (or scoped
  complex), used by the detail price-trend chart.

Request:

- Optional query parameter `complexId`: selected complex id. When omitted, the
  trend covers all complexes under the parcel.

Response:

```json
[
  { "month": "2025-10", "avgAmount": 100000, "count": 1, "minAmount": 100000, "maxAmount": 100000 },
  { "month": "2025-12", "avgAmount": 127500, "count": 2, "minAmount": 125000, "maxAmount": 130000 }
]
```

Response item fields:

- `month`: `YYYY-MM` trade month.
- `avgAmount`: average deal amount in 10,000 KRW units.
- `count`: number of trades in the month.
- `minAmount` / `maxAmount`: min/max deal amount in 10,000 KRW units.

Status:

- `200`: ordered oldest month first. If the parcel exists but has no trades,
  return an empty array.
- `404`: parcel or complex parent path does not exist.
- `500`: unexpected server error.

Migration notes:

- Aggregate over normalized `trade.complex_id`, excluding soft-deleted rows.
  Group by calendar month; months without trades are omitted.

### GET `/api/v1/complex/{complexId}/trade-trend`

Purpose:

- Return the same monthly-average trend shape as `/api/v1/trade/{parcelId}/trend`,
  but addressed directly by `complexId`.

Response:

- Same array body shape as `GET /api/v1/trade/{parcelId}/trend`.

Status:

- `200`: ordered oldest month first; empty array when the complex has no active
  trades.
- `404`: complex id does not exist.
- `500`: unexpected server error.

## Authenticated User APIs

These APIs are owned by user-service. Existing property-data map, search,
detail, trend, and trade APIs remain unauthenticated. Access tokens use RS256,
remain memory-only in the browser, and must contain `iss=user-service`, exactly
one `aud=home-search-user-api`, a positive numeric `sub`, and `role=USER`. The
verified `sub` is the only accepted user id.

Authentication failures return `401` with code `AUTHENTICATION_REQUIRED` and
the common `ProblemDetail` fields. Auth refresh/logout mutations accept only the
single configured frontend `Origin`.

### OAuth login endpoints

- `GET /oauth2/authorization/{provider}` starts login for the allowlisted
  `google`, `kakao`, or `naver` provider.
- `GET /login/oauth2/code/{provider}` is the provider callback.

Unsupported providers are not forwarded to an arbitrary authorization URL.

### POST `/auth/access`

Rotates the HttpOnly `refresh_token` cookie and returns a new memory-only access
token. A missing, expired, revoked, or reused refresh token returns `401`.

```json
{ "accessToken": "signed-rs256-token" }
```

### POST `/auth/logout`

Revokes the presented refresh token when present, expires the refresh cookie,
and returns `204`. It is idempotent when the cookie is absent.

### GET `/api/v1/users/me`

Returns the verified user's stable profile without exposing email or OAuth
provider tokens.

```json
{
  "userId": 42,
  "provider": "GOOGLE",
  "displayName": "홍길동",
  "profileImage": null
}
```

### GET `/api/v1/favorites?page=0&size=20`

- `page` defaults to `0` and must be non-negative.
- `size` defaults to `20`, must be at least `1`, and is capped at `100`.
- Items are ordered by `savedAt DESC`, then `complexId DESC`.

```json
{
  "content": [{ "complexId": 501, "savedAt": "2026-07-13T06:00:00Z" }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Invalid pagination returns `400` with code `INVALID_PAGINATION`.

### GET `/api/v1/favorites/{complexId}`

Returns `200` for both saved and unsaved complexes. An unsaved item returns
`favorite: false` and `savedAt: null`.

```json
{ "complexId": 501, "favorite": true, "savedAt": "2026-07-13T06:00:00Z" }
```

### PUT `/api/v1/favorites/{complexId}`

Saves the complex and returns `204` with no body. Duplicate PUT is successful
and preserves the original `savedAt`. A user may save at most 200 complexes;
attempting to save a new item over the limit returns `409` with code
`FAVORITE_LIMIT_REACHED`.

### DELETE `/api/v1/favorites/{complexId}`

Removes the saved complex and returns `204` with no body. Deleting an absent
item is also successful.

For every favorite item endpoint, `complexId` must be a positive integer.
Invalid values return `400` with code `INVALID_COMPLEX_ID`. Favorite rows store
only `userId`, opaque `complexId`, and `savedAt`; user-service does not join or
foreign-key the property-data database.

## Admin APIs

Admin browser APIs are owned by `admin-service`, not property-data. They require
an authenticated server Session and route permission. Mutations additionally
require the `XSRF-TOKEN` cookie value in the `X-XSRF-TOKEN` header.
Admin-service forwards authorized work to property-data over
`/internal/v1/admin/**` with a 60-second RS256 token. The browser never calls
the internal endpoints directly, and actor identity is derived from the
Session/JWT rather than a request body.

### GET `/api/v1/admin/coordinates/pending`

Purpose:

- Return coordinate-pending complexes that have stored identity/trade data but
  no marker-safe parcel coordinates.
- This endpoint is an operational correction surface exposed by admin-service.
- Requires `COORDINATE_READ`.

Query parameters:

- `limit`: optional page size. Defaults to `50`.
- `offset`: optional zero-based row offset for paging. Defaults to `0`.
- Invalid `limit` values below `1` or invalid `offset` values below `0`
  return `400` with the standard `ProblemDetail` body.

Errors:

- Missing or expired Session returns `401`; insufficient permission returns
  `403`, both with a `ProblemDetail` body.

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
- This endpoint is an operational summary for the paged pending list.
- Requires `COORDINATE_READ`.

Request:

- No request body.
- No query parameters.

Errors:

- Missing or expired Session returns `401`; insufficient permission returns
  `403`.

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
- `401`: missing or expired admin Session.
- `403`: insufficient permission.
- `500`: unexpected server error.

### PUT `/api/v1/admin/coordinates/{pnu}/override`

Purpose:

- Approve a manual coordinate override for an identity-safe PNU.
- The override updates the existing `parcel` coordinates and does not create a
  new parcel, complex, or trade row.
- Requires `COORDINATE_WRITE` and a valid CSRF token.

Errors:

- Missing or expired Session returns `401`; insufficient permission or invalid
  CSRF token returns `403`.

Request:

```json
{
  "latitude": 37.5123,
  "longitude": 127.0456,
  "reason": "operator verified missing coordinate"
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

### Metadata Enrichment Admin APIs

The metadata enrichment admin surface is exposed by admin-service. Reads require
`METADATA_READ`; retry, HOLD, and alias operations require their corresponding
permissions and valid CSRF token.

Read APIs:

- `GET /api/v1/admin/metadata/pending?limit=50&offset=0`: unresolved or held
  complex metadata snapshots.
- `GET /api/v1/admin/metadata/pending/summary`: whole-query counts by metadata
  status.
- `GET /api/v1/admin/metadata/{complexId}`: complex snapshot, enrichment
  attempt evidence, and admin decision history.
- `GET /api/v1/admin/metadata/pnu-aliases`: ODC PNU prefix alias proposals and
  approval state.

Mutation APIs:

- `POST /api/v1/admin/metadata/{complexId}/retry`
- `POST /api/v1/admin/metadata/{complexId}/hold`
- `POST /api/v1/admin/metadata/pnu-aliases`
- `POST /api/v1/admin/metadata/pnu-aliases/{aliasId}/approve`
- `POST /api/v1/admin/metadata/pnu-aliases/{aliasId}/disable`

Retry, HOLD, approve, and disable requests require:

```json
{
  "reason": "operator verification evidence"
}
```

Alias proposal additionally requires numeric eight-digit `canonicalPrefix`
and `sourcePrefix`. Admin mutations append decision audit evidence. They do not
directly edit operational PNU, region relationships, complex identity, or
metadata values.

Status:

- `200`: successful read or mutation.
- `400`: invalid page, prefix, reason, complex, or alias target.
- `401`: missing or expired admin Session.
- `403`: insufficient permission or invalid CSRF token.
- `409`: downstream domain state conflict or duplicate alias proposal.
- `500`: unexpected server error.

These endpoints are additive admin operations. Public map, search, detail, and
trade API URLs and response shapes do not change.

## later-scope APIs

Keep these out of the current critical path:

- `/api/v1/rankings/top-price-30d`
- `/api/v1/rankings/top-volume-30d`
- `/admin/batch/trade-alarm/run`

They should not be deleted from source knowledge, but they must not block
collection, storage, and map display.

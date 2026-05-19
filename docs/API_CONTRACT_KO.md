# API Contract

## Purpose

이 문서는 Home Search migration을 위한 V1 public API contract다. target backend와 frontend가 따라야 하는 HTTP URLs, request shapes, response shapes, units, error behavior, compatibility rules를 고정한다.

이 문서를 다음의 baseline으로 사용한다:

- Backend controllers, DTOs, repository projections, controller tests.
- Frontend API clients, marker adapters, detail panels, search behavior.
- source backend fields가 source frontend expectations와 충돌할 때 migration decisions.

V1은 frontend가 새 route names 없이 map을 표시할 수 있도록 main API URLs를 안정적으로 유지한다. safer storage를 지원하기 위해 internal implementation은 바뀔 수 있지만, 이 contract가 먼저 업데이트되지 않는 한 그 changes가 public V1 API로 새어나오면 안 된다.

## Current Work Package

이 work package는 API contract만 명확히 한다. backend code, frontend adapters, OpenAPI YAML, new tests를 구현하지 않는다.

다음 implementation batches는 이 문서를 사용해 다음을 만들어야 한다:

- Backend request/response DTOs and controller tests.
- canonical response fields를 emit하는 Repository projections.
- temporary source-field variants를 normalize하는 Frontend adapters.
- map, search, region, detail, trade flows를 위한 Integration tests.

## Fixed Paths

Source backend:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target repository:

- `/Users/gwongwangjae/home-search`

## Contract Authority

source backend와 source frontend는 read-only references다. source code와 이 target contract가 다르면 target V1 implementation은 이 문서를 따르고 source mismatch를 migration note로 남겨야 한다.

migration scope가 명시적으로 바뀌지 않는 한 rankings, favorites, alarms, mail batches, recommendations, auth flows, heavy analytics를 V1에 넓히지 않는다.

## Common Conventions

- Canonical URLs는 leading slash를 포함한다. 예: `/api/v1/map/regions`.
- Requests와 responses는 JSON을 사용한다.
- Coordinates는 WGS84 / EPSG:4326을 사용한다.
- `lat`와 `latitude`는 latitude를 뜻한다. `lng`와 `longitude`는 longitude를 뜻한다.
- Dates는 `YYYY-MM-DD`를 사용한다.
- `dealAmount`와 `latestDealAmount`는 10,000 KRW units의 integer values다.
- `priceEokMin`와 `priceEokMax`는 eok units의 filter inputs다. backend는 stored trade amounts와 비교하기 전에 이를 10,000 KRW units로 변환한다.
- Nullable filter fields는 "이 filter를 적용하지 않는다"를 뜻한다.
- request 자체가 valid하면 empty list results는 `200`과 `[]`를 반환해야 한다.
- Optional response fields는 `null`이거나 omitted될 수 있다.

## Compatibility Policy

V1 compatible changes:

- optional response field 추가.
- optional로 문서화된 field를 `null`로 반환하거나 omit.
- temporary adapter 안에서 legacy frontend field variants 허용.

V1 breaking changes:

- documented response field 제거.
- documented field rename.
- documented field type 또는 unit 변경.
- 이전에 optional이던 field를 required로 만들기.
- public URL 또는 HTTP method 변경.

Target V1은 canonical fields를 expose해야 한다. source code의 legacy variants는 frontend adapter에서 일시적으로 허용할 수 있지만 backend responses는 아래 canonical fields로 converge해야 한다.

## Error Policy

V1은 Spring `ProblemDetail` style error bodies를 사용한다.

Minimum error fields:

- `type`
- `title`
- `status`
- `detail`
- `exception`
- `timestamp`

Status rules:

- Invalid request body, invalid query parameter, invalid enum: `400`.
- Missing region, parcel, complex, detail, trade parent resource: `404`.
- Unexpected server error 또는 external integration failure: `500`.

Example:

```json
{
  "type": "/docs/index.html#error-code-list",
  "title": "C401",
  "status": 400,
  "detail": "Invalid parameter format.",
  "exception": "MapApiException",
  "timestamp": "2026-05-18T10:30:00"
}
```

## V1 APIs

### POST `/api/v1/map/regions`

Purpose:

- current map bounds 안의 region-level markers를 반환한다.
- Kakao map이 zoomed out 상태일 때 사용된다.

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
- `trend`: optional regional trend value. V1은 `null`을 반환하거나 omit할 수 있다.

Status:

- `200`: successful lookup. `[]`일 수 있다.
- `400`: invalid bounds 또는 unsupported `region`.
- `500`: unexpected server error.

Migration notes:

- Regional trend calculation은 V1 map display에 required가 아니다.
- Source repository aliases는 target field names와 정확히 일치하지 않을 수 있다. Target V1은 `name`, `lat`, `lng`를 expose해야 한다.
- `unitCntSum`은 required V1 region-marker field가 아니다. Frontend code는 region markers를 render하기 위해 이를 요구하면 안 된다.

### POST `/api/v1/map/complexes`

Purpose:

- current map bounds 안의 parcel-level apartment complex markers를 반환한다.
- Kakao map이 detailed markers를 보여줄 만큼 zoomed in 상태일 때 사용된다.

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
    "lat": 37.5123,
    "lng": 127.0456,
    "latestDealAmount": 125000,
    "unitCntSum": 740
  }
]
```

Response fields:

- `parcelId`: detail 및 trade APIs에서 사용하는 parcel id.
- `lat`: marker latitude.
- `lng`: marker longitude.
- `latestDealAmount`: optional latest trade amount in 10,000 KRW units.
- `unitCntSum`: parcel 아래 total household count.

Status:

- `200`: successful lookup. `[]`일 수 있다.
- `400`: invalid bounds 또는 invalid filter type/range.
- `500`: unexpected server error.

Migration notes:

- source code에는 `parcelId`, `id`, `latitude`, `lat`, `longitude`, `lng` naming이 섞여 있다.
- Target V1 backend는 위 canonical fields를 반환해야 한다.
- Frontend adapters는 source code가 migrate되는 동안 `id`, `latitude`, `longitude`를 일시적으로 허용할 수 있지만 새 target code는 `parcelId`, `lat`, `lng`를 선호해야 한다.
- Map marker APIs는 ranking, trend, favorite, alarm, mail, auth state를 요구하면 안 된다.

### GET `/api/v1/search/complexes?q=`

Purpose:

- user-entered text로 apartment complexes를 search한다.
- left sidebar search flow에서 사용된다.

Source controller:

- `src/main/java/com/home/infrastructure/web/search/SearchController.java`

Frontend source consumers:

- `src/components/sidebar/LeftSidebar.jsx`
- `src/store/uiSlice.js`

Request:

- Query parameter `q`: required string, search 전에 trim한다.

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

- `200`: successful lookup. empty 또는 no-match searches는 `[]`를 반환한다.
- `400`: invalid query parameter type.
- `500`: unexpected server error.

Migration notes:

- 이 endpoint는 source frontend compatibility를 위해 `latitude`와 `longitude`를 유지한다. frontend adapter를 같은 batch에서 update하지 않는 한 V1에서 이를 `lat`와 `lng`로 rename하지 않는다.

### GET `/api/v1/region`

Purpose:

- region navigation을 위한 root regions를 load한다.

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

- `200`: successful lookup. `[]`일 수 있다.
- `500`: unexpected server error.

### GET `/api/v1/region/{regionId}`

Purpose:

- region detail, child regions, center coordinates를 load한다.
- region navigation과 map recentering에 사용된다.

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

- selected marker의 parcel 및 representative complex details를 반환한다.

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Frontend source consumer:

- `src/components/sidebar/detail/DetailSidebar.jsx`

Response:

```json
{
  "parcelId": 1001,
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
- `404`: parcel 또는 representative complex does not exist.
- `500`: unexpected server error.

Migration notes:

- Source DTO는 `null` values를 omit한다. Target V1도 nullable fields를 explicit `null`로 반환하기보다 omit할 수 있다.

### GET `/api/v1/trade/{parcelId}`

Purpose:

- selected parcel 아래 complexes의 trade list를 반환한다.

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Frontend source consumer:

- `src/components/sidebar/detail/TradeSidebar.jsx`

Response:

```json
{
  "parcelId": 1001,
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
- `trades`

Trade item fields:

- `tradeId`: trade id.
- `dealDate`: `YYYY-MM-DD`.
- `exclArea`: exclusive area in square meters.
- `dealAmount`: trade amount in 10,000 KRW units.
- `aptDong`: optional apartment building name or number.
- `floor`: optional floor.

Status:

- `200`: successful lookup. parcel은 존재하지만 trades가 없으면 empty `trades` list를 반환한다.
- `404`: parcel 또는 complex parent path does not exist.
- `500`: unexpected server error.

Migration notes:

- target V1 query path는 `complex_id`를 통해 동작해야 한다.
- audit, matching, deduplication을 위해 `complex_pk`, `apt_seq`, `source`, `source_key`를 보존하되 public response에는 노출하지 않는다.
- default ordering은 newest first여야 한다: `dealDate` descending, dates가 같으면 `tradeId` descending.

## V2 APIs

다음은 V1 critical path 밖에 둔다:

- `/api/v1/rankings/top-price-30d`
- `/api/v1/rankings/top-volume-30d`
- `/api/v1/favorites`
- `/api/v1/users/me`
- `/auth/access`
- `/admin/batch/trade-alarm/run`

source knowledge에서 삭제해서는 안 되지만 collection, storage, map display를 막으면 안 된다.

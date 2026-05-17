# API Contract

## Rule

V1 keeps the main API URLs stable. Internal implementation may change to support
safer storage, but the frontend should not need new route names to display the
map.

Source backend:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target repository:

- `/Users/gwongwangjae/home-search`

## V1 APIs

### POST `/api/v1/map/regions`

Source controller:

- `src/main/java/com/home/infrastructure/web/map/MapController.java`

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

Allowed `region` values:

- `si-do`
- `si-gun-gu`
- `eup-myeon-dong`

Response fields:

- `id`
- `name`
- `lat`
- `lng`
- `trend`

V1 note:

- `trend` can be `null` or omitted until trend migration moves to V2.

### POST `/api/v1/map/complexes`

Source controller:

- `src/main/java/com/home/infrastructure/web/map/MapController.java`

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

Response fields for frontend compatibility:

- `parcelId`
- `lat`
- `lng`
- `latestDealAmount`
- `unitCntSum`

Compatibility note:

- The source code has mixed naming around `parcelId`, `id`, `latitude`, `lat`,
  `longitude`, and `lng`. V1 should stabilize the public response to the fields
  above while the frontend adapter may temporarily accept old variants.

### GET `/api/v1/search/complexes?q=`

Source controller:

- `src/main/java/com/home/infrastructure/web/search/SearchController.java`

Response fields:

- `complexId`
- `complexName`
- `parcelId`
- `latitude`
- `longitude`
- `address`

Frontend source consumers:

- `src/components/sidebar/LeftSidebar.jsx`
- `src/store/uiSlice.js`

### GET `/api/v1/region`

Source controller:

- `src/main/java/com/home/infrastructure/web/region/RegionController.java`

Purpose:

- Load root regions for region navigation.

### GET `/api/v1/region/{regionId}`

Source controller:

- `src/main/java/com/home/infrastructure/web/region/RegionController.java`

Purpose:

- Load region detail, children, and center coordinates.

Frontend source consumer:

- `src/components/sidebar/region/RegionNavSidebar.jsx`

### GET `/api/v1/detail/{parcelId}`

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Purpose:

- Return parcel and representative complex details.

Frontend source consumer:

- `src/components/sidebar/detail/DetailSidebar.jsx`

### GET `/api/v1/trade/{parcelId}`

Source controller:

- `src/main/java/com/home/infrastructure/web/detail/DetailController.java`

Purpose:

- Return trade list for complexes under the parcel.

Frontend source consumer:

- `src/components/sidebar/detail/TradeSidebar.jsx`

V1 note:

- The query path should work through `complex_id` in target V1.

## V2 APIs

Keep these out of the V1 critical path:

- `/api/v1/rankings/top-price-30d`
- `/api/v1/rankings/top-volume-30d`
- `/api/v1/favorites`
- `/api/v1/users/me`
- `/auth/access`
- `/admin/batch/trade-alarm/run`

They should not be deleted from source knowledge, but they should not block
collection, storage, and map display.

# Map Display Flow

## Goal

stable V1 APIs를 사용해 real-estate trade data를 Kakao map에 표시한다.

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target frontend:

- `/Users/gwongwangjae/home-search/apps/web`

## Current Source Flow

현재 central file:

- `src/App.jsx`

현재 behavior:

1. `KakaoMap.jsx`가 Kakao Map을 만든다.
2. `onCreate`와 `onIdle`이 `fetchMarkers`를 호출한다.
3. `fetchMarkers`가 map center, level, bounds를 읽는다.
4. Map level이 endpoint를 선택한다:
   - `level <= 4`: `api/v1/map/complexes`
   - otherwise: `api/v1/map/regions`
5. response를 marker coordinates로 normalize한다.
6. Complex markers 또는 region markers를 render한다.

## V1 Flow

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

compatibility를 위해 current source behavior를 유지한다:

- `level <= 4`: complex markers 표시.
- `level >= 10`: `si-do` request.
- `level >= 7`: `si-gun-gu` request.
- `level >= 4`: `eup-myeon-dong` request.

level thresholds는 나중에 조정할 수 있지만 V1 migration은 map display가 안정될 때까지 이를 보존해야 한다.

## Complex Marker Contract

Complex markers에 필요한 fields:

- `parcelId`
- `lat`
- `lng`
- `latestDealAmount`
- `unitCntSum`

Marker display:

- `latestDealAmount`에서 price label.
- `unitCntSum`에서 unit label.
- click하면 `parcelId`의 detail drawer를 연다.

## Backend Query Boundary

`/api/v1/map/complexes`는 map display에 필요한 만큼만 수행해야 한다:

- PostGIS bounds로 parcels filter.
- parcel 아래 complexes join.
- latest trade amount compute 또는 select.
- unit count, price, area, age의 simple filters 적용.

다음을 요구하지 않는다:

- Ranking tables.
- Trend tables.
- 30-day aggregate tables.
- Mail 또는 favorite state.

## Frontend Error Behavior

marker API failure 시:

- current markers를 clear한다.
- map usable 상태를 유지한다.
- map에서 벗어나지 않는다.
- redesigned UI에 small non-blocking error state를 보여준다.

## Acceptance Criteria

- map 이동 또는 zoom이 marker refresh를 trigger한다.
- Wide zoom은 region markers를 보여준다.
- Detailed zoom은 complex markers를 보여준다.
- Complex marker click이 detail과 trade data를 연다.
- Map display가 V1 data tables만으로 동작한다.

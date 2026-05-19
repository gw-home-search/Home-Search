# Architecture Baseline

## Source System

Backend source:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Frontend source:

- `/Users/gwongwangjae/frontend/home-client`

Migration target:

- `/Users/gwongwangjae/home-search`

## Backend Current Shape

source backend는 layered Spring Boot structure를 따른다:

```text
src/main/java/com/home
├── application/
├── domain/
├── global/
└── infrastructure/
```

중요한 V1 packages:

- `application/map`: map marker use case.
- `application/region`: region navigation use case.
- `application/search`: complex search use case.
- `application/detail`: complex detail and trade list use case.
- `domain/region`: region hierarchy.
- `domain/parcel`: parcel, coordinates, map bounds queries.
- `domain/complex`: apartment complex metadata.
- `domain/trade`: trade data model.
- `infrastructure/external/apis`: RTMS와 building public data client.
- `infrastructure/batch/trade`: trade collection and bulk insert flow.
- `infrastructure/web`: HTTP API controllers.

## Backend V1 Target

`/Users/gwongwangjae/home-search/apps/api`에서는 backend를 layered로 유지하되 V1 boundary를 더 명확하게 만든다:

```text
apps/api
├── src/main/java/com/home
│   ├── application/
│   ├── domain/
│   ├── infrastructure/
│   │   ├── external/
│   │   ├── persistence/
│   │   ├── batch/
│   │   └── web/
│   └── global/
└── src/main/resources/db/migration/
```

첫 이동 중에는 existing package names를 유지해도 된다. 중요한 결정은 package rename이 아니라 V1을 collection, storage, map display에 집중시키는 것이다.

## Frontend Current Shape

source frontend는 Vite React app이다:

```text
src
├── App.jsx
├── axiosInstance/
├── components/
│   ├── filters/
│   ├── map/
│   └── sidebar/
├── data/
└── store/
```

중요한 V1 files:

- `src/App.jsx`: map level logic과 marker API calls.
- `src/axiosInstance/AxiosInstance.jsx`: `VITE_API_SERVER_IP` base URL.
- `src/store/uiSlice.js`: map state, filters, selected parcel, sidebar mode.
- `src/components/map/KakaoMap.jsx`: Kakao map wrapper.
- `src/components/map/RegionMarkers.jsx`: region marker rendering.
- `src/components/map/ComplexMarkers.jsx`: complex marker rendering.
- `src/components/sidebar/LeftSidebar.jsx`: search and region navigation shell.
- `src/components/sidebar/detail/DetailSidebar.jsx`: detail API consumer.
- `src/components/sidebar/detail/TradeSidebar.jsx`: trade API consumer.

## Frontend V1 Target

`/Users/gwongwangjae/home-search/apps/web`에서는 map exploration 중심으로 UI/UX를 다시 만들면서 API calls compatibility를 유지한다:

```text
apps/web/src
├── app/
├── api/
├── features/
│   ├── map/
│   ├── search/
│   ├── region/
│   └── complex-detail/
├── shared/
└── store/
```

이는 target direction이며 mandatory first-copy layout은 아니다. source code를 먼저 copy하는 것이 더 빠르면 source code를 그대로 migrate한 뒤 API compatibility가 확인된 후 이 shape로 refactor한다.

## Critical Risk

source backend는 두 trade relationship models를 섞고 있다:

- Detail과 JPA paths는 `trade.complex_id -> complex.id`를 사용한다.
- Batch insert paths는 `trade.complex_pk -> complex.complex_pk`를 사용한다.

V1은 이 ambiguity를 조용히 가져가면 안 된다. target operational query model은 `complex_id`를 사용해야 하며, `complex_pk`, `apt_seq`, `source`, `source_key`는 source tracking columns로 유지한다.

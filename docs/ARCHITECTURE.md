# Architecture Baseline


## Source System

Backend source:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Frontend source:

- `/Users/gwongwangjae/frontend/home-client`

Migration target:

- `/Users/gwongwangjae/home-search`

## Backend Current Shape

The source backend follows a layered Spring Boot structure:

```text
src/main/java/com/home
├── application/
├── domain/
├── global/
└── infrastructure/
```

Important V1 packages:

- `application/map`: map marker use case.
- `application/region`: region navigation use case.
- `application/search`: complex search use case.
- `application/detail`: complex detail and trade list use case.
- `domain/region`: region hierarchy.
- `domain/parcel`: parcel, coordinates, and map bounds queries.
- `domain/complex`: apartment complex metadata.
- `domain/trade`: trade data model.
- `infrastructure/external/apis`: RTMS and building public data client.
- `infrastructure/batch/trade`: trade collection and bulk insert flow.
- `infrastructure/web`: HTTP API controllers.

## Backend V1 Target

In `/Users/gwongwangjae/home-search/apps/api`, keep the backend layered but
make the V1 boundary clearer:

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

The implementation can keep existing package names during the first move. The
important decision is not package renaming; it is keeping V1 focused on
collection, storage, and map display.

## Frontend Current Shape

The source frontend is a Vite React app:

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

Important V1 files:

- `src/App.jsx`: map level logic and marker API calls.
- `src/axiosInstance/AxiosInstance.jsx`: `VITE_API_SERVER_IP` base URL.
- `src/store/uiSlice.js`: map state, filters, selected parcel, sidebar mode.
- `src/components/map/KakaoMap.jsx`: Kakao map wrapper.
- `src/components/map/RegionMarkers.jsx`: region marker rendering.
- `src/components/map/ComplexMarkers.jsx`: complex marker rendering.
- `src/components/sidebar/LeftSidebar.jsx`: search and region navigation shell.
- `src/components/sidebar/detail/DetailSidebar.jsx`: detail API consumer.
- `src/components/sidebar/detail/TradeSidebar.jsx`: trade API consumer.

## Frontend V1 Target

In `/Users/gwongwangjae/home-search/apps/web`, keep API calls compatible while
reworking UI/UX around map exploration:

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

This is the target direction, not a mandatory first-copy layout. If copying
source code first is faster, migrate source code as-is, then refactor toward
this shape after API compatibility is verified.

## Critical Risk

The source backend mixes two trade relationship models:

- Detail and JPA paths use `trade.complex_id -> complex.id`.
- Batch insert paths use `trade.complex_pk -> complex.complex_pk`.

V1 must not carry this ambiguity forward silently. The target operational query
model should use `complex_id`, while retaining `complex_pk`, `apt_seq`,
`source`, and `source_key` as source tracking columns.

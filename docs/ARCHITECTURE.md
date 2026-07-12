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

Important project packages:

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

## Backend Project Target

In `/Users/gwongwangjae/home-search/apps/property-data`, keep the backend layered but
make the project boundary clearer:

```text
apps/property-data
├── core/
│   └── src/main/java/com/home
│       ├── application/
│       ├── domain/
│       └── infrastructure/
│           ├── external/
│           ├── persistence/
│           └── cache/
├── api/
│   └── src/main/java/com/home
│       ├── HomeSearchApiApplication.java
│       ├── infrastructure/web/
│       ├── infrastructure/scheduling/
│       └── global/
├── batch/
│   └── src/main/java/com/home/batch
│       ├── PropertyDataBatchApplication.java
│       ├── launch/
│       └── rtms/
└── migration/
    └── src/main/java/com/home/migration
        ├── PropertyDataMigrationApplication.java
        └── explicit Flyway/backfill operations
```

The implementation can keep existing package names during the first move. The
important decision is not package renaming; it is keeping project focused on
collection, storage, and map display.

`apps/property-data` is the property-data-service boundary. `core`, `api`, and `batch`
are internal module or execution-mode boundaries
inside that service and keep one `home_search` database ownership model.

The API and Batch applications are separate composition roots. API composes HTTP and
map-serving adapters, while Batch composes the daily RTMS job, tasklets, ingest use
cases, persistence adapters, and operational notification. Daily RTMS ingest is owned
only by the packaged Batch process; API does not register an RTMS scheduler even when
the removed legacy property is supplied.

`core` remains a physical Gradle module shared by those two composition roots. Further
domain/application/infrastructure module extraction requires a follow-up ADR and is not
part of the current runtime separation.

`migration`은 같은 property-data ownership boundary 안의 run-and-exit 운영
artifact다. API와 Batch는 모든 profile에서 Flyway 자동 실행을 끄며,
`home_search` schema 변경은 `property-data-migration.jar`의 명시적 operation만
수행한다. SQL/Java migration source는 계속 `core`가 소유하고 migration jar가
`classpath:db/migration/api` 한 location을 사용한다.

현재 `home_search`는 `public` domain table, `batch` Spring Batch metadata,
`public.flyway_schema_history` 하나를 유지한다. schema별 history 또는 Batch
metadata 물리 DB 분리는 실제 운영 격리 요구가 생길 때 후속 ADR로 검토한다.

## Coordinate Source Boundary

Home Search separates three databases even when one PostgreSQL cluster hosts
them:

- `home_search`: property-data API, Batch, and domain evidence.
- `home_search_admin`: admin accounts, RBAC, Session, and security audit.
- `home_search_coordinate_source`: coordinate snapshot/import state.

Every database has separate migrator and runtime/import/reader roles. API and
Batch processes keep `spring.flyway.enabled=false`; only explicit run-and-exit
migration artifacts own Flyway history.

The backend should connect to the coordinate source database through a dedicated
coordinate lookup component. It should not copy nationwide coordinate snapshots
into `home_search`, and it should not treat
`home_search.reference.parcel_coordinate_snapshot` as the operational coordinate
model.

RTMS master bootstrap should flow through:

```text
RTMS row -> PNU -> Coordinate Source DB lookup -> parcel -> complex -> trade
```

VWorld VM/WFS is reserved for same-PNU multi-complex marker disambiguation and
stores any confident complex-level result in `complex_display_coordinate`.

## Admin Control-plane Boundary

The admin product boundary is grouped by filesystem path while preserving two
independent applications:

```text
apps/admin
├── service
└── web
```

`service` and `web` keep separate build artifacts, containers, deployment
boundaries, and security responsibilities.

```text
Admin Web -> admin-service Session/RBAC -> short-lived signed internal token
          -> property-data /internal/v1/admin/**
```

Admin-service never reads `home_search` directly, and property-data never reads
`home_search_admin`. The browser does not call property-data directly. Domain
coordinate/metadata changes and their evidence remain one transaction owned by
property-data; the actor is derived from the authenticated principal rather
than accepted from a browser request body.

The legacy browser-facing property-data admin controllers are removed.
Public ingress returns `404` for `/internal/**`; only admin-service reaches the
internal property-data port on the service network.

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

Important project files:

- `src/App.jsx`: map level logic and marker API calls.
- `src/axiosInstance/AxiosInstance.jsx`: `VITE_API_SERVER_IP` base URL.
- `src/store/uiSlice.js`: map state, filters, selected parcel, sidebar mode.
- `src/components/map/KakaoMap.jsx`: Kakao map wrapper.
- `src/components/map/RegionMarkers.jsx`: region marker rendering.
- `src/components/map/ComplexMarkers.jsx`: complex marker rendering.
- `src/components/sidebar/LeftSidebar.jsx`: search and region navigation shell.
- `src/components/sidebar/detail/DetailSidebar.jsx`: detail API consumer.
- `src/components/sidebar/detail/TradeSidebar.jsx`: trade API consumer.

## Frontend Project Target

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

Home Search must not carry this ambiguity forward silently. The target operational query
model should use `complex_id`, while retaining `complex_pk`, `apt_seq`,
`source`, and `source_key` as source tracking columns.

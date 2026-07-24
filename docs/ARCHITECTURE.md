# Architecture Baseline


## Source System

Backend source:

- `/Users/gwongwangjae/IdeaProjects/home-server`

Frontend source:

- `/Users/gwongwangjae/frontend/home-client`

Migration target:

- `/Users/gwongwangjae/home-search`

Current Java platform baseline:

- All Java builds and local Java application containers use Java 21.
- property-data, user-service, admin-service, and source-data use Spring Boot
  4.1.0.
- Jackson data binding uses Jackson 3 (`tools.jackson`); Jackson annotations
  retain the supported `com.fasterxml.jackson.annotation` package.
- Property/user runtime artifacts remain Flyway-free. Admin/source migration
  operations own explicit Flyway execution.

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

## Implemented Property-data Shape

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
├── db/
│   ├── flyway.conf
│   └── migration/api/
│       └── external SQL-only Flyway catalog
└── ops/
    ├── property-flyway.sh
    └── property-deployment-preflight.sh
```

Public read behavior is split by change reason. Application packages are
`search`, `regionnavigation`, `propertydetail`, and `tradehistory`; matching
JDBC adapters and web controllers use the same feature names. Nearby-place
lookup depends on the narrow `ComplexCenterReader` port instead of a combined
property-read facade. Public URL and JSON contracts remain unchanged.

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

Spring Boot runtime ownership and framework annotation boundaries are separate
concerns. Domain code remains pure Java. Application services may use Spring
`@Service` and `@Transactional` so registration and transaction ownership are
visible at the use-case boundary, but application code must not depend on Spring
Data, JDBC, web APIs, JPA, or infrastructure implementations. Ordinary adapters
use component/repository registration. API and Batch remain explicit composition
roots for execution-mode imports, SecurityFilterChain, conditional cache,
executors, and external clients; configuration classes do not manually construct
every use case.

API와 Batch는 모든 profile에서 Flyway 자동 실행을 끈다. `home_search` schema
변경은 pinned official Flyway container가 외부 `db/migration/api` SQL catalog를
read-only mount해 수행한다. Flyway dependency와 migration SQL은 API/core/Batch
runtime artifact에 포함되지 않는다.

The build enforces the final architecture boundary: domain source cannot import
application, infrastructure, Spring, JDBC, JPA, or Flyway; application source
may import only Spring `@Service` and `@Transactional`; web source cannot import
persistence adapters directly; transactional application services and methods
cannot be `final`.

Migration version 8의 `complex.display_name`/generated `search_name`은 내부 검색
projection이다. 검색 matching과 ranking에는 사용할 수 있지만 기존 public
response의 `complexName`은 계속
`COALESCE(NULLIF(BTRIM(trade_name), ''), name)` 의미를 유지한다. Optional
Detail response는 기존 `name`/`tradeName`을 유지하면서 optional
`displayName`만 additive로 공개한다. Web title은 `displayName ?? name`을
사용하고 map marker source name과 기존 `complexName` 의미는 변경하지 않는다.

현재 `home_search`는 `public` domain table, `batch` Spring Batch metadata,
`public.flyway_schema_history` 하나를 유지한다. schema별 history 또는 Batch
metadata 물리 DB 분리는 실제 운영 격리 요구가 생길 때 후속 ADR로 검토한다.

## Coordinate Source Boundary

Home Search separates three databases even when one PostgreSQL cluster hosts
them:

- `home_search`: property-data API, Batch, and domain evidence.
- `home_search_admin`: admin accounts, RBAC, Session, and security audit.
- `home_search_coordinate_source`: coordinate snapshot/import state.
- `home_search_user`: OAuth identity and refresh-token state owned by
  user-service.

Every database has separate migrator and runtime/import/reader roles. API and
Batch processes keep `spring.flyway.enabled=false`; only explicit run-and-exit
migration artifacts own Flyway history.

## User And AI Service Boundaries

`apps/user/service/{core,app}` is an independent Gradle build and
deployment unit. It owns identity, refresh tokens, and favorite complexes in
`home_search_user.users`; property-data and
admin-service never read that database. Its RS256 user keys, issuer, and
audience are distinct from the admin internal JWT boundary. JPA entities,
Spring Data repositories, adapters, and the PostgreSQL identity lock live in
`core`; `app` owns Spring Boot, HTTP, OAuth, Security, cookies, and composition.
User Flyway runs only from the external Docker CLI against `db/migration/user`.

Reusable user-token claim verification lives in the pure Java
`libs/user-auth-contract` library on top of `security-jwt-core`. Consumer APIs
load allowlisted public keys locally and derive `userId` only from a fully
verified `sub`; they do not call user-service during token verification.

The nearby-place gateway is provider-neutral outside
`infrastructure/external/kakao`. Property-data owns the server-only Kakao key,
Redis quota/cache policy, and
`GET /api/v1/complex/{complexId}/nearby-places`; it does not persist place
results in PostgreSQL. Category cache entries expire within 24 hours and a
Seoul-day request budget fails closed when the Redis quota guard is unavailable.

`apps/ai` is an independent FastAPI deployment. Home Search facts enter only
through the `ai_read` read-only contract; dataset metadata, quality evidence,
POI/reference snapshots, legal corpus, chunks, and embeddings are ai-service-owned.
Feature code does not query property-data tables directly. Conversation state is
browser-owned IndexedDB data; the BFF and ai-service receive only a bounded recent
context per request and do not persist question or answer text.

```text
Browser -> user-service OAuth/JWT
Browser -> user-service authenticated favorites
Browser -> property-data public map/trade (unauthenticated)
Browser IndexedDB -> bounded conversationContext -> authenticated chatbot BFF
Browser -> authenticated chatbot BFF -> ai-service JSON/SSE
ai-service -> ai_read views (SELECT only)
ai-service -> home_search_ai reference/quality/RAG data
```

The implementation order is user-service first, evidence-grounded chatbot
capabilities second, then image/ECR CI and AWS deployment preparation.

## Market Insight And Digest Expansion

Property-data keeps insight calculation and news integration inside its
existing service boundary:

```text
RTMS batch -> collection execution/work-unit evidence -> insight snapshot
NAVER API HUB -> property batch -> Redis current/last-good news cache
property public API -> web MapApp /insights rail mode
property public API -> user batch -> user inbox / SES
```

The `core` module owns `domain.insight`, `application.insight`, feature-local
JDBC snapshot adapters, the NAVER adapter, and news cache adapters. The `api`
module owns only public insight/news HTTP DTOs and controllers. The `batch`
module owns collection evidence lifecycle and insight/news job composition.
Existing map/search/detail/trade packages must not import insight packages.
The web composition root may coordinate the feature-local insight rail with
the existing detail selection and map focus ports; map marker repositories and
runtime hooks remain independent of insight storage and request state.

The operational daily chain is:

```text
rtmsDailyRefreshJob
  -> complete DAILY/NATIONWIDE collection evidence
  -> daily insight step
  -> rolling 7-day insight step
  -> atomic nationwide + 17 SIDO publication
```

The rolling step uses only the latest execution whose `runDate` exactly
matches the batch date. It derives `periodStart=runDate-6 days` from structured
raw registration/cancellation dates and joins canonical trades by source
identity. Registration-based sections prefer `registration_date`; an
uncanceled trade without a usable registration date falls back to its
canonical `trade.deal_date`. Cancellation sections continue to require
`cancellation_date`, and canceled trades never enter the other five sections.
Those five sections only admit current contracts within one calendar month of
`runDate`. Exact-area record/rise/fall calculations require the immediately
previous contract date for the same `(complex_id, excl_area)` to be within six
calendar months of the current contract; record-high still compares against
the all-time maximum after that comparability gate passes.
The job does not schedule work in the API runtime and does
not make map/search/detail repositories depend on snapshot state.
A rejected daily insight result fails the batch step, so the rolling step never
runs after an incomplete daily publication.

User-service keeps subscription/inbox/delivery domain and persistence in
`core`, authenticated subscription/inbox HTTP in `app`, and delivery execution
in a separate `batch` composition root. The batch calls property-data over HTTP
and does not receive property database credentials, OAuth client secrets, or a
JWT private key.

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

# Infrastructure and Environment


## Fixed Paths

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Target repository: `/Users/gwongwangjae/home-search`

## Source Infrastructure

The source backend includes:

- `docker-compose.yml`
- `docker-compose-prod.yml`
- `docker-compose-batch.yml`
- `Dockerfile`
- `prometheus.yml`
- Spring profiles:
  - `local`
  - `prod`
  - `batch`

The local database image is:

- `postgis/postgis:16-3.4`

## Required Infrastructure

Home Search needs:

- PostgreSQL with PostGIS.
- API application runtime.
- Frontend application runtime.
- Flyway migration execution.
- RTMS public data API access.

Optional but recommended:

- Prometheus endpoint.
- Grafana dashboard.
- Batch execution logs.
- Redis for short-lived map marker response caching.

## Required Backend Environment

Home Search backend collection and map display need:

- `DB_HOST` or JDBC URL equivalent.
- `DB_PASSWORD`
- `COORDINATE_SOURCE_DB_JDBC_URL` for read-only PNU coordinate lookup.
- `COORDINATE_SOURCE_DB_USERNAME`
- `COORDINATE_SOURCE_DB_PASSWORD`
- `HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=false` for marker-display
  validation; set it to `true` only for storage-only experiments.
- `APT_SERVICE_KEY`
- `BLD_SERVICE_KEY` if building data enrichment is included in the current scope.
- `ODC_SERVICE_KEY` if complex reference enrichment is included in the current scope.
- `VW_SERVICE_KEY` if GIS/building data calls are included in the current scope.
- `JWT_SECRET` only if authenticated endpoints are enabled.
- `FRONTEND_URL`
- `ADMIN_COORDINATE_ACCESS_CODE` when coordinate override admin is enabled.
- `HOME_MAP_MARKER_CACHE_ENABLED=true` when Redis-backed map marker caching is
  enabled.
- `HOME_MAP_MARKER_CACHE_TTL`, for example `5m`, to bound stale marker data.
- `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT` when marker caching is
  enabled outside the local Docker network.

Authentication can remain outside the core map-display path unless a later
work item explicitly brings authenticated endpoints into scope.

## Required Frontend Environment

The source frontend uses:

- `VITE_API_SERVER_IP`
- `VITE_APP_SURFACE=public|admin`; omit or set `public` for the public map
  frontend. Set `admin` only for the admin coordinate frontend runtime.

Home Search target frontend should keep an equivalent API base URL variable. The name can
stay the same during migration to reduce risk.

## Flyway Strategy

Separate project baselines from later-scope migrations.

Project baseline:

- region
- parcel
- complex
- trade
- raw trade ingest
- failed trade match tracking
- PostGIS extension and indexes

later-scope:

- rankings
- top price and top volume tables
- trade trend tables
- mail target tables
- alarm indexes

## Monitoring

Minimum project metrics/logs:

- Trade ingest read count.
- Raw saved count.
- Normalized inserted count.
- Duplicate count.
- Failed match count.
- Parse failure count.
- API error rate for map endpoints.

The source backend already has actuator/prometheus dependencies. Preserve that
capability when moving to `apps/api`.

## Acceptance Criteria

- Local PostGIS can start.
- API can connect to the database.
- Local Redis can start and respond to `redis-cli ping`.
- Flyway can create baseline tables from scratch.
- Frontend can call the API through its env base URL.
- Ingest logs show read, inserted, duplicate, and failed counts.

## Local Flyway History

Durable local databases can contain seed migrations from an older local runtime,
for example `sample home search data`. Local API startup should not require a
Flyway `repair` just because that historical seed file is no longer present in
the repository.

The local profile uses:

```text
SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:missing
```

This is a local runtime guard only. Do not use it as a substitute for reviewing
production migration history, and do not run Flyway `repair` or delete local
database state without explicit approval.

## Local Redis

`infra/docker-compose.local.yml` includes a local Redis service for short-lived
map marker response caching.

- Container name: `home-search-redis`.
- Docker network address: `redis:6379`.
- Host address: `localhost:${HOME_SEARCH_REDIS_PORT:-16379}`.
- Healthcheck command: `redis-cli ping`.

The local `api` service receives Redis connection variables by default:

```text
HOME_MAP_MARKER_CACHE_ENABLED=false
HOME_MAP_MARKER_CACHE_TTL=5m
SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379
```

Marker response caching remains opt-in. To run the local API with Redis-backed
marker caching enabled:

```bash
HOME_MAP_MARKER_CACHE_ENABLED=true docker compose -f infra/docker-compose.local.yml up -d redis api
```

To verify Redis itself:

```bash
docker compose -f infra/docker-compose.local.yml up -d redis
docker exec home-search-redis redis-cli ping
```

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

## V1 Required Infrastructure

V1 needs:

- PostgreSQL with PostGIS.
- API application runtime.
- Frontend application runtime.
- Flyway migration execution.
- RTMS public data API access.

Optional but recommended:

- Prometheus endpoint.
- Grafana dashboard.
- Batch execution logs.

## Required Backend Environment

V1 backend collection and map display need:

- `DB_HOST` or JDBC URL equivalent.
- `DB_PASSWORD`
- `APT_SERVICE_KEY`
- `BLD_SERVICE_KEY` if building data enrichment is migrated in V1.
- `ODC_SERVICE_KEY` if complex reference enrichment is migrated in V1.
- `VW_SERVICE_KEY` if GIS/building data calls are migrated in V1.
- `JWT_SECRET` only if authenticated endpoints are enabled.
- `FRONTEND_URL`

For V1, authentication can remain outside the core map-display path.

## Required Frontend Environment

The source frontend uses:

- `VITE_API_SERVER_IP`

V1 target frontend should keep an equivalent API base URL variable. The name can
stay the same during migration to reduce risk.

## Flyway Strategy

Separate V1 migrations from V2 migrations.

V1:

- region
- parcel
- complex
- trade
- raw trade ingest
- failed trade match tracking
- PostGIS extension and indexes

V2:

- rankings
- top price and top volume tables
- trade trend tables
- mail target tables
- alarm indexes

## Monitoring

Minimum V1 metrics/logs:

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
- Flyway can create V1 tables from scratch.
- Frontend can call the API through its env base URL.
- Ingest logs show read, inserted, duplicate, and failed counts.

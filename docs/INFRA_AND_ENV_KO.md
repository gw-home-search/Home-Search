# Infrastructure and Environment

## Fixed Paths

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Target repository: `/Users/gwongwangjae/home-search`

## Source Infrastructure

source backend에는 다음이 포함된다:

- `docker-compose.yml`
- `docker-compose-prod.yml`
- `docker-compose-batch.yml`
- `Dockerfile`
- `prometheus.yml`
- Spring profiles:
  - `local`
  - `prod`
  - `batch`

local database image:

- `postgis/postgis:16-3.4`

## V1 Required Infrastructure

V1에는 다음이 필요하다:

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

V1 backend collection과 map display에는 다음이 필요하다:

- `DB_HOST` 또는 JDBC URL equivalent.
- `DB_PASSWORD`
- `APT_SERVICE_KEY`
- building data enrichment가 V1에 migrate되면 `BLD_SERVICE_KEY`.
- complex reference enrichment가 V1에 migrate되면 `ODC_SERVICE_KEY`.
- GIS/building data calls가 V1에 migrate되면 `VW_SERVICE_KEY`.
- authenticated endpoints가 enabled일 때만 `JWT_SECRET`.
- `FRONTEND_URL`

V1에서는 authentication이 core map-display path 밖에 남아 있을 수 있다.

## Required Frontend Environment

source frontend는 다음을 사용한다:

- `VITE_API_SERVER_IP`

V1 target frontend는 equivalent API base URL variable을 유지해야 한다. migration risk를 줄이기 위해 name은 그대로 둘 수 있다.

## Flyway Strategy

V1 migrations와 V2 migrations를 분리한다.

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

source backend에는 이미 actuator/prometheus dependencies가 있다. `apps/api`로 이동할 때 그 capability를 보존한다.

## Acceptance Criteria

- Local PostGIS가 start할 수 있다.
- API가 database에 connect할 수 있다.
- Flyway가 V1 tables를 fresh 상태에서 만들 수 있다.
- Frontend가 env base URL을 통해 API를 호출할 수 있다.
- Ingest logs가 read, inserted, duplicate, failed counts를 보여준다.

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
- Loki log aggregation for local runtime logs.
- Batch execution logs.
- Redis for short-lived map marker response caching.

## Required Property-data Environment

Home Search backend collection and map display need:

- `DB_HOST` or JDBC URL equivalent.
- `DB_PASSWORD`
- `COORDINATE_SOURCE_DB_JDBC_URL` for read-only PNU coordinate lookup.
- `COORDINATE_SOURCE_DB_USERNAME`
- `COORDINATE_SOURCE_DB_PASSWORD`
- `HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=false` for marker-display
  validation; set it to `true` only for storage-only experiments.
- `APT_SERVICE_KEY`
- `SPRING_BATCH_JOB_NAME` for run-and-exit batch execution
  (`rtmsDailyRefreshJob`, `rtmsBackfillJob`, `complexOdcMetadataGapFillJob`, or
  `complexBuildingMetadataJob`).
- `BLD_SERVICE_KEY` if building data enrichment is included in the current scope.
- `ODC_SERVICE_KEY` if complex reference enrichment is included in the current scope.
- `VW_SERVICE_KEY` if GIS/building data calls are included in the current scope.
- `FRONTEND_URL`
- `HOME_MAP_MARKER_CACHE_ENABLED=true` when Redis-backed map marker caching is
  enabled.
- `HOME_MAP_MARKER_CACHE_TTL`, for example `5m`, to bound stale marker data.
- `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT` when marker caching is
  enabled outside the local Docker network.

Property-data receives neither admin database credentials nor an internal
signing private key. It receives only the active/overlap internal JWT public
keys; the browser-facing legacy admin authentication path is removed.

## Required Admin-service Environment

- `ADMIN_DB_JDBC_URL`
- `ADMIN_DB_USERNAME=home_search_admin_runtime`
- `ADMIN_DB_PASSWORD`
- server Session cookie/security settings
- the internal admin-token private signing key and active `kid`
- property-data internal base URL

Admin-service receives neither `home_search` nor coordinate-source database
credentials. Its migration and ops credentials are injected only into their
run-and-exit jobs, never into the API runtime.

## Required Source-data Environment

- `SOURCE_DATA_DB_JDBC_URL`
- `SOURCE_DATA_DB_USERNAME=home_search_coordinate_migrator` for migration jobs
- `SOURCE_IMPORTER_DB_USERNAME=home_search_coordinate_importer` for imports
- `COORDINATE_READER_DB_USERNAME=home_search_coordinate_reader` for read smoke

Each role has a separate password. Property-data receives only the reader
credential.

## Required Frontend Environment

- `apps/web` is the public map app on development port `5173` and receives only
  the property-data API base configuration.
- `apps/admin/web` is an independent app on development port `5174`. It calls
  same-origin `/api/**`; `ADMIN_SERVICE_PROXY_TARGET` is development-proxy-only.
- Neither app uses a surface-switch environment flag, shares a build artifact,
  or receives the other service's base URL.
- Admin browser authentication is an HttpOnly server Session. Tokens, session
  identifiers, passwords, and access codes are not stored in Web Storage.

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

API와 Batch는 모든 profile에서 `spring.flyway.enabled=false`다. `bootRun`, API
startup, Batch startup은 migration이나 strict validation을 실행하지 않는다.
실제 schema 작업은 다음 artifact만 수행한다.

```bash
cd apps/property-data
./gradlew -q :migration:printMigrationBootJarPath --no-daemon
PROPERTY_DATA_MIGRATION_JAR=/absolute/property-data-migration.jar \
DB_JDBC_URL=jdbc:postgresql://localhost:15432/home_search \
DB_USERNAME=home_search \
DB_PASSWORD=... \
./ops/run-migration-jar.sh --operation=info
```

지원 operation:

```text
--operation=info
--operation=validate
--operation=migrate --target=6 --confirm=6 --confirm-database=home_search
--operation=migrate --target=7 --confirm=7 --confirm-database=home_search
--operation=repair-missing-v3 --confirm=3 --confirm-database=home_search
--operation=backfill-registry-trade-date --confirm-database=home_search --batch-size=20000 --sleep-millis=100
```

exit code는 성공 `0`, DB/Flyway/backfill/validation 실패 `1`, 잘못된 argument
또는 confirmation `2`다. wrapper의 마지막 명령은 `exec java -jar ... "$@"`다.
모든 operation은 연결 직후 `current_database()=home_search`를 확인한다.
mutating operation은 `--confirm-database=home_search`와
`MIGRATION_EVIDENCE_FILE`을 요구한다. wrapper는 실행 직전에 credential을
포함하지 않는 UTC timestamp, operation, Git SHA, migration JAR SHA-256을 해당
evidence 파일에 append한다. evidence 파일의 상위 directory는 미리 만들어 둔다.
V3 controlled repair는 history CSV/SQL과 schema-only dump를 먼저 만들고,
`MIGRATION_HISTORY_CSV_BACKUP_FILE`, `MIGRATION_HISTORY_SQL_BACKUP_FILE`,
`MIGRATION_SCHEMA_BACKUP_FILE`로 non-empty backup 파일을 지정해야 실행된다.

개발 중 다음 version은 `./gradlew :core:printNextApiMigrationVersion`으로 확인한다.
신규 migration은 durable DB에 명시적으로 적용하기 전까지 수정할 수 있다.
적용 직전 checksum과 Git diff를 증거로 고정하고, 적용 뒤에는 파일을 수정하지
않으며 다음 version으로 변경한다. fresh PostgreSQL 검증은 `persistenceTest`와
`:migration:test`가 담당한다.

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
capability when moving to `apps/property-data`.

Local monitoring stack:

- Prometheus scrapes `api:8080/actuator/prometheus`.
- Loki stores local Docker container logs with filesystem storage.
- Grafana Alloy reads Docker logs from the local Docker socket and forwards
  only `home-search-*` containers to Loki.
- Grafana provisions Prometheus and Loki datasources plus the
  `Home Search Local Overview` dashboard from `infra/grafana`.

Local monitoring ports are bound to host loopback by default:

- Prometheus: `localhost:${HOME_SEARCH_PROMETHEUS_PORT:-9090}`.
- Loki: `localhost:${HOME_SEARCH_LOKI_PORT:-3100}`.
- Alloy UI: `localhost:${HOME_SEARCH_ALLOY_PORT:-12345}`.
- Grafana: `localhost:${HOME_SEARCH_GRAFANA_PORT:-3000}`.

Grafana local credentials default to:

```text
HOME_SEARCH_GRAFANA_ADMIN_USER=admin
HOME_SEARCH_GRAFANA_ADMIN_PASSWORD=home_search_local_admin
```

Override these in a private local environment before exposing Grafana outside
the local machine. The Docker socket mount used by Alloy is a local-only
diagnostic surface; do not copy it to production deployment files without a
separate security review.

Monitoring labels must stay low-cardinality. Do not put `source_key`, raw
payloads, service keys, user search text, or full request query strings in
metric labels or Loki labels.

## Acceptance Criteria

- Local PostGIS can start.
- API can connect to the database.
- Local Redis can start and respond to `redis-cli ping`.
- Flyway can create baseline tables from scratch.
- Frontend can call the API through its env base URL.
- Ingest logs show read, inserted, duplicate, and failed counts.
- Prometheus can scrape `home_search_ingest_items_total`,
  `home_search_map_requests_total`, and
  `home_search_map_marker_cache_requests_total`.
- Grafana can load provisioned Prometheus and Loki datasources.
- Loki can query `home-search-api` logs without exposing secrets in labels.

## Local Flyway History

local API도 Flyway를 자동 실행하지 않으며 missing/validation 우회 환경 변수를
제공하지 않는다. durable local DB의 history 변경도 migration jar를 명시적으로
사용한다. `enabled=false`는 자동 실행만 막고 이미 적용된 migration checksum
규칙을 없애지 않는다.

현재 V3 source missing history는 일반 ignore로 숨기지 않는다.
`V1__create_clean_core_schema.sql`과 `V4__create_spring_batch_metadata_schema.sql` checksum,
failed migration 0건, V3 unresolved 단 한 건, backup을 확인한 뒤
`repair-missing-v3 --confirm=3`으로만 `Deleted` 처리한다. 예상하지 않은 aligned,
removed, deleted action이 있으면 V5/V6 적용을 중단한다.

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

## Batch Packaged Runtime

Batch 운영 실행은 Gradle `bootRun`이 아니라 `:batch:bootJar`로 만든 단일
artifact를 직접 `java -jar`로 실행한다. `bootRun`은 개발 편의용이며 OS process
exit code `0/1/2` 계약 검증에 사용하지 않는다.

```bash
cd apps/property-data
./gradlew -q :batch:printBatchBootJarPath --no-daemon
PROPERTY_DATA_BATCH_JAR=/absolute/path/from/the/previous-command \
SPRING_BATCH_JOB_NAME=rtmsDailyRefreshJob \
./ops/run-batch-jar.sh \
  runDate=2026-07-10 \
  requestId=123e4567-e89b-12d3-a456-426614174000
```

`run-batch-jar.sh`는 jar가 없거나 명시된 파일이 존재하지 않으면 `2`로
종료하며 후보를 임의 선택하지 않는다. 마지막 명령은 `exec java -jar ...`로
process를 교체하므로 signal과 Batch exit code를 그대로 전달한다.

Docker Compose smoke는 host에서 만든 같은 jar를 read-only로
`/app/property-data-batch.jar`에 mount한다.

```bash
cd apps/property-data
PROPERTY_DATA_BATCH_JAR=/absolute/path/from/printBatchBootJarPath \
BATCH_RUN_DATE=2026-07-10 \
BATCH_REQUEST_ID=123e4567-e89b-12d3-a456-426614174001 \
docker compose -f ../../infra/docker-compose.local.yml \
  -f ops/docker-compose.daily-batch-smoke.yml run --rm batch
```

ODC gap fill과 building metadata collection은 같은 packaged jar를 사용한다:

```bash
SPRING_BATCH_JOB_NAME=complexOdcMetadataGapFillJob ./ops/run-batch-jar.sh \
  runDate=2026-07-10 maxTargets=450 toComplexId=43978 \
  requestId=123e4567-e89b-12d3-a456-426614174009

SPRING_BATCH_JOB_NAME=complexBuildingMetadataJob ./ops/run-batch-jar.sh \
  mode=missing runDate=2026-07-10 maxRequests=900 \
  requestId=123e4567-e89b-12d3-a456-426614174010
```

`complex.metadata.daily-request-quota` must match the approved provider quota;
ODC는 canonical/alias 최악 조건을 반영해 `maxTargets * 2`가 quota 90% 이하여야
하고 building collection은 `maxRequests`가 quota 90% 이하여야 한다. 두 job은
같은 PostgreSQL advisory lock을 사용하므로 동시에 실행할 수 없다.

## Local Monitoring

Start the local monitoring stack without deleting volumes:

```bash
docker compose -f infra/docker-compose.local.yml up -d postgis redis api prometheus loki alloy grafana
```

Verify the main endpoints:

```bash
curl -fsS http://localhost:8080/actuator/prometheus
curl -fsS http://localhost:${HOME_SEARCH_PROMETHEUS_PORT:-9090}/-/ready
curl -fsS http://localhost:${HOME_SEARCH_LOKI_PORT:-3100}/ready
curl -fsS http://localhost:${HOME_SEARCH_GRAFANA_PORT:-3000}/api/health
```

Use `docker compose stop` or `docker compose down` without `-v` when shutting
down the local stack. Do not use `docker compose down -v` unless a current task
explicitly approves volume deletion.

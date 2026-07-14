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

All Java application artifacts target Java 21. Local property-data API/Batch,
admin-service, and user-service containers therefore use Eclipse Temurin 21.
All Java applications build on Spring Boot 4.1.0; application runtime images do
not perform implicit Flyway migration.

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

## Required User-service Environment

- `USER_DB_JDBC_URL`, `USER_DB_USERNAME=home_search_user_runtime`, and a
  runtime-only password.
- Google, Kakao, and Naver OAuth client id/secret values injected at runtime.
- user-service RS256 private key, active `kid`, issuer, audience, access TTL,
  and refresh TTL.
- public-key overlap set for verifying tokens during key rotation.

Consumer services that validate user tokens receive only the allowlisted
public-key mapping and canonical verification policy:

```dotenv
USER_JWT_ISSUER=user-service
USER_JWT_AUDIENCE=home-search-user-api
USER_JWT_MAXIMUM_LIFETIME=15m
USER_JWT_PUBLIC_KEYS=active-kid=/run/keys/user-active-public,old-kid=/run/keys/user-old-public
```

They fail at startup for empty/duplicate mappings, missing or oversized key
files, RSA keys below 2048 bits, or active/overlap `kid` collisions. New public
keys are deployed before signing switches; old keys remain for at least the
15-minute access-token lifetime. Runtime JWKS fetch is not used.

Migration jobs use `home_search_user_migrator` credentials separately. No
user-service process receives `home_search`, admin DB, or admin internal signing
credentials.

Local Compose loads user-service runtime values from
`apps/user/service/.env` through the service-level `env_file`; do not commit
that populated file. The container receives `USER_DB_JDBC_URL`,
`USER_DB_USERNAME`, key mount paths, and `SERVER_PORT` from Compose, while
`USER_DB_PASSWORD`, OAuth credentials, Origin/redirect values, cookie security,
and JWT key metadata come from the service-local file.

`HOME_SEARCH_DB_PASSWORD`, `USER_RUNTIME_DB_PASSWORD`, and
`USER_MIGRATOR_DB_PASSWORD` are Postgres bootstrap variables, not user-service
application variables. The user-service
file must therefore contain the same value as `USER_DB_PASSWORD`. Compose has no
repository-known default for the cluster superuser or either user database role:
export three distinct values before `docker compose` and keep the runtime value synchronized with
`USER_DB_PASSWORD`. Service-level `env_file` values are intentionally not shared
with the Postgres container because that would also expose OAuth credentials to
it. PostgreSQL binds to host loopback only, and runtime services mount only their
own artifact or application directory rather than the repository root.

## Required AI-service Environment

- ai-service database credentials limited to `ai` ownership and `ai_read`
  `SELECT`.
- user JWT public keys plus exact user issuer/audience values.
- LLM and legal-source credentials injected only when those adapters are
  enabled.

Tests run with stub OAuth/LLM/legal providers and require no live secret.

## Required Frontend Environment

- `apps/web` is the public map app on development port `5173`. It receives
  `VITE_API_SERVER_IP` for property-data and the separate
  `VITE_USER_API_SERVER_IP` for optional OAuth/current-user calls.
- Production must set `VITE_USER_API_SERVER_IP` explicitly. Local/test may use
  `http://localhost:8082`; OAuth development uses the exact frontend origin
  `http://localhost:5173`, not `127.0.0.1:5173`.
- `apps/admin/web` is an independent app on development port `5174`. It calls
  same-origin `/api/**`; `ADMIN_SERVICE_PROXY_TARGET` is development-proxy-only.
- Neither app uses a surface-switch environment flag, shares a build artifact,
  or receives the other app's service credentials.
- Public web OAuth keeps its access JWT memory-only and its refresh token in the
  user-service HttpOnly cookie. Admin browser authentication is an HttpOnly
  server Session. Tokens, session identifiers, passwords, and access codes are
  not stored in Web Storage.

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
실제 schema 작업은 pinned official Flyway container만 수행한다.

```bash
cd apps/property-data
PROPERTY_MIGRATOR_JDBC_URL=jdbc:postgresql://localhost:15432/home_search \
PROPERTY_MIGRATOR_DB_USERNAME=home_search_property_migrator \
PROPERTY_MIGRATOR_DB_PASSWORD=... \
./ops/property-flyway.sh info
```

지원 interface:

```text
property-flyway.sh info|validate|migrate <numeric-target>
```

wrapper는 expected database와 최고 pending version을 확인하고 `latest`, `clean`,
`repair`, `baseline`, 임의 option을 거부한다. mutation evidence에는 credential을
제외한 timestamp, service, target, pinned image, Git SHA만 기록한다.

Property-data deployment도 fresh-only다.

```bash
./ops/property-deployment-preflight.sh before 8
./ops/property-flyway.sh migrate 8
./ops/property-deployment-preflight.sh after 8
./ops/property-flyway.sh validate
```

User-service는 application runtime과 분리된 official Docker Flyway CLI만
사용한다. `core`/`app` artifact에는 Flyway dependency와 migration SQL이 없다.

```bash
cd apps/user/service
USER_MIGRATOR_JDBC_URL=jdbc:postgresql://localhost:15432/home_search_user \
USER_MIGRATOR_DB_USERNAME=home_search_user_migrator \
USER_MIGRATOR_DB_PASSWORD=... \
./ops/user-flyway.sh validate
```

`user-flyway.sh`는 `info`, `validate`, 숫자 target이 필수인 `migrate`만
제공하고 `latest`, `clean`, `repair`, `baseline`, 임의 option을 거부한다.
`redgate/flyway:12.4.0`과 read-only SQL/conf mount를 사용하며 migrator
credential은 user-service runtime container에 전달하지 않는다.

User-service deployment는 fresh-only다.

```bash
./ops/user-deployment-preflight.sh before 5
./ops/user-flyway.sh migrate 5
./ops/user-deployment-preflight.sh after 5
./ops/user-flyway.sh validate
```

preflight는 pinned PostgreSQL client의 read-only catalog probe로
`current_database()`와 service relation/history를 확인한다. `before`는 empty
database만, `after`는 migration versions 1 through 5가 각각 정확히 한 건의 `SQL`/`Success`이고
`validate -outputType=json`이 성공한 경우만 허용한다. snapshot, JDBC,
Baseline, Deleted, Out of Order, Missing, Ignored, duplicate, failed history는
exit `2`로 중단하며 credential은 stdout/stderr/evidence에 기록하지 않는다.

개발 중 다음 version은 `./gradlew printNextApiMigrationVersion`으로 확인한다.
신규 migration은 durable DB에 명시적으로 적용하기 전까지 수정할 수 있다.
적용 직전 checksum과 Git diff를 증거로 고정하고, 적용 뒤에는 파일을 수정하지
않으며 다음 version으로 변경한다. fresh PostgreSQL 검증은 `persistenceTest`와
pinned Docker CLI smoke가 담당한다.

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
제공하지 않는다. `enabled=false`는 자동 실행만 막고 이미 적용된 migration
checksum 규칙을 없애지 않는다.

local migration version 2 Java→SQL cutover는 완료됐고 executable repair script는
제거됐다. sanitized evidence는 timestamp `2026-07-13T10:51:35Z`, image
`redgate/flyway:11.7.2`, Git SHA `a65fe6dd...`, SQL checksum `599267940`,
business fingerprint `b49e9afa...03fb4`, schema fingerprint
`f4354488...d1b0`, validate `success`다. ignored
`.migration-backup/20260713T104859Z-v2-history-cutover` backup은 local에 보존한다.
현재 local DB의 JDBC/Deleted audit history는 fresh-only deployment preflight에서
거부되는 것이 정상이며 일반 `info`/`validate`만 허용한다.

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

## Kakao Nearby-place Gateway

Kakao REST credentials are server-only. Never add `KAKAO_REST_API_KEY` to a
`VITE_*` variable, fixture, log, image layer, or committed override file.

```text
KAKAO_REST_API_KEY=
HOME_PLACE_KAKAO_ENABLED=false
HOME_PLACE_KAKAO_CACHE_ENABLED=true
HOME_PLACE_KAKAO_CACHE_TTL=24h
HOME_PLACE_KAKAO_DAILY_REQUEST_BUDGET=10000
HOME_PLACE_KAKAO_CONNECT_TIMEOUT=1s
HOME_PLACE_KAKAO_READ_TIMEOUT=2s
```

The feature is disabled by default. Enabling it requires a Kakao app with Local
API use enabled, Redis connectivity, and a deployment-time daily budget below
the provider quota. Public ingress must apply the equivalent of the checked-in
per-IP `5r/s`, burst `10` nearby-place route limit; the local gateway returns
`429` above it. Redis cache read/write failures may degrade to a provider call,
but an unavailable quota guard fails closed.

Category cache keys contain six-decimal canonical coordinates, radius, and
category only. TTL is 24 hours, empty results are cached, provider failures are
not cached, and no place result is written to PostgreSQL.

Prometheus exposes bounded tags only:

- `home_search_nearby_place_cache_requests_total`
- `home_search_kakao_local_calls_total`
- `home_search_kakao_local_duration_seconds`
- `home_search_kakao_local_quota_used`

Never use complex id, coordinate, place name, raw provider body, query URL, or
authorization material as a metric tag or log field.

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

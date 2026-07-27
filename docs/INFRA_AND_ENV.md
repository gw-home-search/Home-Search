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

## ML Prediction Runtime

`apps/ml/Dockerfile` builds the F37 FastAPI runtime once from the exact
`requirements.lock` graph. Container startup does not run `pip install` and does
not mount application source. The model remains a deployment artifact outside
Git and outside the image:

```text
host F37_ARTIFACT_DIR
  -> /model:ro
  -> non-root UID/GID 10001:10001
```

The entrypoint fails before Uvicorn starts when `keras_model.keras` is missing
or unreadable. The Compose healthcheck calls `/health`, which also loads the
remaining metadata/schema/model files. The property API intentionally has no
Compose `depends_on` edge to ML, so a model deployment failure does not block
the map/API process from starting.

Build and verify locally:

```bash
docker build --tag home-search-ml:local apps/ml
docker run --rm --entrypoint python home-search-ml:local -m pip check

F37_ARTIFACT_DIR=/path/to/best_price_deployment_attempt \
  docker compose -f infra/docker-compose.local.yml up -d --build ml
```

CI runs the image gate when `apps/ml/**`, the local Compose definition, or the
CI workflow changes. It checks the exact dependency graph, runtime imports,
non-root identity, model exclusion, and missing-model fail-fast behavior. The
Python base tag is intentionally kept on the existing `3.10-slim` line in this
slice; registry digest pinning remains a separate supply-chain decision.

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
- `VITE_KAKAO_MAP_APP_KEY` for the browser Kakao SDK. Local Compose reads this
  browser-safe key from `apps/web/.env` only for the Web service; a blank value
  stops the Web container without printing the value and does not block
  backend-only Compose commands.
- `HOME_MAP_MARKER_CACHE_ENABLED=true` when Redis-backed map marker caching is
  enabled.
- `HOME_MAP_MARKER_CACHE_TTL`, for example `5m`, to bound stale marker data.
- `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT` when marker caching is
  enabled outside the local Docker network.
- `HOME_INSIGHT_TRADE_ENABLED=false` for trade insight snapshot generation.
- `HOME_NEWS_NAVER_ENABLED=false` remains the default.
- `NAVER_NEWS_API_KEY_ID` and `NAVER_NEWS_API_KEY` are the existing local
  names for a NAVER Search credential. Its product is selected explicitly by
  provider mode. The values are injected only into property Batch and must
  never be logged or supplied to API/Web.
  `HOME_NEWS_NAVER_CLIENT_ID` and `HOME_NEWS_NAVER_CLIENT_SECRET` remain
  explicit deployment aliases.
- `HOME_NEWS_NAVER_PROVIDER_MODE=API_HUB` is the production default and uses
  the API HUB endpoint and NCP API Gateway headers. The local acceptance runner
  uses `DEVELOPERS` with `https://openapi.naver.com/v1/search/news.json` when
  the existing `NAVER_NEWS_API_KEY_ID`/`NAVER_NEWS_API_KEY` pair is injected.
- Production Property Batch calls NAVER API HUB at
  `https://naverapihub.apigw.ntruss.com/search/v1/news` with the
  `X-NCP-APIGW-API-KEY-ID` and `X-NCP-APIGW-API-KEY` headers. Legacy NAVER
  Developers credentials and `openapi.naver.com` are not compatible with this
  adapter.
- `HOME_NEWS_PUBLIC_ENABLED=true` exposes the read-only API after publication
  readiness; setting it to `false` disables both news controllers without
  stopping collection.
- `VITE_MARKET_NEWS_ENABLED=true` is the browser build-time rollback switch.
  Setting it to `false` hides the news navigation/detail section and redirects
  `/insights/news` to the map.
  Release builds pass the reviewed `MARKET_NEWS_ENABLED` value into this flag,
  record it as `build_flags.market_news_enabled`, and staging deployment rejects
  a mismatch with `enable_market_news_public`.
- `HOME_NEWS_DAILY_CALL_BUDGET=4000`, `HOME_NEWS_CACHE_ENABLED=true`,
  `HOME_NEWS_CACHE_TTL=31d`, `HOME_NEWS_CONNECT_TIMEOUT=2s`, and
  `HOME_NEWS_READ_TIMEOUT=5s` bound provider/cache behavior. The call budget is
  enforced across all executions on the same KST date using the lowest budget
  recorded that day, so operators can lower it without a code change.
- Terraform defines fail-closed EventBridge Scheduler targets for
  `marketNewsGeneralJob` at KST 00:30/12:30/18:30,
  `marketNewsMorningJob` at 06:30, `marketNewsMajorSelectionJob` Monday 05:30,
  and `marketNewsRetentionJob` at 20:30. `marketNewsMorningJob` runs general
  collection and then major-complex collection as one restartable chain.
  `enable_market_news_schedules=true` is set only after credentials, migration,
  quota, and quality readiness checks pass. The API app has no news scheduler.
- `enable_market_news_public` is independent from schedule enablement and maps
  to `HOME_NEWS_PUBLIC_ENABLED`; collection can continue while the public
  surface stays disabled.
- A failed human quality review is applied with `marketNewsWithdrawalJob`
  using `--snapshotId={canonical-uuid}` and one stable
  `MarketNewsWithdrawalReason`. It changes only the current pointer; PostgreSQL
  and Redis last-good evidence remain available as `STALE`.
- `marketNewsQualitySampleJob --reviewSetId={canonical-uuid}
  --policyVersion=NEWS_V5` stores a deterministic review set. Missing category,
  SIDO, relation, challenge, or URL minima are recorded as
  `INSUFFICIENT_SAMPLE` rather than treated as a pass.
- `ops/market_news_quality_review.py export` writes the private title,
  description, and URL worksheet only outside the repository with mode `0600`.
  `import --dry-run` validates membership and input without saving labels;
  `import` stores an identified human review. `report --checkpoint
  immediate|24h|7d` writes aggregate-only evidence and returns nonzero for
  insufficient samples, missing labels, elapsed-time gaps, insufficient normal
  runs, or failed precision thresholds. The 24-hour and 7-day checks require at
  least 4 and 28 healthy general collections respectively. Only `GENERAL`
  executions in `COMPLETED` state with zero truncated, failed, and
  budget-skipped work units count; bootstrap does not count.
- The initial 30-day collection uses
  `marketNewsGeneralJob --requestId=BOOTSTRAP:{canonical-uuid}`. Normal runs
  keep the repository-wide canonical UUID request-id contract.
- Local bootstrap acceptance uses
  `apps/property-data/ops/run-local-market-news-e2e.sh`. It receives the NAVER
  credential and database password only from the invoking process, runs major
  selection/general bootstrap/major-complex/retention in order, and records
  aggregate DB/Redis/API evidence without copying provider title, description,
  or URL values into repository evidence files.

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
- `HOME_USER_DIGEST_ENABLED=false` for the user batch and
  `HOME_USER_EMAIL_ENABLED=false` for SES delivery. The user batch receives the
  property API base URL and SES configuration, but no OAuth client secret or
  JWT signing private key.

Consumer services that validate user tokens receive only the allowlisted
public-key mapping and canonical verification policy:

```dotenv
USER_JWT_ISSUER=user-service
USER_JWT_AUDIENCE=home-search-user-api
USER_JWT_MAXIMUM_LIFETIME=15m
USER_JWT_PUBLIC_KEYS=active-kid=/run/keys/user-active-public,old-kid=/run/keys/user-old-public
```

Production-enabled consumers fail at startup for empty/duplicate mappings,
missing or oversized key files, RSA keys below 2048 bits, or active/overlap
`kid` collisions. Slice 1의 route 미연결 AI/BFF skeleton만 health 확인을 위해
empty mapping으로 기동할 수 있고, 이 경우 모든 chatbot 요청을 fail-closed로
거부한다. 실제 route 활성화 전에는 non-empty mapping preflight가 필수다. New public
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

The property database bootstrap also requires `AI_PROPERTY_READER_DB_PASSWORD`.
It creates the independent `home_search_ai_reader` login with `NOINHERIT` and no
role-management privileges. This login can connect only to `home_search`, can
`SELECT` only the explicitly granted `ai_read.complex_fact`,
`ai_read.trade_fact`, and `ai_read.region_fact` views, and receives no default
privilege for future views.
Keep this value distinct from property runtime, migrator, user, admin, and
Postgres bootstrap credentials.

The same protected `apps/property-data/.env` bootstrap file must define three
additional, distinct values without repository-known defaults:

```dotenv
AI_DATA_MIGRATOR_DB_PASSWORD=
AI_DATA_IMPORTER_DB_PASSWORD=
AI_DATA_RUNTIME_DB_PASSWORD=
```

Fresh PostgreSQL volumes create `home_search_ai` and the
`home_search_ai_migrator`, `home_search_ai_importer`, and
`home_search_ai_runtime` `NOINHERIT` roles during normal init. Existing
volumes are upgraded without deletion or recreation by the idempotent command
below. It passes password values through inherited Docker exec environment
entries, not command arguments, and applies only the AI database boundary:

```bash
export AI_DATA_MIGRATOR_DB_PASSWORD
export AI_DATA_IMPORTER_DB_PASSWORD
export AI_DATA_RUNTIME_DB_PASSWORD
infra/postgres/bootstrap-ai-database.sh
```

Do not run this command until the three variables contain protected local
values. It never deletes a database, volume, publication, or raw object.

Local raw storage uses a dedicated persistent MinIO volume. `apps/ai/.env`
must keep MinIO administration credentials distinct from the importer S3
credentials:

```dotenv
HOME_AI_MINIO_ROOT_USER=
HOME_AI_MINIO_ROOT_PASSWORD=
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
HOME_AI_RAW_S3_BUCKET=
HOME_AI_RAW_S3_PREFIX=raw
HOME_AI_RAW_S3_REGION=ap-northeast-2
HOME_AI_RAW_S3_ENDPOINT=http://minio:9000
```

`minio-init` creates a private, versioned, object-locked bucket and attaches an
importer policy limited to `s3:GetObject` and `s3:PutObject` under `raw/*`.
The chatbot runtime receives none of these values. Never delete or recreate the
MinIO volume to repair configuration.

## Required AI-service Environment

- ai-service uses `home_search_ai_reader` for `home_search.ai_read` `SELECT` and
  separate credentials for `home_search_ai`; neither credential may be reused by
  property-data runtime or migration jobs.
- user JWT public keys plus exact user issuer/audience values.
- LLM and legal-source credentials injected only when those adapters are
  enabled.

Tests run with stub OAuth/LLM/legal providers and require no live secret.

Local chatbot runtime is an explicit opt-in overlay. Prepare four separate
runtime variable files for property bootstrap values, user-service values, BFF
public-key mapping, and AI DSN/public-key mapping. The repository-local default
paths are `apps/property-data/.env`, `apps/user/service/.env`,
`apps/chat-bff/.env`, and `apps/ai/.env`. Start it only through:

```bash
infra/chatbot/run-local-chatbot.sh
```

The existing four-path form remains available when an operator intentionally
uses non-default runtime files. Do not put secret values directly on the command
line.

For the no-argument local path, an explicit `HOME_SEARCH_DB_PASSWORD` takes
precedence. Otherwise the runner accepts the existing property application pair
`DB_USERNAME=home_search` and `DB_PASSWORD` as the bootstrap login; it never
reuses `DB_PASSWORD` when the username is a runtime role. The property runtime
password uses the existing local Compose default unless explicitly supplied,
and the user runtime password comes from the user-service file's
`USER_DB_PASSWORD`. Migrator, AI property reader, AI dataset
migrator/importer, and AI reference runtime passwords remain separate roles.

Use `apps/chat-bff/local-runtime.example` and `apps/ai/local-runtime.example`
as placeholder-only templates. The runner parses assignments without sourcing
the files, never prints their values, and rejects missing/duplicate variables,
placeholder values, an invalid RSA pair, inconsistent `kid` mappings, mismatched
user runtime DB passwords, and an AI reader DSN that does not match the
dedicated reader password. The no-argument path derives BFF/AI public-key
mappings from `USER_JWT_ACTIVE_KID`, percent-encodes the AI reader password into
the fixed local DSN, derives the separate `home_search_ai_runtime` reference
DSN from `AI_DATA_RUNTIME_DB_PASSWORD`, and supplies the approved cumulative
`complex_identity,recent_trade_lookup,price_trend,recommendation,comparison` allowlist when that
optional line is absent. The four-path form keeps strict explicit mapping,
DSN, and Capability validation. The approved reference rollback is blank and
the 2026-07-21 approved values are exactly `academy_lookup`, the cumulative
`academy_lookup,rail_station_lookup`, the cumulative
`academy_lookup,rail_station_lookup,school_location`, and the full cumulative
`academy_lookup,rail_station_lookup,school_location,retail_location`. Any other combination,
including rail or school alone, fails closed until its own readiness and
activation commit pass.

The runner requires the existing `home-search-postgis` and `home-search-redis`
containers to be healthy and `home-search-api` to be running. It then uses
Compose `--no-deps` so chatbot startup cannot recreate the base data services.
After all base-container and Compose preflight checks pass, it runs the
idempotent AI-only bootstrap immediately before chatbot startup. This supports
existing volumes without `down -v`, volume deletion, or container recreation.
The targeted user/AI/BFF/gateway containers use `--force-recreate` so a rebuilt
mounted BFF artifact is loaded without touching Postgres or Redis. The local BFF
timeout is a finite `70s`. The AI total query budget accepts `1..60s` and defaults
to `45s`, so the validated local `60s` maximum still ends before the BFF timeout.
An operator must keep the BFF timeout greater than the AI query budget.

The AI adapter additionally requires `HOME_AI_OPENAI_API_KEY`, explicit
`HOME_AI_OPENAI_PRIMARY_MODEL` and `HOME_AI_OPENAI_SECONDARY_MODEL` IDs, and an
optional `HOME_AI_OPENAI_TIMEOUT_SECONDS` in the range `1..30` with default `30`.
`HOME_AI_QUERY_TIMEOUT_SECONDS` bounds the complete plan, repository, and draft
flow to `1..60s`; invalid values fail closed. Answer-first execution reserves
the final five seconds of this budget for deterministic response assembly and
grounding validation.
`HOME_AI_DEPLOYMENT_TIER=local|offline|staging|production` is required whenever
the supervisor graph mode is not `off`; a missing or unknown tier fails closed.
`HOME_AI_SUPERVISOR_GRAPH_MODE=off|shadow|canary|active` defaults to `off`.
`HOME_AI_SUPERVISOR_GRAPH_CANARY_PERCENT` accepts `0..100` and defaults to `0`.
`shadow` requires an explicit `offline` or `staging` tier and is capped at 5%;
`local`, missing-tier, and production `shadow` fail closed. Production `canary` hashes the authenticated subject with fixed
policy `supervisor-graph-v1` so a request runs exactly one engine. The graph
uses no checkpointer/store and its state, question, entity, tool arguments, and
answer are not metric labels or log fields.
The chatbot overlay enables `HOME_AI_AGENTIC_ORCHESTRATION_ENABLED` and
`HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED` independently by default. Set either to
the exact value `false` to return recommendations to the v1 maintenance path or
to remove the official web tool without a migration rollback. Agent execution
reserves the final eight seconds of the total query budget for final generation
and grounding validation. Runtime metrics contain route/repair/secondary/fallback,
bounded tool and web counts, latency, token usage, response byte counts, and a
grounding rejection category; they never contain the question, answer, context,
tool arguments, tool results, or web query.
`HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED` and
`HOME_AI_PROPERTY_OVERVIEW_ENABLED`, `HOME_AI_SEMANTIC_GOAL_PLANNER_ENABLED`,
`HOME_AI_DEPENDENT_WORKFLOW_ENABLED`, `HOME_AI_DECISION_REPORT_ENABLED`, and
`HOME_AI_ARTIFACT_V2_ENABLED` default to `true`; set one to the exact value
`false` for its independent rollback path. Disabling the report preserves the
legacy answer and removes candidate profile artifacts; disabling artifact v2
removes v2 tables and any report that references them. Any other non-empty
boolean value fails closed. `HOME_AI_ANSWER_FIRST_FALLBACK_CAPABILITIES` optionally limits degraded
observation, deterministic draft, and quality-gate recovery to a comma-separated
subset of canonical chatbot capability names. It defaults to every capability;
an empty or unknown set disables capability fallback rather than silently
enabling it.
The accepted runtime Capability values are the identity-only rollback value
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity` and the approved
cumulative value
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup`.
The price/trend rollback value is
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend`.
The approved full cumulative value is
`HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend,recommendation,comparison`.
The previous value ending in `recommendation` is the comparison rollback.
This activation runs explicit `ACADEMY`, `TRANSIT`, `SCHOOL`, and `SHOPPING`
criteria. The full reference value also enables `BUDGET` recommendation. Retail
results use only coordinate-confirmed official rows and retain the partial-coverage
limitation. Childcare and kindergarten remain unavailable until their
source-specific readiness and activation commits pass.
The no-argument local runner supplies the full cumulative value when omitted;
explicit custom-file startup still rejects a missing value. Reordered,
duplicate, mixed, or unapproved values remain fail-closed.
The local runner requires the API key and two distinct model IDs, validates them
without printing their values, and injects them only into the AI container through
the chatbot Compose overlay. A live provider smoke still requires explicit approval;
without valid runtime values the runner stops before Compose starts.

Property chatbot activation uses the packaged golden CLI before changing the
Capability Registry. Offline mode reads the production `ai_read` views through
`home_search_ai_reader` and makes no provider request:

```bash
apps/ai/ops/run-local-property-golden.sh offline
```

Live mode is not a general batch command. It rejects zero or multiple
`--case-id` values and requires
`HOME_AI_GOLDEN_LIVE_CONFIRM=RUN_ONE_LIVE_GOLDEN_CASE`. Under the current retry
policy one live case has a provider request upper bound of six. Neither mode
prints prompt/answer text, credentials, provider response bodies, or exception
details. Do not place the confirmation variable in a committed env file or a
long-lived Compose runtime; set it only for the separately approved command.
DSN과 provider secret도 command argument나 shell history에 직접 입력하지 않고
보호된 runtime secret injection으로 제공한다.
로컬 전용 실행기는 기본적으로 `apps/ai/.env`에서 필요한 exact key만 읽고 파일을
source하지 않는다. 해당 파일은 regular non-symlink file이어야 하며 group/other
권한을 제거한 `600` 권한이어야 한다. 승인된 live 1건은 다음 명령으로만 실행한다.

```bash
HOME_AI_GOLDEN_LIVE_CONFIRM=RUN_ONE_LIVE_GOLDEN_CASE \
  apps/ai/ops/run-local-property-golden.sh live \
  --case-id budget-recommendation-songpa-84-retail
```

The overlay is `infra/docker-compose.chatbot.yml`. Omitting that file leaves the
existing property stack and public gateway unchanged. Including it replaces the
gateway template with the exact JSON/SSE chatbot routes while retaining the
existing property routes and blocked `/internal`, `/api/v1/admin`, and actuator
surfaces.

The signed authentication transport can be verified without local credentials:

```bash
infra/chatbot/test-signed-jwt-e2e.sh
```

This test creates an ephemeral RSA pair and a five-minute user JWT, then verifies
gateway -> BFF -> AI JSON/SSE, wrong-issuer rejection, and the existing property
route. It mounts a test-only AI engine, so a pass proves authentication and
transport wiring but does not claim live LLM provider readiness.

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

Property-data deployment의 기본 경로는 fresh-only다.

```bash
./ops/property-deployment-preflight.sh before 21
./ops/property-flyway.sh migrate 21
./ops/property-deployment-preflight.sh after 21
./ops/property-flyway.sh validate
```

V17 grants `home_search_property_runtime` the minimum insight-table privileges:
`SELECT`, `INSERT`, and `UPDATE`. It intentionally does not grant `DELETE`.

V18 adds weekly snapshot-to-execution lineage, allows the additive
`WEEKLY_NEW_TRADE` metric, and grants the same non-delete privileges on the
lineage table.

V19 adds structured RTMS registration/cancellation dates, rolling seven-day
period/item evidence, quality counters, and `SUPERSEDED` replacement lineage.
Existing V15-V18 checksums remain unchanged. The DAILY RTMS lookback default is
`2`, so the current trade month and previous two trade months are planned; the
work-unit total is derived from the resolved region and month lists rather than
a fixed count.

For a credential-injected local end-to-end run, use
`apps/property-data/ops/run-local-market-insight-e2e.sh`. The runner refuses a
non-empty `HOME_INGEST_RTMS_DAILY_LAWD_CDS`, validates Flyway before provider
work, verifies complete DAILY nationwide coverage, then requires 18 published
`ROLLING_7D` scopes from the same source execution. The packaged
`rtmsDailyRefreshJob` runs daily insight followed by rolling insight only after
its ingest and region-sync steps succeed; a rejected daily insight result also
fails the job before rolling publication. It writes only request ids and
non-secret counts under ignored
`tmp/market-insight-e2e/`. Recoverable provider failures are retried with the
same RTMS request id and an increasing internal `restartAttempt`; completed work
units are preserved and only incomplete units run again. The bounded attempt
count defaults to 5 and can be set from 1 through 5 with
`MARKET_INSIGHT_E2E_MAX_DAILY_ATTEMPTS`. `MARKET_INSIGHT_E2E_PSQL_DSN` must omit
embedded passwords; the runner uses the separately injected `DB_PASSWORD` for
`psql`.

### Completed local legacy V9 activation

2026-07-17에 기존 local 전국 데이터 volume을 유지하면서 V9 `ai_read` view를
적용했다. 적용 전 custom-format backup과 checksum, 적용된 Flyway history 및 권한
검증 결과는 `docs/reports/CHATBOT_SLICE_3_PROPERTY_READINESS.md`에 보존한다.

이 작업은 history를 `repair`, DELETE, UPDATE 또는 재작성하지 않았고, 완료 후
일회성 `legacy-before|legacy-after` 및 local upgrade wrapper는 제거했다. 반복 운영
interface는 `property-flyway.sh info|validate|migrate <numeric-target>`과 fresh-only
`property-deployment-preflight.sh before|after <numeric-target>`만 유지한다.

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
./ops/user-deployment-preflight.sh before 6
./ops/user-flyway.sh migrate 6
./ops/user-deployment-preflight.sh after 6
./ops/user-flyway.sh validate
```

preflight는 pinned PostgreSQL client의 read-only catalog probe로
`current_database()`와 service relation/history를 확인한다. `before`는 empty
database만, `after`는 migration versions 1 through 6이 각각 정확히 한 건의 `SQL`/`Success`이고
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
- Public gateway는 `/actuator`와 `/actuator/**`를 `404`로 차단한다.
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
현재 local DB의 JDBC/Deleted audit history는 일반 fresh-only deployment
preflight에서 거부되는 것이 정상이다. local 검증은 read-only `info`/`validate`와
Slice 3 준비 보고서의 실제 DB 감사 근거를 사용한다.

## Local Redis

`infra/docker-compose.local.yml` includes a local Redis service for short-lived
map marker response caching.

- Container name: `home-search-redis`.
- Docker network address: `redis:6379`.
- Host address: `127.0.0.1:${HOME_SEARCH_REDIS_PORT:-16379}` (loopback only).
- Healthcheck command: `redis-cli ping`.

The local `api` service receives Redis connection variables by default:

```text
HOME_MAP_MARKER_CACHE_ENABLED=false
HOME_MAP_MARKER_CACHE_TTL=5m
SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379
```

The local API mounts the Gradle `bootJar` read-only under `/source` and copies
it into the container filesystem before starting Java. Rebuilding the host JAR
therefore cannot corrupt an already-running JVM. Apply a newly built API JAR
with `docker compose -f infra/docker-compose.local.yml up -d --no-deps
--force-recreate api`; the copy occurs only when the container starts.

Provider-enabled local Batch jobs use the scheduler-free `property-batch`
tools profile. PostGIS and Redis must already be healthy, and the one-shot
container must run with `--no-deps` so collection cannot recreate either
dependency:

```bash
docker compose -f infra/docker-compose.local.yml --profile tools run --rm \
  --no-deps -e SPRING_BATCH_JOB_NAME=marketNewsGeneralJob \
  property-batch runDate=YYYY-MM-DD requestId=BOOTSTRAP:{canonical-uuid}
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

## Public Map Gateway Guard

The checked-in nginx gateway rate-limits only the two expensive bbox routes,
`POST /api/v1/map/regions` and `POST /api/v1/map/complexes`, per client IP at
`10r/s` with burst `30`. `OPTIONS` is excluded so CORS preflight does not consume
the quota. The application independently rejects oversized bounds before a map
query runs: region bounds are capped at latitude/longitude spans `10.0/15.0`
degrees and complex bounds at `1.0/1.5` degrees.

The nginx config is rendered from the mounted template at container startup so
only `FRONTEND_URL` is injected into gateway-generated CORS headers. nginx
runtime variables remain intact. Rate-limit responses use the public
ProblemDetail shape, `429`, `Retry-After: 1`, and `Cache-Control: no-store`.
Verify the config and behavior without the database or API JAR:

```bash
infra/nginx/test-property-public.sh
```

The smoke test starts isolated temporary nginx containers, checks that a normal
request succeeds, a burst yields both `200` and `429`, the `429` body and headers
match the contract, `OPTIONS` remains exempt, and requests recover after the
bucket drains. CI runs the same check for `infra/**` changes.

## Kakao Nearby-place Gateway

Kakao REST credentials are server-only. Never add `KAKAO_REST_API_KEY` to a
`VITE_*` variable, fixture, log, image layer, or committed override file.

```text
KAKAO_REST_API_KEY=
HOME_PLACE_KAKAO_ENABLED=false
HOME_PLACE_KAKAO_CACHE_ENABLED=true
HOME_PLACE_KAKAO_CACHE_TTL=24h
HOME_PLACE_KAKAO_VIEWPORT_CACHE_TTL=1h
HOME_PLACE_KAKAO_DAILY_REQUEST_BUDGET=10000
HOME_PLACE_KAKAO_CONNECT_TIMEOUT=1s
HOME_PLACE_KAKAO_READ_TIMEOUT=2s
HOME_PLACE_KAKAO_EXECUTOR_THREADS=4
```

Local compose에서 주변시설을 사용할 때는 저장소 루트의 gitignored `.env`에
다음 두 값만 실제 credential로 설정하고 `docker compose --env-file .env`로
실행한다. 실제 key를 tracked example, `VITE_*`, image, log에 넣지 않는다.

```text
HOME_PLACE_KAKAO_ENABLED=true
KAKAO_REST_API_KEY=<Kakao REST API key>
```

The feature is disabled by default. Enabling it requires a Kakao app with Local
API use enabled, Redis connectivity, and a deployment-time daily budget below
the provider quota. The shared executor uses four workers by default (bounded
to `1..4`) and a queue capacity of 24, so the eight default complex categories
run in two bounded waves while viewport selection stays capped at three.
Public ingress must apply the equivalent of the checked-in
per-IP `5r/s`, burst `10` nearby-place route limit; the local gateway returns
the same public `429` ProblemDetail above it. Redis cache read/write failures may degrade to a provider call,
but an unavailable quota guard fails closed.

Complex category cache keys contain six-decimal canonical coordinates, radius,
and category with a 24-hour TTL. Viewport keys use a separate namespace with map
level, outward-normalized 0.001° bounds, and category with a 1-hour TTL. Empty
results are cached, provider failures are not cached, both scopes share the same
daily request budget, and no place result is written to PostgreSQL.

Prometheus exposes bounded tags only:

- `home_search_nearby_place_cache_requests_total` (`scope=complex|viewport`)
- `home_search_kakao_local_calls_total` (`scope=complex|viewport`)
- `home_search_kakao_local_duration_seconds` (`scope=complex|viewport`)
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
curl -fsS http://localhost:${HOME_SEARCH_PROMETHEUS_PORT:-9090}/-/ready
curl -fsSG http://localhost:${HOME_SEARCH_PROMETHEUS_PORT:-9090}/api/v1/query \
  --data-urlencode 'query=up{job="home-search-api"}'
test "$(curl -sS -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/prometheus)" = 404
curl -fsS http://localhost:${HOME_SEARCH_LOKI_PORT:-3100}/ready
curl -fsS http://localhost:${HOME_SEARCH_GRAFANA_PORT:-3000}/api/health
```

Use `docker compose stop` or `docker compose down` without `-v` when shutting
down the local stack. Do not use `docker compose down -v` unless a current task
explicitly approves volume deletion.

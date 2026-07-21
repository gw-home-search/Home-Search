# Home Search AI service

`apps/ai` is the evidence-grounded FastAPI service. Slice 2 adds the isolated
`home_search_ai` dataset lifecycle. Slice 4 adds the provider-agnostic grounded
property kernel and a separate read-only property connection pool.
Slice 6A adds a default-disabled school-location reference path and a one-shot
official API importer. It does not activate the Capability without a `Pass`
readiness report.

The property pool requires `HOME_AI_PROPERTY_DSN` to target database
`home_search` as role `home_search_ai_reader`. It reads only `ai_read` views and
uses read-only transactions with a bounded statement timeout. Do not reuse the
dataset runtime or migrator credential.

The OpenAI Responses adapter is enabled only when an API key and explicit
primary and secondary model IDs are all configured. It sends strict Structured
Outputs requests with `store: false`, bounded output tokens, a bounded HTTP
response, and a fixed HTTPS endpoint. The primary model is retried once before
the secondary model is attempted. Missing or invalid configuration returns the
existing `CHATBOT_PROVIDER_UNAVAILABLE` contract; no template or raw
observation is exposed as a substitute answer.

Runtime variables:

- `HOME_AI_OPENAI_API_KEY`
- `HOME_AI_OPENAI_PRIMARY_MODEL`
- `HOME_AI_OPENAI_SECONDARY_MODEL`
- `HOME_AI_OPENAI_TIMEOUT_SECONDS` (optional, default `8`, allowed `1..30`)
- `HOME_AI_QUERY_TIMEOUT_SECONDS` (optional, default `45`, allowed `1..60`)
- `HOME_AI_ENABLED_PROPERTY_CAPABILITIES=complex_identity,recent_trade_lookup,price_trend`
- `HOME_AI_REFERENCE_DSN` (separate `home_search_ai_runtime` read-only pool)
- `HOME_AI_ENABLED_REFERENCE_CAPABILITIES` (blank by default; the only approved
  non-empty value is `school_location` after activation approval)

The runtime Capability setting is fail-closed. This activation permits the
identity-only rollback value or the cumulative
`complex_identity,recent_trade_lookup` value, or the approved cumulative
`complex_identity,recent_trade_lookup,price_trend` value. Missing, reordered,
duplicate, whitespace-padded, or unapproved values disable all property capabilities.
Golden verification uses its own explicit candidate set and does not widen the
runtime allowlist.

`academy_registry_summary` is implemented for offline review only. It resolves
the property's province and district through the property read view, then runs
an exact education-office plus district aggregate query against the separate AI
database. It remains impossible to enable through the runtime allowlist until
license and live readiness approval are recorded.

`academy_lookup` is also implemented for offline review only. It queries at
most five Sbiz education-store points within 800m by default, or an explicit
100..2,000m radius. Unmatched results remain Sbiz B-grade location evidence;
only Unicode NFKC name plus canonical road-address exact matches may add NEIS
A-grade registry evidence. The runtime allowlist cannot enable this capability
until license, taxonomy, coordinate coverage, and live readiness are approved.

Do not rely on provider-side conversation state. The browser sends only the
bounded `conversationContext`, and the AI service treats it as an untrusted
resolution hint. Provider output still passes the local fact ID, claim, numeric
value, citation, and data readiness checks before a response is returned.

## Container runtime

`Dockerfile` builds the locked runtime dependencies with uv and runs Uvicorn as
the non-root `home-ai` user. `local-runtime.example` documents only placeholder
values. Local integrated startup is owned by
`infra/chatbot/run-local-chatbot.sh`; do not invoke the overlay directly and do
not inject a migrator DSN into the runtime container. The overlay injects the
validated OpenAI variables plus only `HOME_AI_REFERENCE_DSN` and
`HOME_AI_ENABLED_REFERENCE_CAPABILITIES` into the AI container. It never
injects the AI dataset migrator/importer DSN, role passwords, or public API key.
Provider adapter tests use a fake transport and make no live request.

## Verification

Docker must be available because dataset tests start a disposable PostgreSQL
container without a named volume.

```bash
uv sync --frozen --group test
uv run pytest
```

If the local Docker runtime cannot run the Testcontainers Reaper sidecar, use
`TESTCONTAINERS_RYUK_DISABLED=true uv run pytest`. The PostgreSQL container is
still stopped and removed by the fixture context manager.

## Property golden verification

The packaged golden catalog verifies `complex_identity`,
`recent_trade_lookup`, `price_trend`, and the no-result path against the
read-only `home_search.ai_read` connection. Offline mode replays only the
catalog plan and deterministic claims, but still executes the production
repository, observation, grounding, citation, freshness, and limitation
validation path.

```bash
# Defaults to apps/ai/.env and reads only the required named values.
apps/ai/ops/run-local-property-golden.sh offline
```

The local vars file must be a regular non-symlink file with no group/other
permissions (`chmod 600 apps/ai/.env`). The runner accepts only the dedicated
`home_search_ai_reader` DSN and never sources the file into the shell.

Live mode is deliberately limited to one named case. It requires an explicit
cost confirmation and can make at most six provider HTTP requests under the
current primary retry and secondary fallback policy. Running it requires
separate approval; the normal test suite never enables it.

```bash
HOME_AI_GOLDEN_LIVE_CONFIRM=RUN_ONE_LIVE_GOLDEN_CASE \
  apps/ai/ops/run-local-property-golden.sh live \
  --case-id price-trend-jamsil-ells-84
```

The command reports only case IDs, readiness, counts, dates, and stable reason
codes. It does not print questions, answers, DSNs, API keys, or provider error
details. A passing local command is verification evidence; Capability status
must remain unchanged until the data readiness report, live case, contract
review, and activation approval are all complete.

## Dataset migration

Runtime startup never applies DDL. Supply a dedicated migrator credential only
to the explicit migration command:

```bash
HOME_AI_MIGRATOR_DSN='postgresql://...' uv run home-ai-migrate
```

Do not expose `HOME_AI_MIGRATOR_DSN` to the runtime container. The runtime
credential must not own schema objects or receive access to the property database.

Before migration, fresh volumes receive the roles from PostgreSQL init. For an
existing local volume, export the three protected `AI_DATA_*_DB_PASSWORD`
values and run `infra/postgres/bootstrap-ai-database.sh`. The command is
idempotent and does not delete or recreate a Docker volume.

Migrations are discovered from `ai_service/datasets/migrations/NNNN_*.sql` in
numeric order. Applied checksums are verified before pending migrations run;
an applied file must never be edited.

## Reference one-shot ingest

The importer uses the official HTTPS endpoint with sequential 1,000-row pages,
one bounded retry for timeout/5xx, and deterministic ZIP raw preservation. It
does not log or persist the API key, request URL, HTTP error body, or query.
Runtime does not receive importer/migrator credentials or the public-data key.

Before any network or database object is created, the source entry in
`config/reference_sources.toml` must have complete tracked license evidence and
`status = "APPROVED"`. Approval is source-specific and is never inherited by a
different public dataset.

```bash
HOME_AI_IMPORTER_DSN='postgresql://...' \
HOME_AI_DATA_GO_KR_SERVICE_KEY='...' \
HOME_AI_RAW_S3_BUCKET='private-bucket' \
HOME_AI_RAW_S3_PREFIX='raw' \
HOME_AI_RAW_S3_REGION='ap-northeast-2' \
uv run home-ai-school-location-ingest
```

Use protected runtime secret injection rather than command-line literals in
real operation. The command emits only `Pass|Fail`, source/date/page/row counts,
dataset version, and stable reason codes. Configuration failure exits `2`;
API, parse, quality, or publication failure exits `1`.

For the local PostGIS/MinIO path, use the protected-file wrapper instead of
sourcing either `.env` file:

```bash
apps/ai/ops/run-local-reference-refresh.sh --source edu.school-location
apps/ai/ops/run-local-reference-refresh.sh --source edu.academy-registry
apps/ai/ops/run-local-reference-refresh.sh --source place.sbiz-academy
apps/ai/ops/run-local-reference-refresh.sh --source retail.large-store
apps/ai/ops/run-local-reference-refresh.sh --source transport.rail-station
apps/ai/ops/run-local-reference-refresh.sh --source childcare.center
apps/ai/ops/run-local-reference-refresh.sh --family priority
```

The wrapper derives dedicated migrator/importer DSNs from the protected role
passwords, prepares the private versioned/object-locked MinIO bucket, runs
migrations in a one-shot container, and then runs the importer. It passes
secret names through Docker `--env` without placing values in process arguments.
School and Sbiz receive only `HOME_AI_DATA_GO_KR_SERVICE_KEY`; NEIS receives
only `HOME_AI_NEIS_SERVICE_KEY`; childcare receives only
`HOME_AI_CHILDCARE_SERVICE_KEY` and the bounded
`HOME_AI_CHILDCARE_REGION_CODES` scope; CSV/XLSX file sources receive no provider
key. Childcare remains outside the priority family until its source license and
live completeness evidence are approved. The priority family starts one generic
CLI container per active source in fixed order, keeps those key boundaries, and
continues after an individual failure.

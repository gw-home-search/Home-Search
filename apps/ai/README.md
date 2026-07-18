# Home Search AI service

`apps/ai` is the evidence-grounded FastAPI service. Slice 2 adds the isolated
`home_search_ai` dataset lifecycle. Slice 4 adds the provider-agnostic grounded
property kernel and a separate read-only property connection pool.

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

The runtime Capability setting is fail-closed. This activation permits the
identity-only rollback value or the cumulative
`complex_identity,recent_trade_lookup` value, or the approved cumulative
`complex_identity,recent_trade_lookup,price_trend` value. Missing, reordered,
duplicate, whitespace-padded, or unapproved values disable all property capabilities.
Golden verification uses its own explicit candidate set and does not widen the
runtime allowlist.

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
validated OpenAI runtime variables only into the AI container. Provider adapter
tests use a fake transport and make no live request.

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
credential will be introduced with the first operational dataset slice and
must not own schema objects or receive access to the property database.

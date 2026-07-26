# ADR 0002: Market insight, news, and digest boundary

- Status: Accepted
- Date: 2026-07-22

## Context

Home Search now has an explicitly approved expansion for publicly disclosed
apartment-trade insights, NAVER news, and opt-in in-app/email digests. This is
an additive product surface. It does not change the existing map, search,
detail, or trade contracts and must not enter their query path.

## Decision

- `apps/property-data` owns RTMS collection evidence, trade insight snapshots,
  the NAVER news adapter/cache, and public insight/news APIs inside its existing
  `core`, `api`, and `batch` modules.
- `apps/user/service` owns subscriptions, inbox rows, delivery state, and email
  suppression in `home_search_user`. A new `batch` composition root may call
  property-data over HTTP; it must not read the property database.
- No `libs/market-insight-core` or shared Java DTO library is added. Producer
  REST Docs/OpenAPI and consumer fixtures own HTTP compatibility.
- New public trade insight data is materialized before API reads. Public API
  requests must not aggregate the complete trade history.
- A nationwide snapshot is publishable only from a `DAILY`/`NATIONWIDE`
  execution whose planned `lawd_cd x deal_ymd` work units are all `COMPLETED`.
  `BACKFILL`, `REPLAY`, `MAINTENANCE`, `TARGETED`, `PARTIAL`, and `FAILED`
  evidence never qualifies as today's newly disclosed nationwide data.
- `excl_area numeric(10,2)` is compared exactly. Contract dates use ISO-8601,
  amounts remain in 10,000 KRW, and areas remain square meters.
- NAVER news and email remain disabled by default until credentials and
  operational feedback controls are ready.

## Consequences

- Existing map/search/detail/trade URLs, response shapes, and database query
  dependencies remain unchanged.
- Insight and digest workloads can be disabled or rolled back independently.
- The user batch receives user database credentials, the property API base URL,
  and SES permission only. It receives no OAuth client secret or user JWT
  signing key.
- SES delivery is at-least-once at the provider boundary. An ambiguous send is
  stored as `UNKNOWN` and is not retried automatically.

## Stop conditions

Stop before a public breaking change, cross-service database access, a lossy
migration, reinterpretation of `complex_id`, provider enablement without valid
credentials/quota/quality gates, or email enablement without bounce/complaint
feedback and suppression.

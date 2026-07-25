# ADR 0006: Market news evidence, publication, and quality

- Status: Accepted
- Date: 2026-07-24

## Context

ADR 0002 approved NAVER news as an isolated property-data expansion but did
not fix the evidence grain, publication lifecycle, matching precision, or
retention boundary. Apartment names are frequently short or duplicated across
regions, so query provenance alone is not sufficient evidence for a complex
relation.

## Decision

- Property Batch is the only NAVER API HUB caller. The API runtime never calls
  the provider and never fetches a returned article URL. Batch uses the API HUB
  endpoint `/search/v1/news` and the NCP API Gateway credential headers, not
  the legacy NAVER Developers endpoint or headers.
- `NEWS_V2` owns the six nationwide, 17 SIDO, and 200 major-complex query
  templates. General collection runs four times per KST day; major-complex
  collection follows the 06:30 general run. The major list is selected every
  Monday from uncanceled trades in the prior 90 days. V2 rejects public-health
  incidents and corporate-performance stories when real-estate decision terms
  occur only outside the title. A policy change requires a full bootstrap;
  snapshots never merge relations from a different policy version.
- The first `NEWS_V2` deterministic review set was
  `INSUFFICIENT_SAMPLE` because Jeju had four of five required SIDO samples and
  the duplicate/short-name challenge set had 49 of 50. `NEWS_V3` keeps the V2
  matcher and rejection policy unchanged and adds one supplemental query per
  SIDO and per major complex. Each SIDO publishes only after both of its query
  work units finish, so extra volume cannot bypass scope atomicity.
- Every provider item is stored in `market_news_raw_item` before validation.
  Normalization strips markup, decodes entities, validates RFC 1123 time and an
  HTTP(S) URL without userinfo, then deduplicates by a separately calculated
  canonical URL hash. The public URL remains the provider-returned value.
- Direct complex relations require a provider title/description complex name plus its
  SIGUNGU. Nationwide duplicate names and names of four characters or fewer
  additionally require the legal DONG. Query text does not create relation
  evidence.
- PostgreSQL is the durable source for execution, raw, article, relation,
  snapshot, selection, rejection, and review evidence. Redis stores only
  current/last-good snapshot pointers.
- `marketNewsQualitySampleJob` creates an idempotent deterministic review set
  for a policy version. V24 records aggregate coverage and marks any missing
  minimum `INSUFFICIENT_SAMPLE`; labels keep FK lineage to review set, article,
  and relation. V25 pins the exact current snapshots before selection so a
  retry after supersede or withdrawal cannot change the sampled population.
- An incremental scope is published atomically only when every required work
  unit reaches its overlap cutoff without truncation, authentication failure,
  or budget exhaustion. The initial 30-day `BOOTSTRAP` may publish the bounded
  API result when all required units terminate as `COMPLETED|TRUNCATED` with no
  provider/budget failure; `bootstrap_truncated` preserves that limitation.
  Nationwide requires all six category units. Each SIDO is independent. A
  published snapshot may become `SUPERSEDED` or `WITHDRAWN`; evidence is not
  deleted as a rollback action.
- Public reads prefer the current `PUBLISHED` snapshot. After a quality
  withdrawal they may use the previous `SUPERSEDED` snapshot as last-good.
  `FRESH` lasts eight hours unless a newer collection is incomplete; fallback
  last-good is always `STALE`. When no normal publication exists, the response
  is `UNAVAILABLE`.
- Raw provider fields are retained seven days; normalized article/relation and
  non-current snapshot data 30 days; execution aggregates and review labels
  180 days. Article bodies and images are never stored.
- Production collection and public reads remain independently fail-closed
  behind Terraform schedule/public flags until credentials, quota, migration,
  and precision readiness are verified.

## Quality gate

Publication requires valid public fields, zero canonical duplicates, complete
FK lineage, a 30-day time boundary, full incremental cutoff coverage, and
idempotent request evidence. Human review requires at least 90% real-estate and
category precision, 95% direct-complex precision, 90% DONG/SIGUNGU precision,
and zero false direct links in the duplicate/short-name challenge set.
Insufficient samples do not pass.

If a published result fails review, it becomes `WITHDRAWN`; matcher/query rules
are versioned and a new run publishes a replacement. Precision is never traded
for volume. After two unsuccessful policy revisions, the affected category or
complex-relation surface is disabled.

## Consequences

Map, search, detail, and trade APIs remain independent of provider, snapshot,
and Redis availability. News can be disabled without hiding existing real
estate data. The cost is additive storage and an operational review loop.

## Stop conditions

Stop before provider enablement if credentials would enter API or browser
scope, quota cannot support bounded pagination, relation precision misses the
gate, or a breaking public API/data reinterpretation is required.

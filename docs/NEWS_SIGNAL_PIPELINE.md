# News Signal Pipeline

## Status

This document is a planning baseline for later-scope real-estate news signals.
It does not change the current Home Search public API contract, map display
flow, or trade ingest storage contract.

The first implemented news slice is for model-signal validation, not a public
news product. It separates AI-assisted historical seed data from provider
observed realtime data so experiments can compare both without weakening
time-safety claims.

## Goal

Collect public real-estate news metadata and convert it into time-safe,
model-ready signals for future apartment price prediction.

The system should answer:

- What real-estate topic was reported?
- Which region or complex might it affect?
- When was the information first observable to Home Search?
- What structured signal can a prediction model use without storing article
  full text or replacement summaries?

## Non-Goals

- Store article full text.
- Store LLM-written natural-language summaries that can replace the article.
- Build a public news reading product.
- Change the existing map, detail, region, search, or trade API URLs.
- Make map endpoints depend on prediction, recommendation, ranking, favorite,
  alarm, or mail state.

## Source Policy

Use source classes in this priority order:

1. Licensed or contracted news data APIs, such as BigKinds or DeepSearch, when
   full-text or richer metadata is needed.
2. Official search APIs that return metadata and snippets, such as Naver News
   Search API.
3. Publisher-provided RSS feeds, only within their published usage terms.
4. Government and public agency press releases.
5. Publisher article pages, only after source-specific crawling and terms
   review.

Current implemented source classes:

- `NAVER_NEWS_SEARCH`: official Naver News Search API metadata source. This is
  the production observed source for realtime collection from `2026-06-01`
  onward.
- `AI_ASSISTED_WEB_RESEARCH`: OpenAI web search assisted candidate discovery
  for historical model seed data. It is not treated as production observed
  history and only enters the database after manual approval.

Naver News Search is not used for long historical backfill before
`2026-06-01`. If the June 2026 transition gap needs to be filled before the
daily realtime collector is active, store it only as
`AI_ASSISTED_TRANSITION_SEED`, not as `REALTIME_OBSERVED`.

For unlicensed publisher pages:

- Fetch only when a prior metadata filter says the article is prediction
  relevant.
- Respect robots, rate limits, paywall boundaries, and publisher terms.
- Do not persist full text.
- Do not persist article-like summaries.
- Persist only structured features and source metadata.

### AI-Assisted Historical Seed

`2017-01-01` through `2026-05-31` historical candidates are generated as
`AI_ASSISTED_RESEARCH_SEED` review notes. OpenAI web search is used to find
candidates and citations, with `store=false`, strict structured output, and a
schema that excludes article body and article-like summary fields.

Generated notes are written under:

```text
news-research-seed/
  <region_bucket>/
    <year>/
      <published_date>-<candidate_hash>.md
```

The note frontmatter starts as:

```yaml
verification_status: NEEDS_REVIEW
source: AI_ASSISTED_WEB_RESEARCH
discovery_method: OPENAI_WEB_SEARCH
availability_basis: AI_ASSISTED_RESEARCH_SEED
model_dataset_tier: EXPERIMENTAL_SEED
title:
publisher:
published_date:
url:
url_citation:
region_bucket:
topic:
impact_target:
impact_direction_hint:
model_utility:
confidence:
reviewed_by:
```

Humans must verify URL, date, publisher, region bucket, and topic, then change
`verification_status` to `MANUAL_APPROVED` or `REJECTED`. The importer ignores
`NEEDS_REVIEW` and `REJECTED`; only `MANUAL_APPROVED` notes create database
rows.

## Storage Boundary

The database remains the source of truth. Obsidian markdown is an export and
review layer that can be regenerated from database rows.

### Article Observation

Purpose:

- Preserve discovery evidence.
- Support deduplication.
- Preserve time ordering for backtests.
- Avoid storing article full text.

Minimum fields:

- `id`
- `source`
- `source_key`
- `discovery_method`
- `availability_basis`
- `verification_status`
- `model_dataset_tier`
- `review_note_path`
- `ai_research_seed_run_id`
- `publisher`
- `title`
- `url`
- `provider_url`
- `snippet`
- `published_at`
- `provider_pub_at`
- `first_seen_at`
- `collected_at`
- `updated_at`
- `news_date_kst`
- `raw_provider_payload`
- `payload_hash`
- `ingest_status`
- `failure_reason`

`snippet` is the official API or RSS snippet, not an internally generated
replacement summary.

`raw_provider_payload` must be redacted to metadata and snippet fields only.
Article body fields such as `content`, `body`, `full_text`, or rendered HTML
must be dropped before storage, even when a licensed provider returns them.
Forbidden body-like keys include `content`, `body`, `full_text`, `html`,
`article_html`, `summary`, `article_summary`, `본문`, `내용`, `원문`, and
`기사본문`.

For imported AI historical seed rows:

```text
source = AI_ASSISTED_WEB_RESEARCH
discovery_method = OPENAI_WEB_SEARCH
availability_basis = AI_ASSISTED_RESEARCH_SEED
verification_status = MANUAL_APPROVED
model_dataset_tier = EXPERIMENTAL_SEED
```

For Naver realtime rows:

```text
source = NAVER_NEWS_SEARCH
discovery_method = PROVIDER_API
availability_basis = REALTIME_OBSERVED
verification_status = SYSTEM_ACCEPTED
model_dataset_tier = OBSERVED_SIGNAL
```

### News Signal Feature

Purpose:

- Store model-ready facts derived from an article observation.
- Keep prediction features independent of article expression.

Minimum fields:

- `id`
- `article_observation_id`
- `source`
- `source_key`
- `feature_date_kst`
- `first_seen_at`
- `region_tags`
- `complex_candidates`
- `topic_tags`
- `impact_target`
- `impact_direction`
- `sentiment`
- `confidence`
- `extraction_version`
- `evidence_level`
- `created_at`

Recommended values:

- `topic_tags`: `policy`, `supply`, `reconstruction`, `redevelopment`,
  `jeonse`, `rate`, `loan`, `subscription`, `transaction`, `auction`,
  `unsold`, `transport`, `school`, `development`.
- `impact_target`: `sale_price`, `jeonse_price`, `volume`, `supply`,
  `liquidity`, `risk`.
- `impact_direction`: `up`, `down`, `mixed`, `unknown`.
- `sentiment`: `positive`, `neutral`, `negative`, `mixed`.
- `evidence_level`: `title`, `snippet`, `licensed_full_text`,
  `public_press_release`.

## Date Model

Dates are first-class prediction data.

Required timestamps:

- `published_at`: publisher article time when available.
- `provider_pub_at`: API or RSS publication time.
- `first_seen_at`: first time Home Search observed the article.
- `collected_at`: time Home Search fetched or processed the record.
- `updated_at`: publisher or provider update time when available.
- `event_date`: policy effective date, announcement date, subscription date,
  sales opening date, or other date mentioned inside the article when extracted.
- `news_date_kst`: KST date bucket for aggregation.

Backtests and RAG retrieval must use `first_seen_at` as the safest cutoff:

```text
retrievable if first_seen_at <= prediction_cutoff
```

`published_at` can be used for market reaction analysis, but it must not allow
future-discovered historical articles to leak into a past prediction.

Policy articles can have multiple market-relevant dates:

- `announcement_date`: when the market could start reacting.
- `effective_date`: when the rule starts applying.
- `application_window`: start and end dates for subscription, regulation,
  tax, or loan windows.

## Deduplication

Primary identity:

- `source + source_key`

Fallback identity:

- Canonicalized `url`
- Canonicalized `title + publisher + published_at`
- Provider-specific article id when available

Repeated collection must not create duplicate `news_signal_feature` rows for
the same `source + source_key + extraction_version`.

## Daily Collection Pipeline

The daily pipeline is a later-scope backend batch. It must remain independent of
the public map, detail, region, search, and trade APIs.

Default schedule:

```yaml
home.news.pipeline.daily.enabled: false
home.news.pipeline.daily.cron: "0 0 4 * * *"
home.news.pipeline.daily.zone: Asia/Seoul
```

The implemented scheduler is disabled by default. In the first pilot it should
run only a bounded query list for the v1 buckets:

- `NATIONAL`
- `SEOUL_GANGNAM_GU`
- `SEOUL_SONGPA_GU`
- `GYEONGGI_SEONGNAM_SI`
- `GYEONGGI_GWACHEON_SI`

Execution order:

```text
DB keyword planner
  -> Naver News metadata observation per due keyword
  -> relevance gate
  -> feature extraction
  -> Obsidian daily export
  -> Hermes Slack notification
  -> durable run history finalize
```

Daily keyword selection reads from `news_collection_keyword`. The table is the
operational keyword source for future RAG and Tool lookup alignment:

- `query_text`: exact query sent to the provider.
- `keyword_type`: `TOPIC`, `REGION`, `COMPLEX`, or `ALIAS`.
- `source_table` and `source_id`: optional DB source identity for generated
  region or complex keywords.
- `priority`, `cadence`, `enabled`, `next_due_at`, and `last_collected_at`:
  scheduler selection and rotation controls.

The first release must bound API usage with `max-keywords` and `display`.
Do not search every region or complex name every day unless quota, noise, and
duplicate behavior are reviewed first.

Run history is durable:

- `news_collection_run`: total status, counts, Obsidian export path, and Hermes
  notification result.
- `news_collection_run_keyword`: per-keyword query snapshot and outcome.
- `news_collection_run_article`: article provenance for each keyword discovery.

`news_collection_run_article` is intentionally separate from
`news_article_observation`. Repeated article discovery must keep
`source + source_key` dedupe intact while preserving which keyword found the
article again.

Hermes notification failure must not turn a completed collection into a failed
pipeline. Store `notification_status = FAILED`, log a sanitized warning, and
preserve the run result.

## Feature Extraction Rules

Minimum first-release extraction should use only title and official snippet:

```text
title + snippet -> region_tags + topic_tags + impact_target +
impact_direction + sentiment + confidence
```

Full text is allowed only through licensed APIs, public press releases, or
source-reviewed fetches. Even then, persist only structured features.

Extraction must be deterministic enough for replay:

- Store `extraction_version`.
- Store prompt or classifier version outside secrets.
- Store enough input metadata to explain why a feature was produced.
- Re-running the same version on the same observation should produce the same
  feature or an auditable diff.

## Obsidian Export

Obsidian is useful for human review and RAG grounding, not as the operational
database.

Export shape:

```text
obsidian/
  news-signals/
    daily/
      2026-06-07.md
    weekly/
      2026-W23.md
    regions/
      seoul-gangnam-gu.md
```

Daily note front matter:

```markdown
---
date: 2026-06-07
first_seen_until: 2026-06-07T23:59:59+09:00
regions: [Seoul, Gangnam-gu]
topics: [reconstruction, supply, policy]
source_count: 18
generated_from: news_signal_feature
---
```

Daily notes should include aggregated signals and source links, not article
replacement summaries.

## RAG Boundary

RAG is for:

- Explaining prediction drivers.
- Finding source links behind a signal.
- Reviewing regional topic history.
- Generating analyst-facing notes.

RAG is not the primary model input. Prediction models should consume structured
time-series features from the database.

Every RAG query that supports a historical prediction must include a cutoff:

```text
first_seen_at <= prediction_cutoff
```

## Dataset Contract

Model and RAG consumers should read prediction-safe rows from a stable dataset
contract, not from ad hoc joins over operational tables.

`news.signal_dataset_view` exposes:

- Article identity: `source`, `source_key`, `publisher`, `title`, `url`.
- Dataset provenance: `discovery_method`, `availability_basis`,
  `verification_status`, `model_dataset_tier`, `review_note_path`.
- Time safety: `published_at`, `provider_pub_at`, `first_seen_at`,
  `feature_date_kst`, `news_date_kst`.
- Model features: `region_tags`, `complex_candidates`, `topic_tags`,
  `impact_target`, `impact_direction`, `sentiment`, `confidence`,
  `extraction_version`, `evidence_level`.

It must not expose:

- `raw_provider_payload`
- article body fields such as `content`, `body`, `full_text`, or `html`
- internally generated replacement summaries

Historical prediction datasets must always apply:

```text
first_seen_at <= prediction_cutoff
```

This rule is stricter than `published_at <= prediction_cutoff` and prevents a
future-discovered historical article from leaking into a past backtest.

Two narrower views separate experiment and production reads:

- `news.model_experiment_signal_view`: includes both `EXPERIMENTAL_SEED` and
  `OBSERVED_SIGNAL`.
- `news.production_observed_signal_view`: includes only `OBSERVED_SIGNAL`.

`EXPERIMENTAL_SEED` rows may help evaluate whether news features improve model
performance. They must not be represented as production-grade observed history.

### Region Bucket V1

Model aggregation uses stable v1 buckets:

- Parent buckets: `NATIONAL`, `SEOUL`, `GYEONGGI`, `OTHER`.
- Seoul detail buckets: `SEOUL_GANGNAM_GU`, `SEOUL_SEOCHO_GU`,
  `SEOUL_SONGPA_GU`, `SEOUL_YONGSAN_GU`, `SEOUL_MAPO_GU`,
  `SEOUL_SEONGDONG_GU`, `SEOUL_YEONGDEUNGPO_GU`, `SEOUL_YANGCHEON_GU`,
  `SEOUL_NOWON_GU`, `SEOUL_GANGDONG_GU`.
- Gyeonggi detail buckets: `GYEONGGI_SEONGNAM_SI`,
  `GYEONGGI_GWACHEON_SI`, `GYEONGGI_HANAM_SI`,
  `GYEONGGI_GWANGMYEONG_SI`, `GYEONGGI_GOYANG_SI`,
  `GYEONGGI_YONGIN_SI`, `GYEONGGI_SUWON_SI`,
  `GYEONGGI_HWASEONG_SI`, `GYEONGGI_NAMYANGJU_SI`,
  `GYEONGGI_GIMPO_SI`, `GYEONGGI_ANYANG_SI`,
  `GYEONGGI_UIWANG_SI`.

Major Seoul/Gyeonggi areas not listed fall back to the parent bucket. Other
regions fall back to `OTHER`.

### Topic V1

Topic tags are model-feature labels, not public taxonomy:

```text
policy_regulation, tax, loan_rate, subscription,
reconstruction_redevelopment, supply, transport_infra, school_district,
jeonse_rent, transaction_volume, auction_distress, unsold_inventory,
development_project, macro_rate
```

## Retention And Cleanup

Cleanup should remove provider payloads before deleting source identities.

Rows in `news_article_observation` keep the minimum dedupe/audit identity:

- `source`
- `source_key`
- `publisher`
- `title`
- `url`
- `published_at`
- `first_seen_at`
- `ingest_status`
- `failure_reason`

Recommended retention actions:

- `FEATURED`: keep signal rows and source metadata, purge `raw_provider_payload`.
- `DUPLICATE`: keep `source + source_key` for dedupe, purge payload.
- `SKIPPED_IRRELEVANT`: keep collection trace, purge payload.
- `TERMS_BLOCKED`: keep failure reason and source identity, purge payload.
- `FETCH_FAILED` and `PARSE_FAILED`: keep payload during retry window, then
  purge payload after the retention cutoff.

The database provides:

- `news_article_observation_cleanup_candidate_view`: explains rows whose
  provider payload is eligible for cleanup.
- `purge_news_article_observation_payloads(retention_cutoff)`: purges provider
  payloads while preserving dedupe identities.

Hard-deleting observation rows is a later policy decision. Do it only when
dedupe, backtest reproducibility, source terms, and audit requirements remain
explainable without the row.

## Implementation Slices

1. Add this planning document and keep it linked as later-scope.
2. Add Flyway tables for `news_article_observation`, `news_signal_feature`,
   and `news_source_policy`.
3. Add dataset and cleanup lifecycle contracts for model/RAG consumers.
4. Implement one metadata-only source adapter, preferably Naver News Search API
   or one official RSS feed.
5. Add dedupe tests for repeated article observations.
6. Add cutoff tests for prediction-time feature retrieval.
7. Add title/snippet feature extraction with deterministic labels.
8. Add Obsidian daily markdown export from structured features.
9. Add RAG retrieval over markdown or database chunks with cutoff enforcement.

## TDD Starting Points

Backend behavior slices require RED tests before production changes.

Recommended first RED:

- Same `source + source_key` collected twice creates one observation identity
  and one feature per `extraction_version`.

Recommended second RED:

- Feature query for `prediction_cutoff` excludes rows whose `first_seen_at` is
  later than the cutoff, even when `published_at` is earlier.

## Verification

Docs-only slice:

- Review `git diff`.

Backend slices:

```bash
cd apps/api
./gradlew test
./gradlew persistenceTest
```

Contract-sensitive slices:

```bash
cd apps/api
./gradlew apiContractTest apiDocsCheck
```

Frontend or Obsidian UI slices:

```bash
cd apps/web
npm run test
npm run build
```

## Stop Conditions

Stop and re-plan before implementation if:

- Public API URLs or response shapes must change.
- Full article text or article-like summaries must be stored.
- Source terms or robots policy disallow the intended collection path.
- A licensed API is required but no key or contract exists.
- Prediction or recommendation features would enter the current map/trade
  critical path.

## References

- Naver News Search API: https://developers.naver.com/docs/serviceapi/search/news/news.md
- BigKinds: https://www.bigkinds.or.kr/
- DeepSearch News API: https://news.deepsearch.com/api/
- Korea Online Newspaper Association digital news usage rules: https://kona.or.kr/pages/page_64.php
- Korea Copyright Commission AI fair-use guide: https://copyright.re.kr/library/assets/cmmpxsbev000g87sgg5r53efm

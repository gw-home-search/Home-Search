# News Signal Pipeline

## Status

This document is a planning baseline for later-scope real-estate news signals.
It does not change the current Home Search public API contract, map display
flow, or trade ingest storage contract.

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

For unlicensed publisher pages:

- Fetch only when a prior metadata filter says the article is prediction
  relevant.
- Respect robots, rate limits, paywall boundaries, and publisher terms.
- Do not persist full text.
- Do not persist article-like summaries.
- Persist only structured features and source metadata.

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

`news_signal_dataset_view` exposes:

- Article identity: `source`, `source_key`, `publisher`, `title`, `url`.
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

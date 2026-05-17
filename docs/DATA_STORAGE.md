# Data Storage Strategy

## Goal

Store real-estate trade data safely enough that failed processing can be
explained and repeated. V1 prioritizes correctness, traceability, and map
display over aggregate analytics.

Fixed paths:

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Target repository: `/Users/gwongwangjae/home-search`

## Source Findings

Current trade collection code:

- `ApisClient.getAptTrade(...)`
- `TradeDailyCollectService`
- `TradeDailyCollectTasklet`
- `TradeInitTasklet`
- `ComplexResolveService`
- `TradeBulkWriter`

Current persistence mismatch:

- `domain/trade/Trade.java` maps `trade.complex_id`.
- `DetailUseCase.findAllTradeByParcelId(...)` queries by complex IDs.
- `TradeBulkWriter` inserts `complex_pk`, `apt_seq`, `source`, `source_key`.
- Batch migration `V12__create_trade_partitioned_table.sql` creates a
  partitioned `trade` table around `complex_pk`.

V1 resolves this by using `complex_id` as the operational relation and keeping
source identifiers for audit and deduplication.

## V1 Storage Model

Use two layers:

1. Raw ingest records.
2. Normalized operational trades.

### Raw Layer

Purpose:

- Preserve external API source data.
- Support replay after code changes.
- Explain failed parsing or failed complex matching.
- Avoid losing data when external fields are odd but recoverable.

Minimum raw record fields:

- `id`
- `source`
- `source_key`
- `lawd_cd`
- `deal_ymd`
- `page_no`
- `payload`
- `payload_hash`
- `ingest_status`
- `failure_reason`
- `created_at`
- `processed_at`

Recommended statuses:

- `RECEIVED`
- `NORMALIZED`
- `DUPLICATE`
- `MATCH_FAILED`
- `PARSE_FAILED`
- `SKIPPED_INVALID`

### Normalized Trade Layer

Purpose:

- Serve map marker and detail APIs.
- Maintain one clean row per real trade event.
- Keep enough source metadata to debug the row.

Minimum normalized fields:

- `id`
- `complex_id`
- `deal_date`
- `deal_amount`
- `floor`
- `excl_area`
- `apt_dong`
- `source`
- `source_key`
- `complex_pk`
- `apt_seq`
- `raw_ingest_id`
- `created_at`
- `updated_at`
- `deleted_at`

## Deduplication

Primary dedupe rule:

- Unique by `source + source_key`.

Fallback dedupe rule:

- Unique by `complex_id + deal_date + floor + excl_area + deal_amount`.

The fallback exists because historical RTMS data may not always provide a
perfect stable source key. It should be treated as a safety net, not the main
identity.

## Source Key

Generate `source_key` deterministically from source fields. For RTMS apartment
trades, include all fields that identify a trade event as closely as possible:

- `source`
- `aptSeq`
- `sggCd`
- `umdCd`
- `dealYear`
- `dealMonth`
- `dealDay`
- `floor`
- `exclArea`
- `dealAmount`
- `aptDong`
- `jibun`

Normalize whitespace and comma-formatted amounts before hashing or joining.

## Complex Matching

Current matching order in source code:

1. `aptSeq -> complex.complex_pk`
2. PNU exact single match.
3. PNU plus apartment name score.
4. Name plus `umdCd` fallback in `TradeInitTasklet`.

V1 should preserve the matching intent but record match outcomes:

- Match path.
- Matched `complex_id`.
- Matched `complex_pk`.
- Failure reason if no match.

No trade should disappear without a raw record or a failed-match record.

## Partitioning

Keep trade partitioning by `deal_date`.

Required behavior:

- Yearly partitions for supported historical years.
- `trade_default` partition for unexpected dates.
- Index for latest trade lookup by complex.
- Index for map/detail query paths.

The exact partition year range should be generated or extended safely during
migration. Do not hard-code a range that fails silently after the last year.

## Map Display Query Boundary

V1 map display only needs:

- Parcel position.
- Parcel geometry bounds filter.
- Complex unit count.
- Latest available trade amount for the parcel or complex.

Do not block V1 on:

- Regional trend calculations.
- 30-day top price.
- 30-day top volume.
- Ranking materialization.

## Acceptance Criteria

- Raw rows are created before normalized insert.
- Duplicate collection does not create duplicate normalized trades.
- Failed matches are queryable.
- Normalized trades can be joined to complex and parcel by `complex_id`.
- Map marker APIs work without ranking or trend tables.

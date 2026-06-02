# Data Storage Strategy


## Goal

Store real-estate trade data safely enough that failed processing can be
explained and repeated. Home Search prioritizes correctness, traceability, and map
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

Home Search resolves this by using `complex_id` as the operational relation and keeping
source identifiers for audit and deduplication.

## Project Storage Model

Use two layers:

1. Raw ingest records.
2. Normalized operational trades.

RTMS match attempts also keep a review evidence layer between raw ingest and
normalized trades. See [RTMS_JIBUN_PNU_MATCHING.md](RTMS_JIBUN_PNU_MATCHING.md)
for the detailed jibun/PNU and conflict policy.

Coordinate source storage is a separate lookup dependency, not a third
operational storage layer. Home Search reads PNU coordinates from the coordinate
source database and stores only the resulting operational parcel coordinates in
`parcel`. See [COORDINATE_SOURCE_STRATEGY.md](COORDINATE_SOURCE_STRATEGY.md)
and [DATA_MODEL_ERD.md](DATA_MODEL_ERD.md).

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
- Include only display-safe rows whose parcel and complex match is sufficiently
  certain for public latest price, detail, and trade-list display.

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

- Unique by `complex_id + deal_date + floor + excl_area + deal_amount +
  apt_dong` when `apt_dong` is present on both compared rows.
- When either side has missing `apt_dong`, treat the dong as unknown. A missing
  `apt_dong` row may be linked as a duplicate only when the same fallback base
  identity has exactly one existing normalized trade candidate. If multiple
  `apt_dong` candidates already exist, the missing-dong source key must not be
  attached to an arbitrary trade.

The fallback exists because historical RTMS data may not always provide a
perfect stable source key. It should be treated as a safety net, not the main
identity.

RTMS ingest counters should be read as row outcomes, not as proof that every
source field was identical:

- `normalizedInserted`: raw row produced a new public normalized `trade`.
- `duplicateSkipped`: raw row was preserved but did not create a new public
  normalized `trade`, either because the exact `source_key` was already handled,
  a fallback duplicate was found, or a cancellation-reserved source key blocked
  reinsertion.
- `matchFailed`: raw row was preserved with queryable match evidence but could
  not safely attach to a `complex_id`.

### RTMS Deduplication Scenarios

| Scenario | Storage result | Reason |
| --- | --- | --- |
| Same `source + source_key` arrives again with the same payload | Save raw duplicate evidence; do not insert another `trade` | primary source-key dedupe |
| Same complex/date/floor/area/amount but different non-null `apt_dong` | Insert separate `trade` rows | RTMS has no unit number, so `apt_dong` is the only remaining discriminator for same-condition trades in different buildings |
| Existing row has missing `apt_dong`, later row has one non-null `apt_dong` for the same fallback base identity | Treat later row as duplicate and attach its registry entry to the one existing trade | missing `apt_dong` means unknown, not a separate building |
| Existing row has one non-null `apt_dong`, later row is missing `apt_dong` for the same fallback base identity | Treat later row as duplicate and attach it to the one existing trade | the missing value can safely point to a single candidate |
| Existing rows have multiple non-null `apt_dong` values for the same fallback base identity, later row is missing `apt_dong` | Save raw duplicate evidence, but leave the registry `trade_id` unlinked | attaching to the lowest `trade.id` would let a later cancellation delete the wrong building |
| Same condition and same non-null `apt_dong` arrives through a different source key | Treat as fallback duplicate | this is the duplicate the fallback identity is allowed to catch |

## Cancellation Policy

RTMS cancellation rows are terminal in the current storage contract:

- A cancellation row with the same `source + source_key` soft-deletes the
  linked normalized trade by setting `trade.deleted_at`.
- The `source_key` registry remains reserved after cancellation. If the same
  active row reappears later, it is stored as raw duplicate evidence and does
  not revive the soft-deleted trade.
- Soft-deleted rows still occupy fallback identity. Add an explicit revive
  policy only after confirming RTMS publishes real cancellation reversals that
  should restore public display.

Cancellation scenarios:

| Scenario | Storage result | Public display result |
| --- | --- | --- |
| Active row normalized, then cancellation row with the same `source_key` arrives | Linked `trade` is soft-deleted and cancellation raw row is marked `CANCELED` | trade disappears from map/detail/trade-list APIs |
| Cancellation row arrives before the active row | Registry reserves the `source_key`; later active row becomes raw duplicate evidence | no public trade is created |
| Active row is canceled, then the same active row reappears with the same `source_key` | Reappeared active row becomes raw duplicate evidence | canceled trade is not revived in the current policy |

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

Home Search should preserve the matching intent but record match outcomes:

- Match path.
- Matched `complex_id`.
- Matched `complex_pk`.
- RTMS raw `jibun`, normalized jibun parts, and derived PNU.
- Candidate count and limited candidate complex ids.
- Failure reason if no match.

No trade should disappear without a raw record or a failed-match record.

Normalized `trade` rows should not be created for uncertain matches. Rows are
held as queryable evidence when PNU cannot be derived, no candidate exists,
multiple candidates cannot be narrowed by name/alias, or the derived PNU
conflicts with an otherwise matching `aptSeq`.

Operationally monitor `MATCH_FAILED`, `PNU_UNAVAILABLE`, `UNMATCHED`, and
`AMBIGUOUS` outcomes. Backlogged raw/evidence rows should be eligible for a
future DB-side rematch job after parcel coordinate or complex master coverage
improves, without requiring another external RTMS fetch.

PNU derivation must stay centralized through `RtmsJibunPnuNormalizer` so
bootstrap and matching do not diverge.

### Coordinate Lookup For Bootstrap

RTMS bootstrap must use the coordinate source database as a read-only PNU
coordinate provider:

1. Derive a 19 digit PNU from the RTMS row.
2. Lookup `latitude`, `longitude`, and `geom` in the coordinate source database.
3. Upsert `parcel` in the operational `home_search` database.
4. Upsert `complex` and continue normal matching.

The operational database must not be filled with nationwide coordinate snapshot
tables. In particular, `home_search.reference.parcel_coordinate_snapshot` is not
the target coordinate model.

VWorld VM/WFS is reserved for same-PNU multi-complex marker disambiguation. It
is not the default coordinate provider for ordinary single-complex RTMS
bootstrap.

## Complex Metadata Enrichment

`complex` rows keep identity data on the ingest path. Optional complex
metadata, such as household count, building count, approval date, and building
areas, is enriched outside the RTMS ingest critical path.

Core metadata is considered complete only when all of these fields are present:

- `dong_cnt > 0`
- `unit_cnt > 0`
- `use_date IS NOT NULL`

Area and ratio fields are useful detail metadata, but missing area values do
not block a core `RESOLVED` status:

- `plat_area`
- `arch_area`
- `tot_area`
- `bc_rat`
- `vl_rat`

Metadata enrichment status values:

| Status | Meaning |
| --- | --- |
| `PENDING` | Metadata enrichment has not been attempted yet. |
| `RESOLVED` | A single source candidate supplied all core metadata. |
| `PARTIAL` | A single source candidate supplied some metadata, but core metadata is incomplete. |
| `AMBIGUOUS` | Multiple candidates exist and Home Search must not guess. |
| `UNAVAILABLE` | The lookup ran, but no usable candidate was available. |
| `FAILED` | The lookup failed due to a transient or permanent processing error. |

`metadata_failure_kind` gives retry policy a structured reason instead of
parsing free text:

| Failure kind | Meaning |
| --- | --- |
| `TRANSIENT` | Temporary HTTP, timeout, or parsing failure; retry can help. |
| `PERMANENT` | A deterministic failure that should not be retried automatically. |
| `SOURCE_MISSING` | The external source does not currently expose the candidate. |
| `INPUT_INSUFFICIENT` | Home Search lacks enough lookup input, such as PNU or address. |
| `AMBIGUOUS` | Candidate selection is unsafe without operational review. |

`complex.metadata_attempts` stores the number of persisted enrichment attempts.
`complex.metadata_next_attempt_at` stores the next policy-calculated retry time.
The current storage slice calculates and stores this timestamp only; a future
runner may consume rows where `metadata_next_attempt_at <= now()`.

Every persisted enrichment result also appends a row to
`complex_metadata_enrichment_attempt`. This history keeps each attempt
queryable even though the latest status snapshot remains on `complex` for cheap
map and detail reads.

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

map display only needs:

- Parcel position.
- Parcel geometry bounds filter.
- Complex unit count.
- Latest available trade amount for the parcel or complex.

Do not block project on:

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

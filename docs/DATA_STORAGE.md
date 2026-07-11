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

### Registry To Partitioned Trade Reference

`trade`는 `deal_date` partition key를 가지므로 database가 보장하는 trade
identity는 `(id, deal_date)`다. `trade_source_key_registry`도 같은 pair를
원자적으로 저장한다.

- normalized/fallback-linked registry: `trade_id`와 `trade_deal_date` 모두 존재.
- cancellation-only 또는 ambiguous registry: 두 값 모두 `NULL`.
- cancellation, reconciliation, audit join은 두 column을 모두 사용.
- V5는 nullable column/check/index를 expand하고, bounded backfill이 기존 linked
  row의 새 date column만 채운 뒤, V6가 check와 composite FK를 validate한다.
- V5/V6는 기존 trade/raw/source identity/`complex_id` 값을 삭제하거나
  재해석하지 않는다.

V5 적용 후 V6 전에는 API/Batch writer 배포와 backfill 검증을 완료해야 한다.
V6 이후 구 Batch writer는 pair check를 만족하지 않으므로 rollback하지 않고
Batch를 중지한 채 forward-fix한다.

RTMS ingest counters should be read as row outcomes, not as proof that every
source field was identical:

- `normalizedInserted`: raw row produced a new public normalized `trade`.
- `duplicateSkipped`: raw row was preserved but did not create a new public
  normalized `trade`, either because the exact `source_key` was already handled,
  a fallback duplicate was found, or a cancellation-reserved source key blocked
  reinsertion.
- `matchFailed`: raw row was preserved with queryable match evidence but could
  not safely attach to a `complex_id`.

## Ingest Transaction Boundary And Recovery

The RTMS item flow intentionally does not use one cross-repository transaction
for raw evidence, matching evidence, normalized trade storage, and the final raw
status update. The processing order is:

1. Commit a `raw_trade_ingest` row with `RECEIVED`.
2. Check source-key and payload duplicates, parse the item, bootstrap/match the
   complex, and save match evidence.
3. Insert or deduplicate the normalized trade.
4. Update the raw row to its durable terminal status.

This boundary preserves the source payload even when parsing, matching,
normalization, or status updates fail later. A rollback of the whole item would
violate the raw-first evidence invariant and make failed processing harder to
explain or replay.

The design is not transaction-free. `JdbcNormalizedTradeRepository` uses a
transaction around source-key registry changes, fallback-identity locking,
normalized `trade` insertion, and cancellation. Database uniqueness and
`insertIfAbsent` behavior make repeated ingest idempotent for normalized trade
creation. The cross-repository transaction boundary remains outside that
adapter, so a process failure can leave durable intermediate evidence.

### Batch Execution Correlation

신규 production Batch execution은 canonical UUID `requestId`를 identifying
JobParameter로 요구한다. 이 값은 한 execution의 모든 월×지역
`rtms_ingest_run.execution_correlation_id`에 동일하게 저장되며 FAILED/PARTIAL
run도 예외가 아니다. historical run은 `NULL`을 유지한다.

같은 UUID는 동일 JobInstance restart의 exact identifying parameter set에서만
재사용할 수 있다. 운영 대조의 기준은 시간 추정이 아니라 다음 equality다.

```sql
rtms_ingest_run.execution_correlation_id::text
= batch.BATCH_JOB_EXECUTION_PARAMS.PARAMETER_VALUE
```

Recovery behavior:

- `RawIngestReconciliationRunner` finds `RECEIVED` raw rows already linked to
  an active normalized trade and advances them to `NORMALIZED`.
- Re-running the same source data is safe for normalized trade creation because
  the source-key registry and fallback duplicate policy reject duplicate
  inserts.
- Match-failed evidence remains queryable and can be handled by the rematch
  flow after master or coordinate coverage improves.

The reconciliation runner does not repair every partial state. For example, it
does not infer a missing match-evidence write, retry an arbitrary failed raw
status update, or reinterpret a cancellation. Operators must inspect persistent
`RECEIVED`, `MATCH_FAILED`, `PARSE_FAILED`, and related evidence when counts
remain abnormal. Any future transaction expansion must preserve raw-first
durability and prove that replay, dedupe, cancellation, and failure
queryability remain intact.

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
3. If coordinates are present, upsert `parcel` with coordinates in the
   operational `home_search` database.
4. If coordinates are missing but `aptSeq`, `aptName`, and PNU provide a safe
   identity, upsert a coordinate-pending `parcel` shell with nullable
   coordinates.
5. Upsert `complex` and continue normal matching.

The operational database must not be filled with nationwide coordinate snapshot
tables. In particular, `home_search.reference.parcel_coordinate_snapshot` is not
the target coordinate model.

Coordinate-pending parcels are storage-safe but not marker-safe. They allow
normalized `trade` rows to preserve real RTMS history under a certain
`complex_id`, while public map marker queries continue to require a final
lat/lng or geometry. Coordinate-pending rows should be surfaced to future
operator tooling so an approved `parcel_coordinate_override` or coordinate
source backfill can fill the missing coordinates.

The admin correction queue should keep the reason taxonomy intentionally small
before nationwide storage:

- `PNU_COORDINATE_MISSING`: the PNU/parcel coordinate itself is missing.
- `SAME_PNU_MULTI_COMPLEX`: the parcel has multiple complexes and no trusted
  building-footprint display coordinate, so automatic split would be a guess.
- `COMPLEX_DISPLAY_COORDINATE_MISSING`: at least one same-PNU complex has a
  trusted building-footprint display coordinate, but this complex still needs
  one before it can be shown as a complex-scoped marker.

These reasons must not block RTMS storage. They only explain why a stored trade
is not yet marker-safe or why it remains a parcel fallback marker until an
operator override or trusted coordinate backfill is approved.

Operational RTMS ingest should fail preflight when the coordinate source
database is not configured. A storage-only experiment may explicitly allow
coordinate-pending-only ingest, but that mode is not a marker-display
validation.

Approved `parcel_coordinate_override` rows are manual coordinate corrections by
PNU. They update the existing `parcel` coordinates and keep existing
`complex_id`, raw ingest records, match evidence, and normalized `trade` rows
unchanged. They must not be used to invent coordinates for an identity-unsafe
row.

VWorld VM/WFS is reserved for same-PNU multi-complex marker disambiguation. It
is not the default coordinate provider for ordinary single-complex RTMS
bootstrap.

Coordinate source database inspection must not use nationwide `count(*)` or
other broad scans in the application path. Use exact 19 digit PNU lookup only.
For diagnostics, prefer `pg_class.reltuples`, indexed sample lookup, or bounded
queries with `LIMIT` and a short `statement_timeout`.

### Complex-Region Relation And Region Unit Count

`complex` keeps a direct, nullable `region_id` relation for region marker
aggregation. This relation is derived only from the connected parcel and must
not be guessed independently:

- `complex.region_id` is copied only from `parcel.region_id`.
- The composite foreign key on `(complex.parcel_id, complex.region_id)` keeps
  the parcel and complex region relations consistent.
- `region.unit_cnt_sum` stores the sum of `complex.unit_cnt` for the region
  itself and every descendant region.
- A region with no aggregatable complex keeps `unit_cnt_sum = NULL`, not zero.

Recovery and synchronization use the same full-rebuild model:

1. Flyway V30 repairs existing `parcel.region_id` values from valid 19 digit
   PNU values, preferring the 10 digit region code and falling back to the
   8 digit code.
2. V30 copies the repaired parcel relation to `complex.region_id` and rebuilds
   every `region.unit_cnt_sum`.
3. The daily RTMS refresh runs the same relation and aggregate synchronization
   once after collection completes.
4. Operators may explicitly enable the one-shot region sync runner for manual
   recovery.

An unmatched parcel keeps `region_id = NULL`, is excluded from region sums,
and makes the synchronization result `PARTIAL` without rolling back valid
repairs. A complex/parcel region mismatch after synchronization or a cycle in
the region hierarchy fails the synchronization and rolls back the transaction.
This stored aggregate preserves the existing region marker household-count
field, type, and `NULL` meaning; it is not a trade count, parcel count, or
current-generation-only metric.

## Complex Metadata Enrichment

`complex` rows keep identity data on the ingest path. Optional complex
metadata, such as household count, building count, approval date, and building
areas, is enriched outside the RTMS ingest critical path.

Core metadata is considered complete only when all of these fields are present:

- `dong_cnt > 0`
- `unit_cnt > 0`
- `use_date IS NOT NULL`

Area and ratio fields are useful detail metadata, but missing area values do
not block the legacy core `RESOLVED` status. They do remain collection targets
for the building-metadata state machine:

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

ODC can retain an old legal-dong PNU prefix after an administrative-area
rename. Home Search handles this only through approved rows in
`odcloud_pnu_prefix_alias`:

- `parcel.pnu`, `parcel.region_id`, and `complex.region_id` remain canonical
  operational identities and are never rewritten from an ODC alias.
- The resolver tries the canonical PNU first and uses an `APPROVED` alias only
  after the canonical exact lookup has no candidate.
- ODC `COMPLEX_PK` is an external numeric source identity. It is never compared
  with or copied into RTMS `apt_seq`/`complex_pk`.
- Each attempt stores `lookup_path`, requested canonical PNU, resolved source
  PNU, alias id, and candidate count as durable lookup evidence.

`SOURCE_MISSING` rows retry after 30 and 90 days and then every 180 days so
newly registered buildings can recover without manual replay. Operators can
place a complex on metadata HOLD or request an immediate retry through the
admin surface. HOLD is a latest-state snapshot on `complex`; every retry,
HOLD, alias proposal, approval, and disable action is appended to
`complex_metadata_admin_decision`.

Admin actions do not directly write `dong_cnt`, `unit_cnt`, `use_date`,
`parcel.pnu`, region relationships, `complex_pk`, or `apt_seq`. Alias disable
does not delete metadata that was already resolved.

### Building register evidence and replay (V7)

V7 adds a raw-first building metadata path without changing the public detail
projection:

- `complex_metadata_source_snapshot` stores ODC, recap-title, and title raw
  responses before parsing. `(source_kind, requested_pnu, response_hash)` is
  deduplicated by observation count. Bodies over 2 MiB keep only hash and byte
  size and cannot update a projection.
- `complex_metadata_snapshot_evaluation` appends one immutable evaluation per
  `(snapshot_id, policy_version)`.
- `complex_external_identity` stores `ODC_COMPLEX_PK` and
  `BLD_MGM_BLD_RGST_PK` independently of `apt_seq` and `complex_pk`.
- `complex_building_metadata_state` owns the latest operational state,
  optimistic `state_version`, current evaluation, and pending evaluation.
- `complex_building_metadata_decision` audits identity, alias, retry, HOLD,
  replay, and value-change decisions.

Candidate matching is restricted to one exact 19-digit PNU. Automatic name
matching uses Unicode NFKC plus whitespace/case/separator normalization and
requires an exact, mutually unique result. Contains/trigram scores are admin
recommendations only. Building results are limited to `mainPurpsCd=02000`,
`pageNo=1`, and `numOfRows=100`; a larger `totalCount` is held as
`AMBIGUOUS_OVERSIZED_RESULT`.

Projection application is all-or-review: invalid non-positive values become
`NULL`; matching existing values may fill only missing fields; any difference
against an existing non-null value makes the whole evaluation
`CHANGE_PENDING`. Only the explicit admin approval transaction can overwrite
those values. Parser fixes use snapshot replay and do not call an external API.

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

# Data Storage Strategy

## Building-register profile publication

V34 stores the 83 provider fields without replacing the raw/profile discovery
evidence introduced earlier:

- `building_register_profile_publication` owns source campaign/parse/analysis/
  projection lineage, parser/rules versions, row counts, digest, and immutable
  publication history.
- `building_register_profile_site`, `building_register_profile_building`, and
  `building_register_profile_hierarchy` preserve the 35 SITE, 39 BUILDING, and
  9 HIERARCHY typed fields respectively.
- `building_register_profile_field_evidence` preserves typed values plus
  `ABSENT|NULL|BLANK|ZERO|POSITIVE|VALID|INVALID`, source/aggregation method,
  public scope/quality, and conflict state.
- `complex_building_register_profile_summary` is the read model for public
  detail and ratio-filter queries. Only one publication can be `PUBLISHED`;
  prior rows remain `SUPERSEDED`.

Publication switches only from a row-count-complete `VALIDATED` candidate in
one database transaction. A failed switch leaves the existing `PUBLISHED` row
unchanged. Direct `complex`/`parcel` enrichment is null-only: existing non-null
operational values are never overwritten. Direct ratio columns use verified
complex values; conflict-free PNU fallback remains distinguishable in the
summary and is not written into direct ratio columns.

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
- `CANCELED`
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

RTMS item은 raw receipt와 finalization을 분리한다. 처리 순서는 다음과 같다.

1. `RawReceiptService`가 `REQUIRES_NEW` transaction에서
   `raw_trade_ingest(RECEIVED)`를 먼저 commit한다.
2. `TradeIngestFinalizer`가 별도 transaction에서 source-key/payload dedupe,
   cancellation, parse, complex bootstrap/match, match evidence,
   normalized trade write를 수행한다.
3. 같은 finalizer transaction에서 raw row를 `NORMALIZED`, `DUPLICATE`,
   `CANCELED`, `MATCH_FAILED`, `PARSE_FAILED` 중 하나로 전환한다.

이 경계는 raw-first invariant를 유지하면서 terminal 결과의 partial state를
막는다. Finalizer의 어느 write에서 예외가 발생해도 evidence, registry,
normalized trade, cancellation, terminal status는 모두 rollback되고 먼저
commit된 raw `RECEIVED`만 남는다. 따라서 같은 finalizer로 안전하게 재시도할
수 있다.

`JdbcNormalizedTradeRepository`의 source-key registry, fallback-identity lock,
normalized `trade`, cancellation transaction은 finalizer transaction에 참여한다.
Database uniqueness와 `insertIfAbsent`는 replay 시 normalized trade 중복을
계속 방지한다.

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

- `RawIngestReconciliationRunner`는 active trade 연결 여부로 후보를 제한하지
  않고, limit 안의 모든 `RECEIVED` raw row를 조회한다.
- 저장 payload를 `RawTradeItemParser`로 복원할 수 있는 row는 최초 ingest와
  동일한 `TradeIngestFinalizer`로 재처리한다.
- 복원할 수 없는 payload는 임의로 재해석하지 않고 `RECEIVED`로 유지해
  운영자가 원본 evidence를 확인할 수 있게 한다.
- Source-key registry와 fallback duplicate policy가 replay 중 normalized
  trade 중복을 방지한다.
- `MATCH_FAILED` evidence는 계속 queryable하며 master/coordinate coverage가
  개선된 뒤 별도 rematch flow에서 처리할 수 있다.

### Coordinate Resolution Commit Boundary

외부 building-footprint provider 호출, identity verification, dong matching,
geometry 계산은 transaction 밖에서 수행한다. 계산 완료 후 immutable
`CoordinateResolutionCommitCommand`를 `CoordinateResolutionCommitter`에
전달한다.

Committer transaction은 `complex_building_link`,
`complex_display_coordinate`, terminal `complex_coordinate_case`를 함께
저장한다. Coordinate write 예외가 발생하면 link와 case update까지 모두
rollback된다. `AMBIGUOUS`, `UNAVAILABLE` 같은 업무상 미해결 결과는 coordinate
write 없이 terminal case evidence만 저장한다. 실패 후 동일 case를 재시도해도
처음부터 계산된 commit command가 원자적으로 적용된다.

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

### Versioned Map Marker Read Model

V38 adds an immutable, generation-scoped read model without changing public
map fields or identifier meaning:

- `map_marker_generation` stores lifecycle, source watermark, complex/region
  row counts, and the deterministic SHA-256 marker hash.
- `map_complex_marker_projection` stores marker identity, display coordinates,
  latest trade fields, household count, building age, and the same-complex
  ratio members required by public filters.
- `map_region_marker_projection` stores region-level coordinates and household
  counts.
- `map_marker_active_generation` is a singleton pointer. Its trigger accepts
  only `VALIDATED` candidates, retires the previous active generation, and
  performs the switch in one transaction.

Lifecycle values are `BUILDING`, `VALIDATED`, `ACTIVE`, `RETIRED`, and
`FAILED`. Their durable meaning belongs to `domain/map`; database checks and
the activation trigger enforce the same transition boundary. Projection rows
reference their generation with cascade cleanup, while the active pointer uses
a restrictive reference so the served generation cannot be deleted.

The RTMS daily and backfill jobs run region synchronization before projection
refresh. Complex projection creation is one SQL statement and therefore one
source snapshot; validation and activation are separate transactions, so a
failed candidate never makes partial rows public. Only the active and immediate
rollback generations are retained during normal cleanup. V39 grants the
property runtime explicit privileges for these tables, the identity sequence,
and the activation function; it does not expand the AI reader boundary.

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

### Building metadata attempt evidence (V7)

V7 is intentionally minimal and does not add raw response, snapshot,
evaluation, replay, or external-identity tables. It adds only:

- `complex.bld_mgm_bld_rgst_pk` with a nonblank check and partial unique index.
- `complex_metadata_enrichment_attempt.request_id` for execution correlation.
- `complex_metadata_enrichment_attempt.projection_applied` for explicit
  projection evidence.

ODC gap fill uses the existing `PublicComplexMetadataResolver`, canonical PNU
lookup, approved prefix alias fallback, candidate-name policy, and
`complex_metadata_enrichment_attempt`. It selects only rows where `dong_cnt`,
`unit_cnt`, or `use_date` is missing and no earlier ODC attempt exists. This
one-shot selection lets successive request IDs advance through the cutoff
without consuming quota on rows whose retry date is still in the future. Due
retries remain owned by the existing enrichment retry path. A PNU shared by
multiple complexes is recorded as `AMBIGUOUS` without an external request. ODC
`COMPLEX_PK` is not stored in `apt_seq`, `complex_pk`, or a new identity table.

ODC and building projections are all-or-nothing for conflicts. A candidate may
fill only `NULL` fields, and every existing non-null candidate field must match.
If any value differs, no field from that candidate is projected and the attempt
keeps `projection_applied=false`. `metadata_source` means the source of the last
applied projection, not the last attempted lookup.

Building title collection chooses `BLD_TITLE` only when `dong_cnt == 1`; all
other cases, including `dong_cnt IS NULL`, start with `BLD_RECAP_TITLE`.
The PNU land-category digit is converted to the building-register parameter
instead of copied directly: PNU `1` (ordinary land) becomes `platGbCd=0`, and
PNU `2` (mountain land) becomes `platGbCd=1`. Other PNU land-category digits
are rejected before an external request.
Fallback is allowed only after a successful provider response has zero usable
`mainPurpsCd=02000` candidates. HTTP/provider/parser/oversized/multiple-candidate
failures do not trigger fallback. Responses over 2 MiB are not retained and are
recorded as `PERMANENT` without projection.

Both jobs exclude an already-attempted complex when restarted with the same
`request_id` and share one PostgreSQL advisory lock. ODC capacity uses the
worst-case bound `maxTargets * 2 <= floor(daily quota * 0.9)` because canonical
and approved-alias lookups can each make one request. Building collection checks
the remaining `maxRequests` before every primary or fallback call.

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

## User And AI Data Ownership

`home_search_user` is a separate database owned by user-service. Its `users`
schema stores OAuth identities keyed by `(provider, provider_subject)`, hashed
rotating refresh-token state, and `favorite_complex(user_id, complex_id,
saved_at)`. Email is not an identity key. Favorite `complex_id` is an opaque
property-data identifier: there is no cross-database FK or join, and no name,
address, or price snapshot is stored. The
`home_search_user_runtime` role receives only the DML needed by user-service;
`home_search_user_migrator` alone owns DDL and Flyway history. Only the pinned
external Flyway container receives that credential; user `core` and `app`
artifacts contain neither Flyway nor `db/migration/**` resources.

ai-service owns the `home_search_ai` database and its migration history for
dataset source/acquisition/publication metadata, quality and quarantine evidence,
POI and other reference snapshots, legal corpus, chunks, embeddings, and indexing
evidence. Conversation text, user prompts, and generated answers are not stored in
any server database; browser IndexedDB owns conversation history. ai-service receives
`SELECT` only on domain-filtered `ai_read` views and no permission on property-data
`public` tables. Cross-database joins are forbidden; references such as `complex_id`
remain opaque ids across service boundaries.

Imported reference files must preserve immutable raw bytes and record checksum,
source, acquisition URL, source date, collection time, license, schema, coordinate
system, unique key, and coverage expectation before staging. Only a validated
version can become the atomic active snapshot; rejected rows remain queryable with
a reason and the previous active version remains available for rollback.

## Acceptance Criteria

- Raw rows are created before normalized insert.
- Duplicate collection does not create duplicate normalized trades.
- Failed matches are queryable.
- Normalized trades can be joined to complex and parcel by `complex_id`.
- Map marker APIs work without ranking or trend tables.

## Additive Market Insight Storage

ADR 0002 adds collection-completeness evidence and materialized insight
snapshots without changing raw-first ingest or normalized trade identity.

- `rtms_collection_execution` identifies one `requestId`, collection mode,
  scope, run date, state, planned work-unit count, timestamps, and redacted
  failure reason.
- `rtms_collection_work_unit` stores the unique
  `(execution_id, lawd_cd, deal_ymd)` plan and its terminal result/run link.
- `raw_trade_ingest.execution_correlation_id` is nullable for historical rows
  and required by the application for new production RTMS executions.
- `market_insight_snapshot` stores period, scope, region, coverage counts,
  cutoff, build status, and the daily source execution when applicable.
- `market_insight_snapshot_execution` stores the seven exact DAILY execution
  lineages for every weekly scope snapshot.
- `market_insight_trade_item` stores at most 50 ranked items per metric and
  keeps internal trade/comparison/complex relations plus exact `excl_area`.

One eligible nationwide execution publishes one nationwide snapshot plus one
snapshot for every root `si-do` row in the same application transaction. Each
scope independently ranks newly disclosed trades, highest deals, exact-area
record highs, previous-distinct-date rises/falls, and cancellation corrections.
An empty SIDO scope is still a published fresh snapshot with zero items.

Property migrations V15-V19 add this storage and grant
`home_search_property_runtime` only `SELECT`, `INSERT`, and `UPDATE` on the
four insight evidence/snapshot tables. Runtime `DELETE` remains denied; data
retention and cleanup stay under an explicit maintenance path.

Nationwide publication requires a `DAILY`, `NATIONWIDE` execution with every
planned work unit terminal and `COMPLETED`. A complete execution with zero new
normalized trades still produces a fresh empty snapshot. Rejected builds do
not replace the last published snapshot. Snapshot/item retention is 400 days;
this retention never deletes raw ingest or normalized trade evidence.

A rolling publication uses exactly the latest `DAILY/NATIONWIDE` execution for
its `runDate`; it does not require or rebuild the prior six DAILY executions.
V19 stores tolerant `rgstDate`/`cdealDay` raw and parsed evidence on
`raw_trade_ingest`, adds `ROLLING_7D`, date-quality counters, and
`SUPERSEDED` lineage. Source identities join through
`trade_source_key_registry(source, source_key)`, so a current execution's
`DUPLICATE` raw row can reuse its canonical trade while still participating in
the current snapshot.

For registration-based rolling metrics, a usable `registration_date` is the
preferred window and ordering date. If it is missing or malformed, an
uncanceled canonical trade uses `trade.deal_date` as the fallback. The
registration quality counters then describe the unique in-window source
identities included through that fallback, not rows dropped from the ranking.
Canceled identities are excluded from all five registration-based metrics and
remain available to the cancellation metric only when `cancellation_date` is
usable.

All five registration-based metrics require `trade.deal_date` in
`period_end - 1 calendar month .. period_end`. Exact-area record/rise/fall
metrics additionally require the immediately preceding deal date for the exact
same `(complex_id, excl_area)` to be within six calendar months of the current
deal. Multiple rows on that preceding date keep the deterministic median
representative. The record-high baseline remains the all-time historical
maximum, gated by the existence of that recent comparable previous deal.

One transaction creates and publishes nationwide plus all 17 SIDO scopes.
Re-invoking the same source execution is idempotent. A newer complete execution
for the same `runDate` atomically changes the old 18 rows to `SUPERSEDED` and
the replacements to `PUBLISHED`; a calculation or coverage failure leaves the
prior `PUBLISHED` rows intact. Existing `WEEKLY`, `REJECTED`, and V18 lineage
evidence is immutable historical evidence and is not rewritten.

## Additive Market News Storage

V20 adds `market_news_collection_execution`,
`market_news_collection_work_unit`, `market_news_raw_item`,
`market_news_article`, `market_news_relation`, `market_news_snapshot`,
`market_news_snapshot_item`, `market_news_major_complex_selection`, and
`market_news_quality_label`. V21 grants the property runtime only the table and
identity-sequence privileges required by the Batch/API adapters. V24 adds
`market_news_quality_review_set`, binds quality labels to their review set,
article, and relation with FKs, and grants only the new review-set table to the
property runtime. V25 pins the exact publication snapshots used by each review
set in `market_news_quality_review_snapshot`. V27 records and reconciles only
terminal execution counts that can be derived without ambiguity from persisted
work units and raw items. The correction table preserves both the prior and
derived values; article and relation counts are not rewritten because later
policy runs may legitimately share those rows. V28 preserves and corrects the
historic execution failure cause when a provider `AUTHENTICATION` or
`DAILY_QUOTA` work-unit failure was hidden by the remaining units' budget-skip
summary. V29 grants the runtime only `SELECT` and `DELETE` on the two
execution-correction audit tables so the 180-day retention transaction can
delete that child evidence before its execution row. V1-V28 remain unchanged.

The grains are `(work_unit_id, provider_start, provider_rank)` for raw,
`(provider, canonical_url_hash)` for article,
`(article_id, policy_version, relation_type, region_code, complex_id)` for
relation, `(snapshot_id, article_id)` for snapshot item, and
`(review_set_id, article_id, relation_id)` for quality labels. Raw rows precede
article creation. Rejected items retain a stable `NewsRejectionReason`.
Quality sampling is deterministic for `(review_set_id, policy_version)`;
category, 17-SIDO, relation, duplicate/short-name challenge, and URL coverage
are stored on the review set. A missing minimum is durable
`INSUFFICIENT_SAMPLE`, not an implicit pass.
`NEWS_V3` preserves the `NEWS_V2` matcher and rejection rules while adding a
second SIDO and major-complex query template. This increases the candidate
pool without weakening region or direct-complex evidence requirements.
`NEWS_V4` rejects a direct-complex match when the matched complex token equals
its SIDO, SIGUNGU, or DONG name after removing the administrative suffix.
Public reads apply the same guard to older stored relations, so evidence such
as a model-house address cannot keep a geographic-only complex name exposed as
`DIRECT_COMPLEX`; raw and relation rows remain queryable.
`NEWS_V5` preserves that precision rule, limits the second major-complex query
to duplicated or four-character-or-shorter name/alias challenges, and applies
the configured KST daily call budget across executions.
Once captured, a review set never switches to newer or withdrawn publication
snapshots on retry. Snapshot/article/relation rows referenced by a review set
are retained with the 180-day quality evidence and then removed child-first.
Execution aggregate/failure correction rows follow the same 180-day execution
cutoff and are deleted before work units and execution rows to preserve FK
ordering.

Snapshot transitions are `BUILDING -> PUBLISHED|REJECTED` and
`PUBLISHED -> SUPERSEDED|WITHDRAWN`. Execution transitions are
`PLANNED -> RUNNING -> COMPLETED|PARTIAL|FAILED`; work-unit transitions are
`PLANNED -> RUNNING -> COMPLETED|TRUNCATED|FAILED|SKIPPED_BUDGET`.
Before the current pointer changes, the automatic hard gate checks item count,
canonical uniqueness, title/HTTP(S) URL validity, relation policy/category
lineage, and the 30-day/future-time boundary. A failed build remains
`REJECTED`. A reviewed publication can be moved to `WITHDRAWN` with a stable
`MarketNewsWithdrawalReason`; the prior `SUPERSEDED` snapshot remains the
queryable last-good result.
Raw retention is seven days, normalized news 30 days, and execution/review
evidence 180 days. Retention deletes children before parents and never removes
the current published snapshot.

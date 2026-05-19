# Data Storage Strategy

## Goal

failed processing을 explain하고 repeat할 수 있을 정도로 real-estate trade data를 안전하게 저장한다. V1은 aggregate analytics보다 correctness, traceability, map display를 우선한다.

Fixed paths:

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Target repository: `/Users/gwongwangjae/home-search`

## Source Findings

현재 trade collection code:

- `ApisClient.getAptTrade(...)`
- `TradeDailyCollectService`
- `TradeDailyCollectTasklet`
- `TradeInitTasklet`
- `ComplexResolveService`
- `TradeBulkWriter`

현재 persistence mismatch:

- `domain/trade/Trade.java`는 `trade.complex_id`를 mapping한다.
- `DetailUseCase.findAllTradeByParcelId(...)`는 complex IDs로 query한다.
- `TradeBulkWriter`는 `complex_pk`, `apt_seq`, `source`, `source_key`를 insert한다.
- Batch migration `V12__create_trade_partitioned_table.sql`은 `complex_pk`를 중심으로 partitioned `trade` table을 만든다.

V1은 `complex_id`를 operational relation으로 사용하고 source identifiers를 audit와 deduplication을 위해 유지함으로써 이를 해결한다.

## V1 Storage Model

두 layers를 사용한다:

1. Raw ingest records.
2. Normalized operational trades.

### Raw Layer

Purpose:

- external API source data를 보존한다.
- code changes 후 replay를 지원한다.
- failed parsing 또는 failed complex matching을 설명한다.
- external fields가 이상하지만 복구 가능한 경우 data loss를 피한다.

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

- map marker와 detail APIs를 제공한다.
- 실제 trade event마다 하나의 clean row를 유지한다.
- row를 debug할 수 있는 source metadata를 충분히 유지한다.

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

- `source + source_key`로 unique.

Fallback dedupe rule:

- `complex_id + deal_date + floor + excl_area + deal_amount`로 unique.

historical RTMS data가 항상 완벽하게 stable한 source key를 제공하지 않을 수 있어 fallback이 존재한다. 이는 main identity가 아니라 safety net으로 취급해야 한다.

## Source Key

source fields에서 deterministic하게 `source_key`를 생성한다. RTMS apartment trades의 경우 trade event를 최대한 식별하는 모든 fields를 포함한다:

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

hashing 또는 joining 전에 whitespace와 comma-formatted amounts를 normalize한다.

## Complex Matching

source code의 현재 matching order:

1. `aptSeq -> complex.complex_pk`
2. PNU exact single match.
3. PNU plus apartment name score.
4. Name plus `umdCd` fallback in `TradeInitTasklet`.

V1은 matching intent를 보존하되 match outcomes를 기록해야 한다:

- Match path.
- Matched `complex_id`.
- Matched `complex_pk`.
- Failure reason if no match.

raw record 또는 failed-match record 없이 trade가 사라지면 안 된다.

## Partitioning

`deal_date` 기준 trade partitioning을 유지한다.

Required behavior:

- supported historical years를 위한 yearly partitions.
- unexpected dates를 위한 `trade_default` partition.
- complex별 latest trade lookup을 위한 index.
- map/detail query paths를 위한 index.

정확한 partition year range는 migration 중 안전하게 생성되거나 확장되어야 한다. 마지막 year 이후 조용히 실패하는 range를 hard-code하지 않는다.

## Map Display Query Boundary

V1 map display에는 다음만 필요하다:

- Parcel position.
- Parcel geometry bounds filter.
- Complex unit count.
- parcel 또는 complex의 latest available trade amount.

다음 때문에 V1을 막지 않는다:

- Regional trend calculations.
- 30-day top price.
- 30-day top volume.
- Ranking materialization.

## Acceptance Criteria

- Raw rows가 normalized insert보다 먼저 생성된다.
- Duplicate collection이 duplicate normalized trades를 만들지 않는다.
- Failed matches가 queryable하다.
- Normalized trades를 `complex_id`로 complex 및 parcel에 join할 수 있다.
- Map marker APIs가 ranking 또는 trend tables 없이 동작한다.

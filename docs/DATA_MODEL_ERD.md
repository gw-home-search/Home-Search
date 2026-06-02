# Data Model ERD


## Purpose

This document fixes the operational Home Search ERD and the boundary between
the operational database and the coordinate source database.

The public API surface is unchanged. The ERD exists to prevent future work from
mixing coordinate source storage with operational map and trade storage.

## Database Roles

```text
Coordinate Source DB
  home_search_coordinate_full_durable_*
  read-only lookup source for PNU coordinates

Operational DB
  home_search
  owns map, detail, trade, ingest, and evidence data
```

The two databases have no foreign keys between them. The relationship is a
read-only lookup dependency by PNU.

## Coordinate Source DB

Physical storage may use:

```text
reference.parcel_coordinate_snapshot
  pnu PK
  latitude
  longitude
  geom
  snapshot_version
```

This table is not part of the operational ERD. It is mentioned only to identify
the source table that the coordinate source lookup reads from.

## Operational DB ERD

```text
region
  id PK
  code
  name
  parent_id

parcel
  id PK
  region_id FK -> region.id
  pnu UNIQUE
  address
  latitude
  longitude
  geom

complex
  id PK
  parcel_id FK -> parcel.id
  complex_pk UNIQUE
  apt_seq
  name
  trade_name
  dong_cnt
  unit_cnt
  use_date
  metadata_status

complex_display_coordinate
  complex_id FK -> complex.id
  latitude
  longitude
  source
  confidence
  reason

raw_trade_ingest
  id PK
  source
  source_key
  lawd_cd
  deal_ymd
  page_no
  payload
  payload_hash
  status
  failure_reason

trade_match_evidence
  id PK
  raw_ingest_id FK -> raw_trade_ingest.id
  matched_complex_id FK -> complex.id nullable
  match_status
  apt_seq
  derived_pnu
  failure_reason

trade_source_key_registry
  source
  source_key
  raw_ingest_id FK -> raw_trade_ingest.id
  trade_id FK -> trade.id nullable

trade
  id PK
  complex_id FK -> complex.id
  raw_ingest_id FK -> raw_trade_ingest.id
  deal_date
  deal_amount
  floor
  excl_area
  apt_dong
  source
  source_key
  complex_pk
  apt_seq
  deleted_at
```

## RTMS Bootstrap Flow

```text
raw_trade_ingest
  -> derive PNU
  -> Coordinate Source DB lookup by PNU
  -> parcel upsert
  -> complex upsert
  -> complex matcher
  -> trade_match_evidence
  -> trade insert when matched
```

`trade` is inserted only when a `complex_id` is certain.

## Coordinate Flow

```text
Coordinate Source DB
  reference.parcel_coordinate_snapshot
      |
      | read-only lookup by pnu
      v
Operational DB
  parcel.latitude
  parcel.longitude
  parcel.geom
```

`parcel.geom` is preferred when the coordinate source provides geometry. The
field remains nullable so lat/lng-only fallback data can still support map
markers.

## Same-PNU Multi-Complex Flow

```text
parcel
  pnu = X
    |
    +-- complex A
    +-- complex B

if markers overlap and complex-level split is required:
  use VWorld VM/WFS
  store result in complex_display_coordinate
```

The map marker read path should use:

```text
complex_display_coordinate if present
else parcel latitude/longitude
```

VWorld VM/WFS is not the ordinary parcel coordinate provider.

## Excluded From Operational ERD

The operational `home_search` ERD excludes:

- Nationwide coordinate snapshot tables.
- Coordinate import stage tables.
- Coordinate publish checkpoint tables.
- Coordinate import run evidence tables.
- Ranking, favorite, alarm, mail, recommendation, and heavy analytics tables.

These can exist in separate source or later-scope systems, but they are not part
of the current Home Search operational model.

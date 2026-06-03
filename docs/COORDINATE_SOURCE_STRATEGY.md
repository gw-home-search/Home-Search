# Coordinate Source Strategy


## Purpose

This document fixes how Home Search obtains parcel coordinates during RTMS
ingest and map display.

Home Search has two separate database roles:

- `home_search`: operational database for public APIs and ingest results.
- `home_search_coordinate_full_durable_*`: coordinate source database used as a
  read-only PNU coordinate lookup source.

The coordinate source database is not the operational database. Its internal
tables are not part of the `home_search` operational ERD, even when those table
names contain `reference`.

## Fixed Decision

Home Search must not populate or depend on
`home_search.reference.parcel_coordinate_snapshot` as the operational coordinate
path.

Instead, the backend should connect directly to the coordinate source database
and read coordinates by PNU.

```text
RTMS row
  -> derive PNU
  -> lookup PNU in Coordinate Source DB
  -> upsert home_search.parcel
  -> bootstrap home_search.complex
  -> insert home_search.trade
```

The coordinate source database may physically store its coordinate rows in a
table named `reference.parcel_coordinate_snapshot`. That physical table name is
an implementation detail of the coordinate source database only.

## Coordinate Lookup Contract

Lookup key:

- `pnu`: 19 digit PNU derived from RTMS `sggCd`, `umdCd`, and `jibun`.

Lookup result:

- `latitude`: required.
- `longitude`: required.
- `geom`: preferred when available.

The operational database stores the lookup result in `parcel`:

- `parcel.pnu`
- `parcel.latitude`
- `parcel.longitude`
- `parcel.geom`

`parcel.latitude`, `parcel.longitude`, and `parcel.geom` are nullable in the
operational database. A nullable coordinate means the RTMS identity was
display-safe enough to store, but the parcel is still coordinate-pending and
must not produce a map marker until a trusted coordinate is supplied.

`parcel.geom` remains nullable because the public map display can operate with
lat/lng when those are available. Source geometry should be copied when the
coordinate source lookup provides it.

## Coordinate Source Priority

Default coordinate source:

1. Coordinate Source DB lookup by PNU.
2. Approved `parcel_coordinate_override` by PNU.

VWorld VM/WFS is not the default coordinate provider for ordinary RTMS ingest.
Do not call VWorld VM/WFS for a normal single-complex PNU bootstrap when the
coordinate source database has parcel coordinates.

If both the coordinate source database and approved override miss, Home Search
may still create a coordinate-pending `parcel` and `complex` when `aptSeq`,
`aptName`, and derived PNU are sufficient for a safe complex identity. This
improves storage completeness without inventing coordinates.

Approved override policy:

- `parcel_coordinate_override` is an operator-approved correction source for
  identity-safe parcels whose coordinate source lookup is missing or stale.
- Approved override rows must be `HIGH` confidence and must record the operator
  and reason.
- Approval updates the existing `parcel.latitude` and `parcel.longitude` by
  PNU. It must not create a new parcel, complex, or trade row.
- After approval, existing `complex_id` and normalized `trade` rows remain the
  operational relation, and the marker query may return the parcel through the
  normal lat/lng fallback path.

Coordinate-pending storage rules:

- `parcel.pnu`, address evidence, `complex.apt_seq`, `complex.complex_pk`, and
  aliases are stored.
- `parcel.latitude`, `parcel.longitude`, and `parcel.geom` remain `NULL`.
- Normalized `trade` may be inserted because it has a certain `complex_id`.
- Public map marker queries must exclude rows whose final marker coordinate is
  missing.
- Detail and trade read paths may return the stored identity and trade rows, but
  coordinate fields remain nullable until an approved coordinate is available.
- Later approved overrides or coordinate source backfill may update the existing
  parcel instead of creating a second parcel or complex.

RTMS ingest preflight:

- When RTMS ingest is enabled, the application must verify that
  `COORDINATE_SOURCE_DB_JDBC_URL` is configured and that the coordinate source
  database exposes `reference.parcel_coordinate_snapshot`.
- The preflight runs before live RTMS fetch and DB ingest so an environment
  mistake cannot silently store every identity-safe row as coordinate-pending.
- Storage-only experiments may bypass this check only by explicitly setting
  `HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=true`.
- The bypass is not marker-safe. It allows raw/master/trade storage but leaves
  public map markers hidden until approved coordinates are supplied.

## VWorld VM/WFS Policy

Use VWorld VM/WFS only for same-PNU multi-complex display disambiguation.

Allowed case:

- The PNU exists in the Coordinate Source DB.
- `home_search` has, or is creating, two or more `complex` rows under the same
  `parcel.pnu`.
- Parcel-level coordinates would collapse distinct complex markers onto one
  location.

Storage target for VWorld VM/WFS results:

- `complex_display_coordinate`

Marker coordinate selection:

```text
if complex_display_coordinate exists:
    use complex-level coordinate
else:
    use parcel.latitude / parcel.longitude
```

When VWorld VM/WFS cannot confidently split same-PNU complexes, keep the parcel
coordinate fallback and leave explainable evidence instead of guessing a
complex-level marker.

VWorld VM/WFS is used only after the normal PNU coordinate source succeeds and
the operational DB has a same-PNU multi-complex candidate. The readiness flow
may fetch VWorld building footprints for the exact 19 digit PNU, store them in
`building_footprint_snapshot`, and then resolve complex display coordinates.

Identity and matching rules:

- ODC identity verification is the default path: when an ODC service key is
  configured, Home Search attempts to confirm that each `complex.apt_seq`
  belongs to the same parcel PNU before VWorld coordinates are promoted.
  Without a key it degrades to a non-blocking fallback, and
  `complex.coordinate.identity.odcloud.enabled=false` disables it explicitly.
- If ODC returns multiple PNU candidates or a conflicting PNU, keep the case as
  `AMBIGUOUS` instead of storing a guessed display coordinate.
- If ODC returns no exact candidate or an external failure, the default policy
  continues with VWorld footprint matching and records the result from that
  matching step. Strict deployments may set
  `complex.coordinate.identity.block-on-unavailable=true` or
  `complex.coordinate.identity.block-on-failed=true` to hold those cases as
  `UNAVAILABLE` or `FAILED`.
- VWorld footprint `dong_nm` is matched to RTMS `trade.apt_dong` after
  normalization such as `1001동 -> 1001`.
- A complex may match multiple building footprints. In that case Home Search
  stores an aggregate complex display coordinate from the matched footprints.
- If the same footprint matches multiple complexes, or one normalized dong token
  maps to duplicate footprint candidates, the case is `AMBIGUOUS`.
- `REDEVELOPED` relation cases are not split into concurrent markers by
  default; they keep the current-generation representative marker policy.

## Operational Database Boundary

The operational database owns:

- `region`
- `parcel`
- `complex`
- `complex_display_coordinate`
- `raw_trade_ingest`
- `trade_match_evidence`
- `trade_source_key_registry`
- `trade`

The operational database does not own:

- Nationwide coordinate snapshots.
- Coordinate source import stage tables.
- Coordinate source publish/checkpoint tables.
- Coordinate source run evidence.

Those belong to the coordinate source database or to offline coordinate data
preparation.

## Ingest Invariants

- Raw RTMS rows are saved before normalized trades.
- A normalized `trade` row is created only after a `complex_id` is certain.
- Coordinate lookup failure must not create fake coordinates.
- Coordinate lookup failure must not block identity-safe master/trade storage
  when `aptSeq`, `aptName`, and PNU are certain.
- Coordinate-pending rows must stay out of public map markers until coordinates
  are supplied.
- True identity failures such as PNU conflict, ambiguous candidates, missing
  PNU, or name conflict must leave queryable raw/evidence state.
- `complex_id` remains the operational relation.
- `complex_pk`, `apt_seq`, `source`, and `source_key` remain audit and dedupe
  metadata.

## Configuration Direction

Operational DB:

```text
DB_JDBC_URL=jdbc:postgresql://postgis:5432/home_search
DB_USERNAME=home_search
DB_PASSWORD=...
```

Coordinate Source DB:

```text
COORDINATE_SOURCE_DB_JDBC_URL=jdbc:postgresql://postgis:5432/home_search_coordinate_full_durable_20260527182147
COORDINATE_SOURCE_DB_USERNAME=home_search
COORDINATE_SOURCE_DB_PASSWORD=...
HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=false
```

The coordinate source connection should be treated as read-only by application
code.

Coordinate source queries must stay narrow:

- Lookup only by exact 19 digit `pnu`.
- Do not run application queries that count or scan the nationwide source table.
- Use `LIMIT` and estimated catalog metadata for diagnostics instead of
  `count(*)`.
- Keep short `statement_timeout`, `lock_timeout`, and socket timeout values on
  the coordinate source connection.

## Current Implementation

```text
JdbcComplexMasterBootstrapper
  -> ParcelCoordinateResolver
  -> CoordinateSourceFirstParcelCoordinateResolver
  -> CoordinateSourceDbParcelCoordinateLookup
```

VWorld VM/WFS is attached to same-PNU multi-complex display disambiguation, not
to ordinary parcel bootstrap.

When `ParcelCoordinateResolver` returns empty, `JdbcComplexMasterBootstrapper`
creates a coordinate-pending parcel shell instead of failing the master
bootstrap, as long as the RTMS row has a valid PNU and stable complex identity.

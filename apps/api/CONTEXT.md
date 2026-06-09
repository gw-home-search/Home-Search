# apps/api Context


This file defines backend-specific Home Search terms. Canonical decisions remain in root `docs/*.md`.

## Backend Shape

**API app** is the Spring Boot backend under `apps/api`.

**Layered backend** means `application`, `domain`, `infrastructure`, and `global` responsibilities remain separate.

**Web layer** owns controllers, DTOs, validation, and project HTTP behavior.

**Application layer** owns use cases such as map marker lookup, region navigation, search, detail, trade list, and ingest orchestration.

**Domain layer** owns region, parcel, complex, trade, and ingest concepts.

**Persistence layer** owns repository queries, Flyway migrations, PostGIS access, uniqueness, and partitioning behavior.

## Data Terms

**RTMS** is the apartment trade source collected through public data APIs.

**Raw ingest record** is the audit record saved before parsing, matching, and normalized insertion.

**Normalized trade** is the row queried by map, detail, and trade APIs.

**Duplicate-safe ingest** means repeated collection does not create duplicate normalized trades.

**Complex matching** resolves an external trade record to an internal complex.

**Failed match** means complex matching failed and the result remains queryable with a reason.

**ProblemDetail** is the project backend error response style.

**PostGIS bounds query** finds parcels or marker candidates inside map bounds.

**Flyway project baseline** creates only baseline tables and indexes needed for collection, storage, and map display.

## Backend Boundary Patterns

**Source identity** means durable ingest identifiers such as `source` and
`source_key`. When validation or normalization is shared across raw evidence,
match evidence, and normalized trade commands, keep the project meaning in
`domain/ingest/source` and let application records keep their existing string
components.

Do not introduce `common`, `shared`, or `util` packages for repeated constructor
checks. Put reusable rules in the feature and layer that own the reason they
change, and keep provider DTOs, JDBC row mapping, and public web DTOs out of
domain value objects.

## Backend Non-Scope

The API app must not make map or trade endpoints depend on ranking, trend, favorite, alarm, mail, recommendation, auth, or heavy analytics state.

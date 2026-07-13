# apps/property-data Context


This file defines backend-specific Home Search terms. Canonical decisions remain in root `docs/*.md`.

## Backend Shape

**Property-data service** is the Spring Boot backend under `apps/property-data`. It owns
RTMS ingest, map/trade HTTP APIs, domain logic, persistence, and the operational
`home_search` database.

**API app** is the current HTTP execution mode of the property-data service. A
current `api` module isolates this mode without creating a separate MSA service
or database.

**Batch** is the current RTMS batch execution mode of the same property-data
service. It uses the `batch` directory and keeps the same
`home_search` ownership boundary.

**External database CLI** is the explicit run-and-exit Flyway mode backed by
`db/migration/api`, `db/flyway.conf`, and `ops/property-flyway.sh`. API and Batch
keep Flyway auto-execution disabled and do not package Flyway or migration SQL.
The completed local Java-to-SQL history cutover remains sanitized documentation
evidence only; its executable one-time repair command has been removed.

**Layered backend** means `application`, `domain`, `infrastructure`, and `global` responsibilities remain separate.

**Web layer** owns controllers, DTOs, validation, and project HTTP behavior.

**Application layer** owns use cases such as map marker lookup, region navigation, search, detail, trade list, and ingest orchestration.

**Domain layer** owns region, parcel, complex, trade, and ingest concepts.

**Persistence layer** owns repository queries, PostGIS access, uniqueness, and
partitioning behavior. External SQL catalog ownership stays under `db/`.

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

**Ingest item processing** keeps batch orchestration separate from item
outcomes. `OpenApiTradeIngestService` should coordinate a batch and metrics,
while item-level raw save, duplicate/cancel/parse/match/evidence/normalized
insert decisions belong in an application processor under `application/ingest`.

**Raw ingest transitions** are domain-owned status/reason pairs. When
application code updates raw ingest evidence, prefer a domain transition object
over repeating `RawTradeIngestStatus` and stored `failure_reason` string
combinations in service branches.

**Large JDBC SQL** may stay in infrastructure, but repository classes should
focus on choosing a query, binding parameters, and row mapping. Long SQL text
blocks can be held by feature-local SQL provider classes under the same
`infrastructure/persistence/<feature>` package.

## Backend Non-Scope

The property-data service must not make map or trade endpoints depend on ranking,
trend, favorite, alarm, mail, recommendation, auth, or heavy analytics state.

# apps/api Context KO

> KO 생성 기준: canonical source only
> Source: `apps/api/CONTEXT.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `apps/api/CONTEXT.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

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

## Backend Non-Scope

The API app must not make map or trade endpoints depend on ranking, trend, favorite, alarm, mail, recommendation, auth, or heavy analytics state.

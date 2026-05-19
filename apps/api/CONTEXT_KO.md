# apps/api Context

이 파일은 backend-specific Home Search V1 terms를 정의한다. canonical decisions는 root `docs/*.md`에 남아 있다.

## Backend Shape

**API app**은 `apps/api` 아래의 Spring Boot backend다.

**Layered backend**는 `application`, `domain`, `infrastructure`, `global` responsibilities가 분리되어 유지된다는 뜻이다.

**Web layer**는 controllers, DTOs, validation, V1 HTTP behavior를 담당한다.

**Application layer**는 map marker lookup, region navigation, search, detail, trade list, ingest orchestration 같은 use cases를 담당한다.

**Domain layer**는 region, parcel, complex, trade, ingest concepts를 담당한다.

**Persistence layer**는 repository queries, Flyway migrations, PostGIS access, uniqueness, partitioning behavior를 담당한다.

## Data Terms

**RTMS**는 public data APIs를 통해 수집되는 apartment trade source다.

**Raw ingest record**는 parsing, matching, normalized insertion 전에 저장되는 audit record다.

**Normalized trade**는 map, detail, trade APIs가 query하는 row다.

**Duplicate-safe ingest**는 반복 collection이 duplicate normalized trades를 만들지 않는다는 뜻이다.

**Complex matching**은 external trade record를 internal complex로 resolve한다.

**Failed match**는 complex matching이 실패했고 그 결과가 reason과 함께 queryable하게 남아 있다는 뜻이다.

**ProblemDetail**은 V1 backend error response style이다.

**PostGIS bounds query**는 map bounds 안의 parcels 또는 marker candidates를 찾는다.

**Flyway V1 migration**은 collection, storage, map display에 필요한 V1 tables와 indexes만 만든다.

## Backend Non-Scope

API app은 V1 map 또는 trade endpoints가 ranking, trend, favorite, alarm, mail, recommendation, auth, heavy analytics state에 의존하게 만들면 안 된다.

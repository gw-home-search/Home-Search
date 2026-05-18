# apps/api Context

이 문서는 `apps/api/CONTEXT.md`의 한국어 companion이다. 기준은 영문 원문이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## Backend Shape

**API app**은 `apps/api` 아래의 Spring Boot backend다.

**Layered backend**는 `application`, `domain`, `infrastructure`, `global` 책임을 분리해 유지한다는 뜻이다.

**Web layer**는 controller, DTO, validation, V1 HTTP behavior를 담당한다.

**Application layer**는 map marker lookup, region navigation, search, detail, trade list, ingest orchestration 같은 use case를 담당한다.

**Domain layer**는 region, parcel, complex, trade, ingest concept를 담당한다.

**Persistence layer**는 repository query, Flyway migration, PostGIS access, uniqueness, partitioning behavior를 담당한다.

## Data Terms

**RTMS**는 public data API를 통해 수집하는 apartment trade source다.

**Raw ingest record**는 parsing, matching, normalized insertion 전에 저장되는 audit record다.

**Normalized trade**는 map, detail, trade API에서 조회하는 row다.

**Duplicate-safe ingest**는 반복 수집이 duplicate normalized trade를 만들지 않는다는 뜻이다.

**Complex matching**은 external trade record를 internal complex로 resolve한다.

**Failed match**는 complex matching이 실패했고 그 결과가 reason과 함께 queryable하게 남는다는 뜻이다.

**ProblemDetail**은 V1 backend error response style이다.

**PostGIS bounds query**는 map bounds 내부의 parcel 또는 marker candidate를 찾는다.

**Flyway V1 migration**은 V1 collection, storage, map display에 필요한 table과 index만 만든다.

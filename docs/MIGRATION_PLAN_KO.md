# V1 Migration Plan

## Summary

V1은 minimum safe product를 migrate한다: apartment trade data를 수집하고, source data를 보존하고, operational tables로 normalize한 뒤 map에 표시한다.

Fixed locations:

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Target repository: `/Users/gwongwangjae/home-search`

## Phase 0 - Documentation Baseline

Target: `/Users/gwongwangjae/home-search/docs`

code를 옮기기 전에 이 directory의 docs를 만들고 유지한다. 이 docs는 future backend와 frontend work를 위한 migration contract다.

Done when:

- V1 included 및 excluded scope가 explicit하다.
- Source와 target paths가 문서화되어 있다.
- 보존할 API surface가 문서화되어 있다.
- trade storage model이 결정되어 있다.
- 알려진 source-code risks가 기록되어 있다.

## Phase 1 - Target Structure

Target:

- `/Users/gwongwangjae/home-search/apps/api`
- `/Users/gwongwangjae/home-search/apps/web`
- `/Users/gwongwangjae/home-search/infra`

repository를 monorepo-style migration target으로 준비한다.

Rules:

- repository root에 backend와 frontend files를 섞지 않는다.
- backend Gradle files는 `apps/api` 안에 둔다.
- frontend Vite files는 `apps/web` 안에 둔다.
- tool이 root-level file을 요구하지 않는 한 Docker, Postgres, monitoring, deployment files는 `infra` 안에 둔다.

## Phase 2 - Database and Storage Baseline

Source references:

- `src/main/resources/db/migration/api`
- `src/main/resources/db/migration/batch`
- `src/main/java/com/home/domain`
- `src/main/java/com/home/infrastructure/batch/trade`

Target behavior:

- `region`, `parcel`, `complex`, `trade`를 V1 core tables로 유지한다.
- parcel bounds lookup에는 PostGIS를 유지한다.
- normalized trade insert 전에 raw trade preservation을 추가하거나 formalize한다.
- `complex_id`와 `complex_pk` 사이의 source mismatch를 해결한다.

V1 excludes:

- `trade_top_price_30d`
- `trade_top_volume_30d`
- `region_trade_trend`
- `mail_target`
- batch mail indexes

Done when:

- fresh database가 V1 migrations를 실행할 수 있다.
- region, parcel, complex, trade seed rows가 map APIs를 지원한다.
- Duplicate ingest가 방지된다.
- Failed matches를 inspect할 수 있다.

## Phase 3 - Backend Migration

Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`

Target backend: `/Users/gwongwangjae/home-search/apps/api`

다음 순서로 migrate한다:

1. Build/runtime baseline: Gradle, Spring Boot app entrypoint, profiles.
2. Domain baseline: `region`, `parcel`, `complex`, `trade`.
3. DB baseline: Flyway V1 schema and PostGIS setup.
4. Public data client: RTMS apartment trade collection.
5. Ingest service: raw save, resolve complex, normalized trade insert.
6. API controllers: map, region, search, detail, trade.
7. Error handling and validation.
8. V1 tests.

V2 features를 critical path로 migrate하지 않는다. rankings, favorites, OAuth-dependent user flows, mail alarms는 분리한다.

## Phase 4 - Frontend Migration

Source frontend: `/Users/gwongwangjae/frontend/home-client`

Target frontend: `/Users/gwongwangjae/home-search/apps/web`

다음 순서로 migrate한다:

1. Vite React runtime and env handling.
2. Axios base URL handling.
3. Kakao map render path.
4. Map marker fetch flow.
5. Search and region navigation.
6. Detail and trade side panel.
7. UI/UX redesign around full-map exploration.

frontend는 `API_CONTRACT.md`에 문서화된 V1 API URLs를 계속 호출해야 한다.

## Phase 5 - Integration

작고 deterministic한 dataset을 사용한다:

- One SIDO, one SIGUNGU, one EUP_MYEON_DONG.
- geometry가 있는 one parcel.
- 해당 parcel에 linked된 one complex.
- complex의 multiple trades.
- one duplicate trade ingest attempt.
- one failed match ingest case.

Done when:

- Map bounds API가 parcel marker를 반환한다.
- Marker click이 detail과 trade list를 연다.
- Duplicate ingest가 duplicate rows를 만들지 않는다.
- Raw data로 ingest를 explain 또는 replay할 수 있다.

## V2 Backlog

명시적으로 rescope되지 않는 한 다음은 V1 implementation 밖에 둔다:

- Rankings and top lists.
- Trade trend calculations.
- Favorite and alarm flows.
- OAuth login UX.
- Mail batch.
- Analytics dashboards.

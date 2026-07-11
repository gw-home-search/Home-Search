# Project Migration Plan


## Summary

Home Search migrates the minimum safe product: collect apartment trade data, preserve the
source data, normalize it into operational tables, and show it on the map.

Fixed locations:

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Target repository: `/Users/gwongwangjae/home-search`

## Phase 0 - Documentation Baseline

Target: `/Users/gwongwangjae/home-search/docs`

Create and maintain the docs in this directory before moving code. These docs
are the migration contract for future backend and frontend work.

Done when:

- The project included and excluded scope is explicit.
- Source and target paths are documented.
- The API surface to preserve is documented.
- The trade storage model is decided.
- Known source-code risks are captured.

## Phase 1 - Target Structure

Target:

- `/Users/gwongwangjae/home-search/apps/property-data`
- `/Users/gwongwangjae/home-search/apps/web`
- `/Users/gwongwangjae/home-search/infra`

Prepare the repository as a monorepo-style migration target.

Rules:

- Do not mix backend and frontend files at the repository root.
- Keep backend Gradle files inside `apps/property-data`.
- Keep frontend Vite files inside `apps/web`.
- Keep Docker, Postgres, monitoring, and deployment files inside `infra` unless
  a tool requires a root-level file.

## Phase 2 - Database and Storage Baseline

Source references:

- `src/main/resources/db/migration/api`
- `src/main/resources/db/migration/batch`
- `src/main/java/com/home/domain`
- `src/main/java/com/home/infrastructure/batch/trade`

Target behavior:

- Keep `region`, `parcel`, `complex`, and `trade` as project core tables.
- Keep PostGIS for parcel bounds lookup.
- Add or formalize raw trade preservation before normalized trade insert.
- Resolve the source mismatch between `complex_id` and `complex_pk`.

Home Search excludes:

- `trade_top_price_30d`
- `trade_top_volume_30d`
- `region_trade_trend`
- `mail_target`
- batch mail indexes

Done when:

- A fresh database can run project baselines.
- A seed set of region, parcel, complex, and trade rows supports map APIs.
- Duplicate ingest is prevented.
- Failed matches are inspectable.

Flyway 운영 규칙:

- API와 Batch startup은 migration 또는 validation을 자동 실행하지 않는다.
- schema 변경은 `property-data-migration.jar`의 명시적 operation만 수행한다.
- 신규 `V*` source는 durable DB 적용 전까지 수정할 수 있지만, 한 번 적용한
  migration은 수정하지 않고 다음 version으로 forward-fix한다.
- trade registry 연관 변경은 V5 expand → bounded backfill → V6 validate 순서로
  적용한다. V6에는 backfill DML을 넣지 않는다.
- V7 building metadata evidence는 durable DB의 V3 repair와 V5 bounded
  backfill/V6 validation이 완료된 뒤 명시적으로 적용한다. V7은 기존 값을
  삭제하거나 재해석하지 않고 snapshot, evaluation, external identity, state,
  decision만 추가한다.
- 현재는 `home_search` DB와 `public.flyway_schema_history` 하나를 유지한다.
  schema별 history나 Batch metadata DB 분리는 후속 운영 요구가 생길 때 ADR로
  검토한다.

## Phase 3 - Backend Migration

Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`

Target backend: `/Users/gwongwangjae/home-search/apps/property-data`

Migrate in this order:

1. Build/runtime baseline: Gradle, Spring Boot app entrypoint, profiles.
2. Domain baseline: `region`, `parcel`, `complex`, `trade`.
3. DB baseline: Flyway project schema and PostGIS setup.
4. Public data client: RTMS apartment trade collection.
5. Ingest service: raw save, resolve complex, normalized trade insert.
6. API controllers: map, region, search, detail, trade.
7. Error handling and validation.
8. Project tests.

Do not migrate later-scope features into the critical path. Keep rankings, favorites,
OAuth-dependent user flows, and mail alarms separate.

`apps/property-data` is the property-data-service boundary that owns the operational
`home_search` database. `core`, `api`, and `batch` are internal module and execution
splits of the same service, not trade/map database or MSA service splits.

The daily RTMS operational entrypoint is the packaged `batch` application. The legacy
API `@Scheduled` entrypoint was removed after `HS-SEP-03-LIVE-SMOKE` proved two
successful executions of the same jar against RTMS and the local integration database.
The evidence is stored under `.codex/harness/reports/hs-sep/03-live-*.md`.

The RTMS ingest service must apply the jibun/PNU match policy before normalized
trade insert. Uncertain rows remain raw/evidence records until a later admin
review or data correction slice resolves them. Admin UI, manual override, and
bulk live replay are not part of the baseline backend migration step.

## Phase 4 - Frontend Migration

Source frontend: `/Users/gwongwangjae/frontend/home-client`

Target frontend: `/Users/gwongwangjae/home-search/apps/web`

Migrate in this order:

1. Vite React runtime and env handling.
2. Axios base URL handling.
3. Kakao map render path.
4. Map marker fetch flow.
5. Search and region navigation.
6. Detail and trade side panel.
7. UI/UX redesign around full-map exploration.

The frontend must continue to call the public API URLs documented in
`API_CONTRACT.md`.

## Phase 5 - Integration

Use a small deterministic dataset:

- One SIDO, one SIGUNGU, one EUP_MYEON_DONG.
- One parcel with geometry.
- One complex linked to that parcel.
- Multiple trades for the complex.
- One duplicate trade ingest attempt.
- One failed match ingest case.
- One RTMS jibun/PNU conflict or ambiguous candidate case that remains held out
  of normalized `trade` while preserving review evidence.

Done when:

- Map bounds API returns a marker for the parcel.
- Marker click opens detail and trade list.
- Duplicate ingest does not create duplicate rows.
- Raw data can be used to explain or replay an ingest.

## Later-Scope Worklog

Keep these out of project implementation unless explicitly re-scoped:

- Rankings and top lists.
- Trade trend calculations.
- Favorite and alarm flows.
- OAuth login UX.
- Mail batch.
- Analytics dashboards.

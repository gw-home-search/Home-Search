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

- `/Users/gwongwangjae/home-search/apps/api`
- `/Users/gwongwangjae/home-search/apps/web`
- `/Users/gwongwangjae/home-search/infra`

Prepare the repository as a monorepo-style migration target.

Rules:

- Do not mix backend and frontend files at the repository root.
- Keep backend Gradle files inside `apps/api`.
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

## Phase 3 - Backend Migration

Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`

Target backend: `/Users/gwongwangjae/home-search/apps/api`

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

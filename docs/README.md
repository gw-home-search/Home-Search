# Home Search Migration Docs


## Fixed Paths

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Migration target: `/Users/gwongwangjae/home-search`

These paths are the anchors for every migration document. If a document
mentions "source backend", "source frontend", or "target repository", it means
the paths above.

## Project Goal

Home Search migrates only the product surface needed to collect real-estate apartment
trade data, store it safely, and display it on a map.

Included:

- Region, parcel, complex, and trade domain data.
- RTMS apartment trade collection through the existing public data client.
- Raw source preservation for reprocessing and audit.
- Normalized trade storage for map and detail APIs.
- Duplicate-safe ingest.
- Failed match tracking.
- Map bounds based region and complex marker APIs.
- Search, region navigation, complex detail, and trade list APIs.
- Frontend map UX using the existing API contract.

Excluded from the current project scope:

- Ranking APIs and screens.
- Trade trend tables and calculations.
- Top price or top volume 30-day aggregate tables.
- Favorite and trade alarm workflows.
- Mail target generation and mail sending batch.
- Recommendation or insight features.
- Query-heavy analytical optimizations unrelated to map display.

## Target Repository Shape

```text
/Users/gwongwangjae/home-search
├── docs/
├── apps/
│   ├── api/
│   └── web/
└── infra/
```

- `docs/`: migration decisions and implementation guide.
- `apps/api/`: future backend location.
- `apps/web/`: future frontend location.
- `infra/`: Postgres/PostGIS, Docker Compose, monitoring, and env docs.

## Reading Order

1. [MIGRATION_PLAN.md](MIGRATION_PLAN.md)
2. [ARCHITECTURE.md](ARCHITECTURE.md)
3. [DATA_STORAGE.md](DATA_STORAGE.md)
4. [API_CONTRACT.md](API_CONTRACT.md)
5. [MAP_DISPLAY_FLOW.md](MAP_DISPLAY_FLOW.md)
6. [UI_UX_MIGRATION.md](UI_UX_MIGRATION.md)
7. [INFRA_AND_ENV.md](INFRA_AND_ENV.md)

## Non-Negotiable Decisions

- Main API URLs stay stable.
- Backend behavior outside the map and trade-data surface is not migrated
  until later-scope.
- Data safety is more important than aggregate features.
- The `complex_id` versus `complex_pk` mismatch in the source backend must be
  resolved explicitly during backend migration.
- UI/UX may change, but frontend calls must remain compatible with the public API
  contract.

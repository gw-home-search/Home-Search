# Home Search Migration Docs

## Fixed Paths

- Source backend: `/Users/gwongwangjae/IdeaProjects/home-server`
- Source frontend: `/Users/gwongwangjae/frontend/home-client`
- Migration target: `/Users/gwongwangjae/home-search`

이 paths는 모든 migration document의 anchors다. 문서에서 "source backend", "source frontend", "target repository"를 언급하면 위 paths를 뜻한다.

## V1 Goal

V1은 real-estate apartment trade data를 수집하고, 안전하게 저장하고, map에 표시하는 데 필요한 product surface만 migrate한다.

Included:

- Region, parcel, complex, trade domain data.
- existing public data client를 통한 RTMS apartment trade collection.
- reprocessing과 audit를 위한 raw source preservation.
- map과 detail APIs를 위한 normalized trade storage.
- Duplicate-safe ingest.
- Failed match tracking.
- map bounds 기반 region 및 complex marker APIs.
- Search, region navigation, complex detail, trade list APIs.
- existing API contract를 사용하는 frontend map UX.

Excluded from V1:

- Ranking APIs and screens.
- Trade trend tables and calculations.
- Top price 또는 top volume 30-day aggregate tables.
- Favorite and trade alarm workflows.
- Mail target generation and mail sending batch.
- Recommendation 또는 insight features.
- map display와 무관한 query-heavy analytical optimizations.

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
- `infra/`: Postgres/PostGIS, Docker Compose, monitoring, env docs.

## Reading Order

1. [MIGRATION_PLAN.md](MIGRATION_PLAN.md)
2. [ARCHITECTURE.md](ARCHITECTURE.md)
3. [DATA_STORAGE.md](DATA_STORAGE.md)
4. [API_CONTRACT.md](API_CONTRACT.md)
5. [MAP_DISPLAY_FLOW.md](MAP_DISPLAY_FLOW.md)
6. [UI_UX_MIGRATION.md](UI_UX_MIGRATION.md)
7. [INFRA_AND_ENV.md](INFRA_AND_ENV.md)

## Non-Negotiable Decisions

- Main API URLs는 V1에서 안정적으로 유지된다.
- V1 map 및 trade-data surface 밖의 backend behavior는 V2까지 migrate하지 않는다.
- Data safety가 aggregate features보다 중요하다.
- source backend의 `complex_id`와 `complex_pk` mismatch는 backend migration 중 명시적으로 해결해야 한다.
- UI/UX는 바뀔 수 있지만 frontend calls는 V1 API contract와 compatible해야 한다.

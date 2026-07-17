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

Excluded from the property-data map/trade scope:

- Ranking APIs and screens.
- Trade trend tables and calculations.
- Top price or top volume 30-day aggregate tables.
- Favorite and trade alarm workflows.
- Mail target generation and mail sending batch.
- Recommendation or insight features in property-data public APIs. Authenticated
  chatbot features are owned by the later expansion milestone in
  `AI_SERVICE_PLAN.md`.
- Query-heavy analytical optimizations unrelated to map display.

## Target Repository Shape

```text
/Users/gwongwangjae/home-search
├── docs/
├── apps/
│   ├── admin/
│   │   ├── service/
│   │   └── web/
│   ├── user/
│   │   └── service/
│   ├── ai/                    # evidence-grounded chatbot core
│   ├── chat-bff/              # authenticated chatbot boundary
│   ├── ml/
│   ├── property-data/
│   ├── source-data/
│   └── web/
├── libs/
│   ├── rtms-ingest-core/
│   ├── security-jwt-core/
│   └── user-auth-contract/
└── infra/
```

- `docs/`: migration decisions and implementation guide.
- `apps/property-data/`: property-data-service boundary for API runtime, RTMS ingest,
  domain, and persistence.
- `apps/admin/`: admin product boundary. `service/` and `web/` remain independently
  built and deployed applications.
- `apps/user/service/`: OAuth identity, user JWT, and refresh-token ownership
  boundary with its own build, container, and database.
- `apps/ai/`: evidence-grounded FastAPI chatbot service. Slice 1에서는 외부
  provider 없이 fail-closed skeleton만 활성화한다.
- `apps/chat-bff/`: JWT를 다시 검증하고 AI timeout/error를 JSON/SSE 계약으로
  변환하는 Spring WebFlux 경계다.
- `apps/ml/`: optional prediction runtime. Model artifacts are supplied at
  runtime and are not stored in the image or repository.
- `apps/source-data/`: coordinate-source migration and verification boundary,
  separate from the operational property database.
- `apps/web/`: public map frontend.
- `libs/`: shared ingest and security contracts used by independently built
  services.
- `infra/`: Postgres/PostGIS, Docker Compose, monitoring, and env docs.

`apps/ai/`와 `apps/chat-bff/`의 Slice 1 skeleton, AI dataset lifecycle, property
`ai_read` 경계, grounded answer kernel, Slice 5의 Redis subject rate limit과
browser-only IndexedDB 대화/UI까지 구현되었다. 운영 LLM provider가 아직
선택되지 않아 실제 질문 Capability는 계속 `unavailable`이다.
public gateway는 기본 stack에 포함되지 않고 preflight를 통과한 opt-in
chatbot overlay에서만 연결된다. 두 서비스는
`AI_SERVICE_PLAN.md`를 따르며 property-data map/trade critical path에는 들어가지 않는다.

## Reading Order

1. [MIGRATION_PLAN.md](MIGRATION_PLAN.md)
2. [ARCHITECTURE.md](ARCHITECTURE.md)
3. [DATA_STORAGE.md](DATA_STORAGE.md)
4. [COORDINATE_SOURCE_STRATEGY.md](COORDINATE_SOURCE_STRATEGY.md)
5. [DATA_MODEL_ERD.md](DATA_MODEL_ERD.md)
6. [API_CONTRACT.md](API_CONTRACT.md)
7. [MAP_DISPLAY_FLOW.md](MAP_DISPLAY_FLOW.md)
8. [UI_UX_MIGRATION.md](UI_UX_MIGRATION.md)
9. [INFRA_AND_ENV.md](INFRA_AND_ENV.md)
10. [STAGING_RUNBOOK.md](STAGING_RUNBOOK.md)
11. [VERIFICATION_EVIDENCE.md](VERIFICATION_EVIDENCE.md)
12. [JAVA_SPRING_BOOT_MODERNIZATION.md](JAVA_SPRING_BOOT_MODERNIZATION.md)
13. [RESTRUCTURING_PLAN.md](RESTRUCTURING_PLAN.md)
14. [USER_SERVICE_PLAN.md](USER_SERVICE_PLAN.md)
15. [AI_SERVICE_PLAN.md](AI_SERVICE_PLAN.md)
16. [CHATBOT_CAPABILITY_REGISTRY.md](CHATBOT_CAPABILITY_REGISTRY.md)
17. [CHATBOT_DATA_SOURCES.md](CHATBOT_DATA_SOURCES.md)
18. [CHATBOT_API_CONTRACT.md](CHATBOT_API_CONTRACT.md)
19. [ADR 0001](adr/0001-evidence-grounded-chatbot-and-browser-memory.md)

## Non-Negotiable Decisions

- Main API URLs stay stable.
- Backend behavior outside the map and trade-data surface is not migrated
  until later-scope.
- Data safety is more important than aggregate features.
- The `complex_id` versus `complex_pk` mismatch in the source backend must be
  resolved explicitly during backend migration.
- Coordinate source storage is separate from the operational `home_search`
  database. The coordinate source database is read by PNU; nationwide coordinate
  snapshots are not copied into the operational database.
- UI/UX may change, but frontend calls must remain compatible with the public API
  contract.
- Expansion order is user-service, chatbot parity, image/ECR CI, then AWS
  deployment preparation. These services do not change the property-data
  database or its public map/trade surface.

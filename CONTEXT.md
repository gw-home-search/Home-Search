# Home Search Context


This file gives agents the shortest shared vocabulary for Home Search. Canonical product, API, and data decisions remain in `docs/*.md`.

## Product Boundary

**Home Search** is the migration target for collecting real-estate apartment trade data, storing it safely, and displaying it on a map.

**later-scope work** includes rankings, favorites, alarms, mail batches, recommendations, insights, auth-dependent UX, and heavy analytics. later-scope work must not enter the current critical path unless explicitly re-scoped.

## Repositories

**Source backend** means `/Users/gwongwangjae/IdeaProjects/home-server`. It is read-only reference material.

**Source frontend** means `/Users/gwongwangjae/frontend/home-client`. It is read-only reference material.

**Target api** means `apps/api`.

**Target web** means `apps/web`.

## Shared Terms

**Canonical API contract** means `docs/API_CONTRACT.md`. Backend and frontend work must preserve it.

**Map marker** is a region or complex marker rendered on the Kakao map from map endpoints.

**Parcel** is the map/display location unit used by detail and trade APIs.

**parcelId** is the public API identifier used by marker click, detail, and trade flows.

**Complex** is an apartment complex associated with a parcel.

**complex_id** is the operational backend relation from normalized trades to complexes.

**complex_pk** is a source identifier retained for audit, matching, and deduplication.

**source_key** is the deterministic source identity for duplicate-safe ingest.

**Raw ingest** is preserved external source data saved before normalized trade rows.

**Normalized trade** is the operational trade row used by map/detail/trade APIs.

**Failed match** is an ingest result that could not resolve to a complex and must remain explainable and queryable.

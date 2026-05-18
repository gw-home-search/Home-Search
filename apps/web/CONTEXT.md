# apps/web Context

This file defines frontend-specific Home Search V1 terms. Canonical decisions remain in root `docs/*.md`.

## Frontend Shape

**Web app** is the Vite React frontend under `apps/web`.

**Map-first layout** means the Kakao map is the primary screen, with search, filters, region navigation, and details arranged around it.

**API adapter** is the frontend boundary that calls V1 APIs and normalizes temporary source field variants.

**Marker adapter** normalizes region and complex marker responses for rendering.

## Map Terms

**Kakao map** is the interactive map surface.

**Map level** is the Kakao zoom level used to decide region markers versus complex markers.

**Region marker** is returned by `/api/v1/map/regions`.

**Complex marker** is returned by `/api/v1/map/complexes`.

**Complex marker canonical fields** are `parcelId`, `lat`, `lng`, `latestDealAmount`, and `unitCntSum`.

**Exploration panel** is the compact search and region navigation surface.

**Filter controls** are map controls for unit, price, area, and age filters.

**Detail drawer** opens from a complex marker and shows detail and trade data for a `parcelId`.

**Trade list** is the frontend view of `/api/v1/trade/{parcelId}`.

**Non-blocking map error** means marker API failure does not navigate away from the map or make the map unusable.

## Frontend Non-Scope

The web app must not introduce V2 ranking, favorite, alarm, mail, recommendation, or auth flows into the V1 map/trade display path.

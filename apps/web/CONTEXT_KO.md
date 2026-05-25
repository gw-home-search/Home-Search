# apps/web Context KO

> KO 생성 기준: canonical source only
> Source: `apps/web/CONTEXT.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `apps/web/CONTEXT.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# apps/web Context


This file defines frontend-specific Home Search terms. Canonical decisions remain in root `docs/*.md`.

## Frontend Shape

**Web app** is the Vite React frontend under `apps/web`.

**Map-first layout** means the Kakao map is the primary screen, with search, filters, region navigation, and details arranged around it.

**API adapter** is the frontend boundary that calls public APIs and normalizes temporary source field variants.

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

The web app must not introduce later-scope ranking, favorite, alarm, mail, recommendation, or auth flows into the map/trade display path.

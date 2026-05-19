# apps/web Context

이 파일은 frontend-specific Home Search V1 terms를 정의한다. canonical decisions는 root `docs/*.md`에 남아 있다.

## Frontend Shape

**Web app**은 `apps/web` 아래의 Vite React frontend다.

**Map-first layout**은 Kakao map이 primary screen이고 search, filters, region navigation, details가 그 주변에 배치된다는 뜻이다.

**API adapter**는 V1 APIs를 호출하고 temporary source field variants를 normalize하는 frontend boundary다.

**Marker adapter**는 rendering을 위해 region 및 complex marker responses를 normalize한다.

## Map Terms

**Kakao map**은 interactive map surface다.

**Map level**은 region markers와 complex markers 중 무엇을 보여줄지 결정하는 Kakao zoom level이다.

**Region marker**는 `/api/v1/map/regions`에서 반환된다.

**Complex marker**는 `/api/v1/map/complexes`에서 반환된다.

**Complex marker canonical fields**는 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`이다.

**Exploration panel**은 compact search와 region navigation surface다.

**Filter controls**는 unit, price, area, age filters를 위한 map controls다.

**Detail drawer**는 complex marker에서 열리며 `parcelId`의 detail과 trade data를 보여준다.

**Trade list**는 `/api/v1/trade/{parcelId}`의 frontend view다.

**Non-blocking map error**는 marker API failure가 map에서 벗어나게 하거나 map을 unusable하게 만들지 않는다는 뜻이다.

## Frontend Non-Scope

web app은 V2 ranking, favorite, alarm, mail, recommendation, auth flows를 V1 map/trade display path에 도입하면 안 된다.

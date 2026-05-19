# Home Search Context

이 파일은 agents가 Home Search V1을 이해하는 데 필요한 가장 짧은 shared vocabulary를 제공한다. canonical product, API, data decisions는 `docs/*.md`에 남아 있다.

## Product Boundary

**Home Search V1**은 real-estate apartment trade data를 수집하고, 안전하게 저장하고, map에 표시하기 위한 migration target이다.

**V2 work**에는 rankings, favorites, alarms, mail batches, recommendations, insights, auth-dependent UX, heavy analytics가 포함된다. 명시적으로 rescope되지 않는 한 V2 work는 V1 critical path에 들어오면 안 된다.

## Repositories

**Source backend**는 `/Users/gwongwangjae/IdeaProjects/home-server`를 뜻한다. read-only reference material이다.

**Source frontend**는 `/Users/gwongwangjae/frontend/home-client`를 뜻한다. read-only reference material이다.

**Target api**는 `apps/api`를 뜻한다.

**Target web**은 `apps/web`을 뜻한다.

## Shared Terms

**Canonical API contract**는 `docs/API_CONTRACT.md`를 뜻한다. Backend와 frontend work는 이를 보존해야 한다.

**Map marker**는 V1 map endpoints에서 Kakao map에 렌더링되는 region 또는 complex marker다.

**Parcel**은 detail과 trade APIs에서 사용하는 map/display location unit이다.

**parcelId**는 marker click, detail, trade flows에서 사용하는 public API identifier다.

**Complex**는 parcel과 연결된 apartment complex다.

**complex_id**는 normalized trades에서 complexes로 이어지는 operational backend relation이다.

**complex_pk**는 audit, matching, deduplication을 위해 보존되는 source identifier다.

**source_key**는 duplicate-safe ingest를 위한 deterministic source identity다.

**Raw ingest**는 normalized trade rows보다 먼저 저장되는 preserved external source data다.

**Normalized trade**는 map/detail/trade APIs가 사용하는 operational trade row다.

**Failed match**는 complex로 resolve되지 못한 ingest result이며 explainable하고 queryable해야 한다.

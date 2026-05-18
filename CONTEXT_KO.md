# Home Search Context

이 문서는 `CONTEXT.md`의 한국어 companion이다. 기준은 영문 원문이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## Product Boundary

**Home Search V1**은 real-estate apartment trade data를 수집하고, 안전하게 저장하고, map에 표시하는 migration target이다.

**V2 work**에는 ranking, favorite, alarm, mail batch, recommendation, insight, auth-dependent UX, heavy analytics가 포함된다. 명시적으로 재범위화하지 않는 한 V2 work는 V1 critical path에 들어가면 안 된다.

## Repositories

**Source backend**는 `/Users/gwongwangjae/IdeaProjects/home-server`이며 read-only reference다.

**Source frontend**는 `/Users/gwongwangjae/frontend/home-client`이며 read-only reference다.

**Target api**는 `apps/api`다.

**Target web**은 `apps/web`이다.

## Shared Terms

**Canonical API contract**는 `docs/API_CONTRACT.md`다.

**Map marker**는 V1 map endpoint에서 받아 Kakao map에 표시하는 region 또는 complex marker다.

**Parcel**은 detail/trade API에서 사용하는 map/display location unit이다.

**parcelId**는 marker click, detail, trade flow에서 사용하는 public API identifier다.

**Complex**는 parcel과 연결된 apartment complex다.

**complex_id**는 normalized trade에서 complex로 연결되는 operational backend relation이다.

**complex_pk**는 audit, matching, dedupe를 위해 보존하는 source identifier다.

**source_key**는 duplicate-safe ingest를 위한 deterministic source identity다.

**Raw ingest**는 normalized trade row보다 먼저 저장되는 external source data다.

**Normalized trade**는 map/detail/trade API에서 사용하는 operational trade row다.

**Failed match**는 complex를 resolve하지 못한 ingest result이며 explainable하고 queryable해야 한다.

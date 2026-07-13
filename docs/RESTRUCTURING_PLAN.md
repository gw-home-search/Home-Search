# Restructuring Plan

## 현재 상태

- 상태: Stage 1 완료, 확장 milestone 진입
- 갱신일: 2026-07-13
- 불변: property-data 공개 map/trade API, operational DB data, 실행 중인
  packaged Batch는 변경하지 않는다.

Stage 1에서 `apps/property-data`는 다음 구조로 분리됐다.

```text
apps/property-data/
├── core/       # domain/application/persistence/external, migration source
├── api/        # public/internal HTTP composition root
├── batch/      # Spring Batch run-and-exit composition root
└── migration/  # explicit Flyway/backfill run-and-exit artifact
```

daily RTMS 수집은 packaged `batch`의 `rtmsDailyRefreshJob`이 소유한다.
표적 재수집은 `rtmsBackfillJob`이 소유하며 legacy API scheduler는 제거됐다.
API/Batch startup은 Flyway를 자동 실행하지 않는다.

## 완료된 결정

- `core`, `api`, `batch`, `migration`은 하나의 property-data service 내부
  module/execution 경계다.
- operational trade relation은 `complex_id`이며 `complex_pk`, `apt_seq`,
  `source`, `source_key`는 audit/dedupe evidence로 보존한다.
- raw-first, duplicate-safe ingest, failed-match queryability를 유지한다.
- trade/map을 별도 service나 database로 분리하지 않는다.
- public map/search/detail/trade는 무인증이며 ranking/favorite/alarm/mail 상태를
  요구하지 않는다.
- admin-service, source-data, ml-inference는 이미 독립 application 경계다.

Stage 1의 legacy scheduler 제거와 packaged Batch live smoke 근거는
`.codex/harness/reports/hs-sep/03-live-*.md`에 있다. 이 완료 상태를 되돌려
API scheduler를 재도입하지 않는다.

## 폐기하는 경계

`apps/rtms-loader`는 ingest를 수행하지 않고 계획을 logging executor에 넘기는
dead application이다. 같은 운영 목적은 property-data의
`rtmsDailyRefreshJob`/`rtmsBackfillJob`이 실제 ingest와 실행 evidence를 갖고
대체한다. 별도 cleanup change에서 app과 전용 test를 제거하며 Batch 경로는
유지한다.

## 확장 서비스 지도

```text
apps/
├── property-data/{core,api,batch,migration}
├── admin/{service,web}
├── user/service/{core,api,migration}
├── ai/
├── source-data/
├── ml/
└── web/
```

기존 `core-user` 안은 폐기한다. user-service는 property-data 내부 module이
아니라 `apps/user/service`의 독립 Gradle build, container,
`home_search_user` database 경계다. 세부 계약은 `USER_SERVICE_PLAN.md`가
소유한다.

ai-service는 later-scope 청사진이 아니라 user-service 다음 구현 milestone다.
legacy chatbot 전체 parity 범위와 구현 순서는 `AI_SERVICE_PLAN.md`가 소유한다.

## 서비스와 데이터 소유권

| 경계 | 소유 데이터 | 허용 의존 |
|---|---|---|
| property-data | `home_search`, Batch metadata, domain evidence | coordinate reader, external public-data providers |
| admin-service | `home_search_admin`, Session/RBAC/audit | signed internal HTTP to property-data |
| source-data | `home_search_coordinate_source` | import source; property-data receives read-only credential |
| user-service | `home_search_user.users`, OAuth identity, refresh token | OAuth providers |
| ai-service | `ai` conversation/POI/legal/RAG/reference data | `ai_read` SELECT, user JWT public keys, LLM/legal providers |
| ml-inference | no durable product data | request/response inference only |

Cross-database join은 금지한다. service 간 `user_id`, `complex_id`는 opaque
reference일 뿐이며 한 service가 다른 service database credential을 받지 않는다.

## 실행 순서

```text
계획 문서 정합화
-> dead rtms-loader 제거
-> user-service 기반/OAuth/JWT 완성
-> chatbot legacy 전체 parity
-> image/ECR CI
-> AWS 배포 준비
```

### 1. 문서 정합화

현재 module과 service ownership을 canonical 문서에 반영한다. 동작, DB,
`API_CONTRACT.md`를 변경하지 않으므로 First RED는 면제한다.

### 2. dead rtms-loader 제거

app과 전용 test만 제거한다. property-data Batch source, job name, DB,
schedule/runtime 설정은 변경하지 않는다.

### 3. user-service

Google/Kakao/Naver OAuth, provider subject identity, hashed rotating opaque
refresh token, RS256 access JWT, `/auth/access`, `/auth/logout`,
`/api/v1/users/me`를 같은 milestone에서 완료한다. 세부 보안/DB/테스트 경계는
`USER_SERVICE_PLAN.md`를 따른다.

### 4. ai-service

data boundary, core pipeline, real-estate feature/POI, legal RAG, LLM fallback,
BFF/JWT/JSON/SSE 순으로 PR을 나눈다. milestone 완료 기준은 legacy fixture
전체 parity다.

### 5. CI와 AWS

각 user/ai PR에서 외부 provider 없이 실행되는 service test CI를 유지한다.
둘 다 GREEN 뒤 모든 deployable image build를 통합하고 ECR에 immutable git SHA
tag를 GitHub OIDC로 push한다. 이후 ECS task, RDS, EventBridge, secret injection,
DNS를 준비한다. AWS IaC와 metadata `AMBIGUOUS` 개선은 별도 후속이다.

## 통신 규칙

```text
web -> property-data public API                 allowed, unauthenticated
web -> user-service OAuth/auth                  allowed
web -> chatbot BFF -> ai-service JSON/SSE       allowed, user login required
admin web -> admin-service -> property-data     allowed, Session/RBAC + internal JWT
ai-service -> ai_read                           allowed, SELECT only
ai-service -> property-data public tables       forbidden
ai-service -> ml-inference                      forbidden
user-service <-> property-data database join    forbidden
```

user JWT와 admin internal JWT는 signing key, issuer, audience를 공유하지 않는다.
private key는 각 issuer만 보유하고 verifier는 public key만 받는다.

## 검증 게이트

- 문서: module/roadmap 모순 0건, link 등록, `git diff --check`.
- cleanup: `apps/rtms-loader` source/automation reference 0건, property-data Batch
  tests GREEN. 폐기 결정 문서의 역사적 경로 표기는 허용한다.
- user: provider별 신규/재로그인, refresh rotation/reuse/revoke/expiry,
  JWT issuer/audience/kid, cookie flags, DB permission, public map 무인증 회귀.
- ai: legacy 77 test assets 재구성, feature/SSE/fallback/import/JWT 검증,
  property-data 무영향.
- production 완료 전 findings-first code review와
  `security-audit: 지적사항 = none|listed` evidence를 남긴다.

외부 OAuth/LLM/legal provider는 CI에서 stub으로 실행하며 실제 secret을 저장소에
넣지 않는다.

## 중단과 rollback

public API URL/response 변경, cross-database join, applied migration 수정,
데이터 삭제/재해석, 출처 불명 dataset 이관, Docker volume 삭제가 필요하면
중단하고 승인을 요청한다.

rollback은 slice별 commit revert와 신규 service 비활성화로 수행한다. DB volume,
기존 property-data 데이터, 실행 중인 Batch evidence는 삭제하지 않는다.

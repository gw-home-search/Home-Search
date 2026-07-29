# User Service Plan

## 상태와 범위

user-service는 property-data 다음 구현 milestone이다. `apps/user/service`의
독립 Gradle build, container, `home_search_user` database를 소유하며
property-data의 `core` 모듈이나 `home_search` database를 공유하지 않는다.

이번 milestone은 로그인, 현재 사용자 조회, 관심 단지 저장까지 포함한다.
이메일 가입, 알림, 메일, 사용자 선호 추천은 포함하지 않는다. 기존 public
map/search/detail/trade API는 계속 무인증이다.

## 목표 구조

```text
apps/user/service/
├── core/
│   └── src/main/          # domain/application ports + JPA/JDBC persistence adapters
├── app/                    # Spring Boot, OAuth2, JWT/cookie, HTTP composition root
├── db/                     # external Flyway config and versioned SQL catalog
└── ops/                    # restricted Docker Flyway wrapper
```

두 모듈은 하나의 user-service 배포 경계다. `app -> core`만 허용하고
property-data와 compile-time 의존을 두지 않는다. OAuth identity, user account,
favorite aggregate의 JPA entity/Spring Data repository와 refresh token JDBC adapter,
PostgreSQL identity lock은 `core`가 소유한다. `app`은 JPA aggregate만
`@EntityScan`/`@EnableJpaRepositories`로 조립한다. application service는 `@Service`로
자동 등록되고 transaction boundary를 소유하며 persistence adapter는 transaction을
시작하지 않는다.

## 데이터 소유권

- database: `home_search_user`
- schema: `users`
- migration role: `home_search_user_migrator`
- runtime role: `home_search_user_runtime`
- identity key: `(provider, provider_subject)` unique
- email은 표시/연락 속성이며 identity나 provider 간 자동 병합 키가 아니다.
- refresh token은 원문을 저장하지 않고 강한 one-way hash만 저장한다.
- 사용자당 active refresh token은 최대 하나다. 새 로그인과 refresh 성공은
  기존 token을 폐기하고 새 token으로 회전한다.
- 관심 단지는 `(user_id, complex_id)`로 식별하고 사용자당 최대 200개다.
  단지명, 주소, 가격 snapshot과 property-data cross-database FK/join은 두지 않는다.

runtime role은 `users` schema의 필요한 DML만 받고 DDL, role 관리,
`home_search` 및 `home_search_admin` 접근 권한을 받지 않는다. API와 migration
runtime의 credential은 분리한다.

## OAuth와 토큰 경계

같은 milestone에서 Google, Kakao, Naver를 지원한다.

- 진입: `GET /oauth2/authorization/{google|kakao|naver}`
- callback: `/login/oauth2/code/{provider}`
- identity는 provider가 검증해 반환한 subject로만 찾는다.
- provider access/refresh token은 user-service 세션 token으로 재사용하거나
  durable storage에 저장하지 않는다.

Access token:

- RS256, `iss=user-service`, `aud=home-search-user-api`
- header에 active `kid`
- `sub=userId`, 최소 `role` claim
- 기본 TTL 15분
- private key는 user-service만 보유하고 BFF/ai-service 등 검증자는 public
  key만 받는다.
- logout 또는 계정 정지 뒤 이미 발급된 access token은 별도 revocation 조회를
  하지 않으므로 최대 15분까지 유효할 수 있다.

Refresh token:

- opaque random value, 기본 만료 30일
- `POST /auth/access` 성공 때 단일-use compare-and-rotate
- reuse, revoked, expired token은 모두 거부
- cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth`
- logout은 저장된 active token을 폐기하고 같은 속성으로 cookie를 만료한다.

admin internal JWT와 user JWT는 key pair, issuer, audience, key rotation을
완전히 분리한다. user token으로 `/internal/v1/admin/**`에 접근할 수 없고 admin
token으로 사용자 API에 접근할 수 없다.

## 구현 API

- `POST /auth/access` → `{"accessToken":"..."}`
- `POST /auth/logout` → `204`
- `GET /api/v1/users/me` → 로그인한 사용자의 안정적인 id, provider, profile
  표시 필드
- `GET /api/v1/favorites` 및 `GET /api/v1/favorites/{complexId}`
- `PUT /api/v1/favorites/{complexId}` 및 `DELETE /api/v1/favorites/{complexId}`

이 경로들은 user-service 계약이며 현재 property-data public 계약인
`API_CONTRACT.md`를 변경하지 않는다. BFF가 추가될 때 user JWT 검증 결과만
downstream principal로 전달하고 browser가 임의의 user id를 보내지 못하게 한다.

## 구현 slice와 검증

1. 독립 Gradle build와 `core/app`, external Flyway CLI, `user-service-test` CI.
2. `users` schema/roles와 identity 저장소. provider별 신규/재로그인 검증.
3. refresh token hash 저장, 단일 active 제약, rotation/reuse/revoke/expiry 검증.
4. RS256 access JWT의 issuer/audience/kid/TTL/role 검증.
5. OAuth provider adapter와 callback, cookie 속성 검증.
6. 관심 단지 V5 schema, 최대 200개 정책, user row lock, CRUD API 검증.
7. `/api/v1/users/me` 및 public map 무인증 회귀 검증.

외부 OAuth는 test에서 stub으로 실행한다. 실제 client secret, private key,
refresh token을 source, fixture, log에 저장하지 않는다.

## 중단 조건

public map/trade URL 또는 응답 변경, cross-database join, email 기반 identity
병합, applied migration 수정, 데이터 삭제, admin/user key 공유가 필요하면
구현을 중단하고 별도 승인을 받는다.

## 구현된 runtime 계약

- user-service app은 Spring Boot 4.1.0, Java 21을 사용하고 database migration은
  application JVM을 기동하지 않는 official Docker CLI가 수행한다.
- `OAuthLoginService`, `CurrentUserQueryService`, `RefreshTokenService`, `FavoriteService`가
  application transaction boundary를 소유한다. favorite 저장은 user row lock 뒤 기존
  favorite와 현재 개수를 확인하고 domain `FavoriteLimitPolicy`를 적용하므로 200번째와
  201번째 동시 요청에서도 최대 200개 제약과 duplicate PUT idempotency를 유지한다.
- refresh token은 JPA entity/native query 경로 없이 `JdbcRefreshTokenRepository`의
  명시적 upsert, active lookup, row-count CAS rotate, revoke SQL을 사용한다. OAuth identity,
  user account, favorite aggregate는 JPA를 유지한다.
- `AuthProperties`, `CookieProperties`, `OAuthProperties`, `JwtProperties`가 기존
  `home.auth`, `home.cookie`, `home.oauth`, `home.jwt` key를 type-safe하게 bind하고 startup
  validation을 수행한다. provider별 client registration은 Spring Boot가 제공하는 typed
  `spring.security.oauth2.client` configuration binding을 그대로 사용한다.
- app runtime은 Flyway를 포함하지 않으며 `ddl-auto=validate`로 `users` JPA mapping만 검증한다.
- pinned `redgate/flyway:13.0-alpine`에서 PostgreSQL 전용 CLI runtime을 구성하며
  `db/migration/user`의 migration versions 1 through 6만 실행한다.
  `home_search_user` database guard와 read-only SQL mount를 사용한다.
- `ops/user-flyway.sh`는 `info`, `validate`, 숫자 target이 필수인 `migrate`만
  제공한다. legacy identity importer, `repair`, `clean`, `baseline`, `latest`는
  최종 운영 interface에 없다.
- 신규 deployment는 `ops/user-deployment-preflight.sh before 6`가 history와
  service relation이 모두 없는 fresh DB만 허용한 뒤 migrate하고,
`after 6`가 migration versions 1 through 6의 exact SQL/Success history와 Flyway validate 결과를
  확인해야 한다. JDBC, Baseline, Deleted, Out of Order, Missing, Ignored,
  duplicate, failed history는 자동 중단한다.
- runtime OAuth/JWT/DB 값은 `USER_*`, `GOOGLE_OAUTH_*`, `KAKAO_OAUTH_*`, `NAVER_OAUTH_*`
  environment에서만 주입하며 provider token이나 raw refresh token을 durable storage/log에 남기지 않는다.

검증 gate:

```bash
cd apps/user/service
./gradlew userServiceQualityCheck --no-daemon --stacktrace
```

현재 persistence integration은 fresh PostgreSQL fixture와 pinned external CLI에서
Migration versions 1 through 5, runtime role 권한, 동시 identity 생성, refresh CAS rotation,
favorite 200/201 동시 저장을 검증한다. coverage gate는 `core`와 `app` production class를
모두 denominator에 포함하므로 controller, OAuth handler, security filter, cookie code도 측정한다.

## Frontend 인증 흐름

`apps/web`은 property-data의 `VITE_API_SERVER_IP`와 별도로
optional `VITE_USER_API_SERVER_IP` override를 지원한다. 승인된 production
release는 이 값을 build artifact에 넣지 않고 browser
`window.location.origin`을 사용해 public gateway와 same-origin으로 통신한다.
local/test는 explicit override 또는 `http://localhost:8082` fallback을 허용한다.
OAuth 개발 origin은 user-service의 exact Origin 검사와 맞는
`http://localhost:5173`이고 `127.0.0.1:5173`은 사용하지 않는다.

브라우저는 mount 또는 `/auth/success`에서 `POST /auth/access`
(`credentials: include`) 후 memory-only access JWT로
`GET /api/v1/users/me`를 호출한다. `/auth/access`와 `/auth/logout` 외 요청에는
cookie credential을 추가하지 않으며 public map API에는 cookie나 Bearer token을
전달하지 않는다. startup refresh 실패는 anonymous로 degrade해 public map을
계속 사용하게 하고, logout은 서버 응답 실패에도 local memory auth/favorite
상태를 지운다. `/auth/failure`은 provider 원인을 노출하지 않는 안내를 열고,
두 callback URL은 처리 뒤 `/`로 교체한다.

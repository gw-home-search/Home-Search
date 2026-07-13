# User Service Plan

## 상태와 범위

user-service는 property-data 다음 구현 milestone이다. `apps/user/service`의
독립 Gradle build, container, `home_search_user` database를 소유하며
property-data의 `core` 모듈이나 `home_search` database를 공유하지 않는다.

이번 milestone은 로그인과 현재 사용자 조회까지만 포함한다. 이메일 가입,
즐겨찾기, 알림, 메일, 사용자 선호 추천은 포함하지 않는다. 기존 public
map/search/detail/trade API는 계속 무인증이다.

## 목표 구조

```text
apps/user/service/
├── core/       # identity, refresh-token 정책과 application ports
├── api/        # OAuth2 login, JWT/cookie, HTTP composition root
└── migration/  # 명시적 Flyway run-and-exit artifact
```

세 모듈은 하나의 user-service 배포 경계다. `api -> core`,
`migration -> core resources`만 허용하고 property-data와 compile-time 의존을
두지 않는다.

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

이 경로들은 user-service 계약이며 현재 property-data public 계약인
`API_CONTRACT.md`를 변경하지 않는다. BFF가 추가될 때 user JWT 검증 결과만
downstream principal로 전달하고 browser가 임의의 user id를 보내지 못하게 한다.

## 구현 slice와 검증

1. 독립 Gradle build와 `core/api/migration`, test task, `user-service-test` CI.
2. `users` schema/roles와 identity 저장소. provider별 신규/재로그인 검증.
3. refresh token hash 저장, 단일 active 제약, rotation/reuse/revoke/expiry 검증.
4. RS256 access JWT의 issuer/audience/kid/TTL/role 검증.
5. OAuth provider adapter와 callback, cookie 속성 검증.
6. `/api/v1/users/me` 및 public map 무인증 회귀 검증.

외부 OAuth는 test에서 stub으로 실행한다. 실제 client secret, private key,
refresh token을 source, fixture, log에 저장하지 않는다.

## 중단 조건

public map/trade URL 또는 응답 변경, cross-database join, email 기반 identity
병합, applied migration 수정, 데이터 삭제, admin/user key 공유가 필요하면
구현을 중단하고 별도 승인을 받는다.

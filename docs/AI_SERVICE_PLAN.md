# AI Service Plan

## 상태와 목표

ai-service는 user-service 다음 구현 milestone이다. 참조 구현
`/Users/gwongwangjae/kosa-team5/server`는 읽기 전용으로 사용하고 target의
데이터/인증 경계에 맞게 재구현한다. 목표는 축소판이 아니라 legacy chatbot의
전체 parity다.

포함 범위:

- `simple_lookup`, `price_trend`, `comparison`, `recommendation`
- POI 검색과 추천 필터
- legal-contract ingest, indexing, retrieval, RAG
- embedding과 LLM supervisor/fallback
- conversation context/memory
- legacy JSON 응답과 SSE event 의미

frontend chatbot UI는 backend parity와 BFF가 완료된 뒤 별도 slice다.

## 배포와 데이터 경계

`apps/ai`는 property-data, user-service, `apps/ml`과 독립 build/container다.

- Home Search facts는 `ai_read` read-only view만 조회한다.
- feature별 direct SQL과 property-data `public` schema 접근을 금지한다.
- POI와 법령 corpus는 ai-service 소유 reference dataset이다.
- ai-service는 `home_search`에 쓰지 않고 ml-inference를 직접 호출하지 않는다.
- 일반 chat은 로그인 사용자만 허용한다. BFF와 ai-service 모두 user-service
  JWT의 signature, issuer, audience, expiry, `kid`를 검증한다.
- 법령 ingest/index endpoint는 internal/admin 운영 경로로만 노출한다.

```text
web -> BFF(auth, rate limit, audit) -> ai-service JSON/SSE
ai-service -> ai_read (SELECT only)
ai-service -> ai schema (conversation/reference/RAG ownership)
```

## 목표 구조

```text
apps/ai/
├── pyproject.toml
├── alembic/                         # ai-owned schema migrations
├── ai_service/
│   ├── main.py                      # FastAPI JSON/SSE composition root
│   ├── auth/                        # user JWT public-key verification
│   ├── facts/facts_repository.py    # ai_read SQL의 유일한 진입점
│   ├── chat/
│   │   ├── splitter.py
│   │   ├── planner.py
│   │   ├── orchestrator.py
│   │   ├── supervisor.py
│   │   ├── streaming.py
│   │   └── memory.py
│   ├── features/
│   │   ├── simple_lookup/
│   │   ├── price_trend/
│   │   ├── comparison/
│   │   ├── recommendation/
│   │   ├── poi/
│   │   └── legal_contract/rag/
│   └── embedding/
└── tests/fixtures/
```

SQL은 `facts/`의 `ai_read` 조회와 ai-service 소유 reference/RAG repository에만
존재한다. LLM과 embedding provider 호출은 adapter 뒤로 격리하고 test에서는
stub을 사용한다.

## 데이터 계약

property-data migration이 `ai_read` view와 SELECT-only role을 소유한다. view는
공개 표시 가능한 normalized trade/complex만 노출하며 canceled, match-failed,
marker-unsafe row를 domain policy에 따라 제외한다.

POI/법령 파일은 import 전에 다음 metadata를 요구한다.

- checksum
- 원 출처와 획득 URL 또는 기관
- 갱신일
- 라이선스/재배포 조건

확인되지 않은 legacy CSV는 이관하지 않는다. import는 one-shot seed/backfill로
실행하고 재실행이 idempotent해야 한다.

## HTTP 계약

- `POST /api/v1/chatbot/query`
- `POST /api/v1/chatbot/query/stream`

`question`, `conversationContext`, JSON response payload, SSE event/data/종료/오류
의미는 legacy fixture로 고정한다. 의도적인 차이는 유효한 user login을 필수로
하는 것뿐이다. 이 계약은 chatbot BFF 계약이며 property-data의 기존
`docs/API_CONTRACT.md` URL/응답을 변경하지 않는다.

## 구현 slice

하나의 milestone으로 추적하되 독립적으로 revert 가능한 PR로 나눈다.

1. 데이터 경계: `ai_read` view/role, ai schema, licensed seed importer,
   external LLM 없는 `ai-service-test` CI.
2. 핵심 파이프라인: splitter/planner/orchestrator, conversation context,
   `simple_lookup`, `price_trend`.
3. 부동산 feature: `comparison`, `recommendation`, POI.
4. 법령 RAG: corpus import, chunk/index, embedding, retrieval, internal/admin
   ingest authorization.
5. LLM supervisor와 장애 fallback.
6. BFF/JWT/JSON/SSE parity와 rate-limit/audit boundary.

각 slice는 ai-service가 중단돼도 public map/search/detail/trade가 정상임을
검증한다. property-data와의 cross-database join이나 feature direct SQL이
발견되면 다음 slice를 중단한다.

## 검증 기준

- legacy 77개 test asset을 target fixture/test로 재구성하고 출처를 기록한다.
- 각 feature의 정상/empty/error, conversation context, JSON/SSE parity를 검증한다.
- LLM timeout/error 시 bounded fallback과 종료 event를 검증한다.
- POI/RAG import checksum, idempotency, source/license metadata를 검증한다.
- missing, expired, wrong issuer/audience/key user JWT를 거부한다.
- 외부 LLM/OAuth/법령 API와 실제 secret 없이 CI가 실행된다.
- public map/trade API와 property-data DB 데이터는 변경하지 않는다.

## 배포 순서

`user-service-test`와 `ai-service-test`가 GREEN이 된 뒤 API, Batch, Migration,
Admin, User, AI image build를 통합한다. ECR은 git SHA immutable tag와 GitHub
OIDC를 사용한다. 이후 ECS task, RDS, EventBridge, secret injection, DNS 순으로
AWS 준비를 진행한다.

## 중단 조건

공개 property-data API 변경, cross-database join, applied migration 수정, 데이터
삭제, 출처/라이선스 불명 dataset import, user/admin JWT 경계 공유, Docker volume
삭제가 필요하면 구현을 중단하고 별도 승인을 받는다.

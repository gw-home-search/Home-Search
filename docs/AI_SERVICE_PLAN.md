# 근거 데이터 중심 AI 챗봇 계획

## 상태와 목표

ai-service는 user-service 다음의 later-scope 확장 milestone이다. 참조 구현
`/Users/gwongwangjae/kosa-team5`는 질문 유형과 UX 흐름만 읽기 전용으로 참고하며
코드, fixture, POI, 법령 corpus를 이관하지 않는다.

목표는 검증된 전국 데이터로 답할 수 있는 질문만 단계적으로 활성화하는 것이다.

- 기존 부동산 사실은 `home_search.ai_read`를 SELECT-only로 사용한다.
- 신규 공식 dataset은 원본 보존과 품질 검사를 통과한 active snapshot만 사용한다.
- 모든 답변은 LLM을 통과하지만 observation에 없는 사실은 서버 검증에서 차단한다.
- 대화는 서버에 저장하지 않고 브라우저 IndexedDB에만 저장한다.
- 기존 property-data 공개 API URL과 응답 계약은 변경하지 않는다.

세부 기준은 다음 문서가 소유한다.

- [CHATBOT_CAPABILITY_REGISTRY.md](CHATBOT_CAPABILITY_REGISTRY.md)
- [CHATBOT_DATA_SOURCES.md](CHATBOT_DATA_SOURCES.md)
- [CHATBOT_API_CONTRACT.md](CHATBOT_API_CONTRACT.md)
- [ADR 0001](adr/0001-evidence-grounded-chatbot-and-browser-memory.md)

## 서비스와 데이터 경계

```text
Browser IndexedDB -> recent conversationContext -> chat-bff
Browser -> chat-bff (JWT, rate limit, JSON/SSE) -> ai-service
ai-service -> home_search.ai_read (SELECT only)
ai-service -> home_search_ai (dataset metadata, quality, reference, RAG)
```

- `apps/ai`: FastAPI, intent/capability routing, readiness, tools, fact validation,
  recommendation calculation, legal RAG, LLM supervisor/fallback.
- `apps/chat-bff`: Java 21 Spring WebFlux, JWT double verification boundary,
  subject rate limit, request ID, AI timeout/error mapping, public JSON/SSE.
- `apps/web`: chatbot UI, IndexedDB multi-conversation, context selection,
  import/export/delete.
- `home_search`: property-data ownership. AI write와 public schema direct access 금지.
- `home_search_ai`: reference dataset과 품질·게시·RAG evidence. 대화 저장 금지.

AI는 두 DB에 별도 pool을 사용하고 DB 간 SQL join을 하지 않는다. 결합은
`complex_id`, 행정구역 코드, 검증된 좌표 등 명시된 key로 application에서 수행한다.

## 답변 생성 계약

1. 질문을 Capability로 분해한다.
2. 필수 dataset의 active version, 품질, freshness를 검사한다.
3. 도구가 `factId`, 값, 단위, 출처, version/조회 시각, evidence grade를 생성한다.
4. LLM에는 검증된 fact, limitation, 제한된 context만 전달한다.
5. LLM은 답변과 사용한 `factId`를 구조적으로 반환한다.
6. 서버가 fact 존재, 수치·단위 일치, citation 누락을 검증한다.
7. 검증된 fact로만 citation을 조립한다.
8. 근거 부족도 LLM이 부족한 dataset과 가능한 다음 질문으로 설명한다.
9. 1차 model 재시도 후 2차 model로 전환한다. 모두 실패하면 `503`/SSE error다.

추천 후보 필터와 점수는 A/B 데이터와 사용자의 명시 조건으로 결정론적으로 계산한다.
LLM은 후보와 계산 근거를 설명할 뿐 점수와 사실을 만들지 않는다.

## 구현 Slice

각 slice는 `계약 합의 -> 최초 RED -> 최소 GREEN -> 데이터 준비 보고서 ->
품질·계약·보안 검토 -> 사용자 승인` 순서로 닫는다. 순수 문서 slice는 RED를 면제한다.

### Slice 0: 질문·데이터·API 계약

- 질문 카탈로그와 Capability Registry
- 데이터 출처 카탈로그와 게시 품질 기준
- JSON/SSE 공개 계약
- browser-only conversation ADR
- 모든 질문을 `지원|데이터 준비 중|미지원`으로 분류

### Slice 1: AI/BFF 최소 골격

- 외부 API/LLM 없이 health, request ID, JWT 거부, timeout/error, SSE error RED
- `apps/ai`, `apps/chat-bff` build와 stub provider
- 상태: skeleton 구현 완료. 외부 provider와 부동산 도구는 연결하지 않았으며 모든
  실제 질문 Capability는 계속 `unavailable`이다.

### Slice 2: 데이터 수집·품질·게시 기반

- 작은 fixture로 source/acquisition/raw/publication/quality/quarantine 모델
- checksum 멱등성, validation, atomic publish, rollback
- 상태: 구현 및 fixture 검증 완료. `home_search_ai` 운영 DB에는 아직 적용하지 않았고,
  실제 챗봇 Capability도 활성화하지 않았다. 검증 근거와 공백은
  `docs/reports/CHATBOT_SLICE_2_DATA_READINESS.md`에 기록한다.

### Slice 3: 기존 부동산 데이터 준비도 감사

- 실제 단지·거래 수, 최신일, 지역 coverage, 좌표, 매칭 실패 보고
- `ai_read` view와 최소 SELECT role
- `complex_id`, `complex_pk`, `apt_seq`, `source`, `source_key` 의미 보존
- 상태: 구현·실제 local DB 활성화 완료. 결과는
  `docs/reports/CHATBOT_SLICE_3_PROPERTY_READINESS.md`에 기록한다.

### Slice 4: 근거 답변 kernel과 단순 조회

- 단지 식별, 최근 실거래, 기간별 가격 추이, 거래량
- observation -> LLM -> fact/citation validation
- 상태: kernel과 `ai_read` reader, OpenAI Responses strict Structured Outputs
  adapter를 구현했다. adapter는 `store: false`, 응답·token·timeout 제한,
  primary 1회 재시도 후 secondary 전환, 설정 누락 fail-closed를 적용한다.
  local runtime secret 주입과 live provider 검증 전에는 Capability를
  활성화하지 않으며 `Partial` 근거는
  `docs/reports/CHATBOT_SLICE_4_GROUNDED_KERNEL.md`에 기록한다.
  2026-07-18 운영 DB offline 전체 검증과 승인된 OpenAI live 대표 질문,
  JSON/SSE 계약 회귀를 통과한 `complex_identity`, `recent_trade_lookup`,
  `price_trend`를 누적 runtime allowlist로 활성화했다. recent-trade 금액은 원본
  `10_000_KRW`와 서버가 계산한 `KOREAN_KRW_DISPLAY` claim을 함께 제공해 LLM의
  임의 단위 환산을 차단한다. price-trend도 평균·최저·최고 원본 단위와 서버 계산
  한국어 표시 claim을 함께 제공하며 모든 월별 fact 사용을 검증한다.

### Slice 5: BFF와 최소 UI

- gateway chatbot route, JWT 이중 검증, Redis subject rate limit fail-closed
- IndexedDB 다중 대화와 import/export/delete
- JSON/SSE 의미 일치
- 상태: BFF subject 기준 Redis rate limit과 browser-only IndexedDB 대화/UI는
  구현·검증 완료했다. 기본 Compose를 변경하지 않는 opt-in AI/BFF/gateway
  overlay와 JWT·DB credential preflight runner를 추가했다. 실제 route는 preflight
  통과 후에만 기동한다. provider adapter와 승인된 누적 Capability gate를
  구현했으며, 누락·오류·미승인 allowlist 설정은 fail-closed한다. 검증된 완성
  answer만 Unicode-safe `answer_delta`로 나눈 뒤 `final`을 보내며 delta 결합은
  final answer와 byte-for-byte 동일하다. AI 전체 query budget은 local runtime에서
  최대 `60s`, BFF timeout은 `70s`로 정렬해 AI가 먼저 fail-closed한다.
  `Partial` 근거는
  `docs/reports/CHATBOT_SLICE_5_BROWSER_BFF_FOUNDATION.md`에 기록한다.

### Slice 6: 교육 데이터

- 학교 위치·상태, 초등 통학구역, 중·고 학교군, 학원·교습소
- 학교 ID dedupe, 좌표/polygon, 경계와 전국 coverage 검증

### Slice 7: 교통·의료·보육 공식 데이터

- 도시철도 역사, HIRA 병원, 전국 어린이집
- 기관 ID dedupe, 운영 상태, 좌표, freshness 검증

### Slice 8: Kakao 실시간 장소 탐색

- keyword/category/address/coordinate adapter와 quota 관측
- 검색 조건·시각 표시, 정책 승인 범위 내 무저장 또는 TTL cache

### Slice 9: 단지 비교

- 동일 기간·단위·평형 기준 비교
- 공식 dataset과 Kakao 보조 결과 구분

### Slice 10: 조건 기반 추천

- 예산·지역·면적·거래 시점 deterministic filter
- 공개 가중치 score와 Kakao soft enrichment

### Slice 11: 부동산 계약 법률 RAG

- 국가법령정보 원본, 법령 ID, 공포·시행일, version, 조문 chunk/embedding
- 일반 정보 고지와 조문 citation

### Slice 12: 공식 최신 웹 근거

- 지자체·국토부·공공기관 allowlist
- 공식 원문 URL·게시일을 확인한 정비사업 D등급 근거

### Slice 13: 복합 질문과 최종 UX

- 하위 Capability 분해·독립 검증·부분 실패
- browser context 재검증과 기준일 충돌 처리

### Slice 14: 운영 강화와 단계 활성화

- dataset freshness, quarantine, coverage, fact/citation 실패 관측
- 원문 없는 로그/trace, feature flag, snapshot/gateway rollback

## 필수 검증

```bash
cd apps/ai && uv sync --frozen --group test && uv run pytest
cd apps/chat-bff && ./gradlew chatBffQualityCheck --no-daemon --stacktrace
cd apps/property-data && ./gradlew backendQualityCheck --no-daemon --stacktrace
cd apps/web && npm run lint && npm run test && npm run build
.github/scripts/test-classify-changes.sh
infra/nginx/test-property-public.sh
infra/postgres/verify-service-boundaries.sh
```

Compose config 검증은 root `AGENTS.md`에 정의된 validation-only password를 사용한다.
각 production code 완료에는 `code-review`와
`security-audit: 지적사항 = none|listed`가 필요하다.

## 전체 인수 기준

- 골든 질문 수치가 DB 또는 active snapshot과 일치한다.
- 모든 사실 문장이 유효한 fact ID와 citation을 가진다.
- 제공되지 않은 수치·시설·법령 생성은 응답을 차단한다.
- stale/failed dataset의 Capability는 자동 비활성화된다.
- Kakao를 전체 현황, 공식 학군, 통근 정보로 표현하지 않는다.
- 대화 원문은 서버 DB·로그·trace에 남지 않는다.
- 기존 property-data 공개 계약을 유지한다.
- Docker volume 삭제나 기존 전국 부동산 데이터 재수집 없이 구축한다.

## 중단 조건

다음 상황은 구현을 중단하고 재협의한다.

- 출처, 이용허락, 표시·저장 조건이 불명확하다.
- 전국 coverage 또는 이전 version 대비 증감 검사가 실패한다.
- `complex_id` 의미 변경이나 기존 데이터 재해석이 필요하다.
- 기존 공개 API 또는 응답 변경이 필요하다.
- 데이터 손실 migration 또는 Docker volume 초기화가 필요하다.
- 확보하지 않은 통근시간, 학교 품질, 투자 수익을 사실로 요구한다.
- LLM이 검증된 observation 밖의 사실을 사용해야 답할 수 있다.

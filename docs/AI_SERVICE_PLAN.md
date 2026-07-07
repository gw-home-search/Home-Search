# AI Service Plan (챗봇 이식 청사진)

`docs/RESTRUCTURING_PLAN.md` §8(D15, D18)의 ai-service를 구체화하는 설계 문서.
참조 구현은 `/Users/gwongwangjae/kosa-team5/server`(읽기 전용)이며, 이 챗봇을
home-search의 MSA 지도에 맞게 이식·분리하는 방법을 정의한다.

- 상태: 청사진. 지위는 later-scope — 구현 착수는 re-scope 결정 이후
- 작성일: 2026-07-06
- 상위 제약: RESTRUCTURING_PLAN의 D13(정책 공유 금지), D15(서비스 경계),
  D18(AI 구성요소 분리), §8.4(호출 방향 매트릭스)

## 1. 참조 구현 요약 (kosa-team5/server)

FastAPI 모놀리스로, 두 부분이 한 앱·한 DB에 동거한다.

```
app/real_estate/   부동산 read API (controller/service/dao) + 자체 DB
                   (regions/complexes/trades/pois, seed 데이터)
app/chatbot/       챗봇 오케스트레이션
├─ service/        splitter(질문 분해) → planner(rule 기반 실행 계획)
│                  → orchestrator(계획 실행) → supervisor(LLM 에이전트 fallback,
│                  tool-calling) → answer/composer+formatters → streaming
│                  conversation_memory(세션 페이로드 기반, TTL)
├─ service/tools/  feature별 tool 어댑터 5종
├─ features/       simple_lookup / price_trend / comparison / recommendation
│                  / legal_contract — 각자 slots(슬롯 추출)·service·dao·policy
│                  ├─ feature dao가 complexes/trades 등을 직접 SQL 조회
│                  └─ legal_contract/rag: 법령 API ingest → parser → indexing
│                     (임베딩) → query(intent/expansion/ranking) 완결 RAG
└─ embedding/      openai / sentence_transformer 이중 클라이언트 + similarity
```

계승 가치가 높은 설계:

- rule 기반 planner 우선 + LLM supervisor fallback — 단순 질문은 LLM 비용/지연
  없이 처리하고 복합 질문만 에이전트로 넘기는 비용 구조.
- feature 단위 수직 분해 (slots → service → 데이터 → formatter) — feature
  추가가 독립적.
- legal_contract RAG는 home 데이터와 무관한 자기완결 서브시스템.

## 2. 역할 경계 (home-search MSA 지도에서의 위치)

| | 내용 |
|---|---|
| ai-service가 소유 | `ai` 스키마: 대화 세션/메모리, 법령 코퍼스, 문서 청크, 임베딩(pgvector), vector index. Alembic으로 자체 마이그레이션 |
| ai-service가 소비 | 사실(facts) = `ai_read` 읽기 전용 뷰. 계산된 값(가격 예측 등) = api API |
| ai-service 금지 | `public` 스키마 테이블 직접 접근(권한으로 차단), ml-inference 직접 호출(§8.4), home_search 쓰기 |
| DB 권한 | `ai_service` 역할: `ai_read` SELECT + `ai` 스키마 ALL. `public` 권한 없음 |
| `ai_read` 뷰 소유 | core(Spring Flyway). 해제 거래 제외·match-failed 제외 등 도메인 필터를 뷰 정의에 인코딩 |
| 진입 경로 | web → api(BFF: 인증·rate limit·audit) → ai-service SSE. api 중계는 async 필수 |

## 3. 이식 매핑 (kosa → home-search)

| kosa 모듈 | home-search 대응 | 처리 |
|---|---|---|
| `app/real_estate/**` + `models.py` + `db/init` seed | Spring api + `home_search` DB가 이미 담당 | **이식하지 않음** (대체됨) |
| `app/chatbot/service/**` (splitter/planner/orchestrator/supervisor/tools/answer/streaming/memory) | `apps/ai` 코어 파이프라인 | 구조 계승 |
| `app/chatbot/features/*/dao.py` (complexes/trades/regions 직접 SQL) | `ai_read` 뷰 조회로 치환 | **이식의 핵심 작업** |
| `app/chatbot/features/legal_contract/rag/**` | ai-service 완전 소유 (`ai` 스키마) | 거의 그대로 이식 (home 데이터 의존 없음) |
| `app/chatbot/embedding/**` | ai-service 내부 | 계승 |
| `pois` 테이블 의존 (recommendation 등) | home_search에 POI 없음 | 해당 슬롯/필터는 1차 이식에서 제외, POI 도입은 별도 스코프 결정 |
| 대화 메모리 (세션 페이로드) | 초기: 계승(무DB). 이력 보존 필요 시 `ai.chat_session`으로 승격 | 단계적 |

feature별 데이터 소스 매핑:

| feature | 데이터 소스 |
|---|---|
| simple_lookup (시세/단지 조회) | `ai_read.complex_fact_v`, `ai_read.trade_fact_v` |
| price_trend | `ai_read.trade_fact_v` 집계 또는 전용 트렌드 뷰 |
| comparison | 위 뷰들의 조합 |
| recommendation | `ai_read` 뷰 + ai-service 내부 선정 로직. 가드레일의 later-scope "recommendations"는 챗봇 기능으로서만 존재하며 property-data API 표면에 추가하지 않는다 |
| legal_contract | 자체 RAG (법령 API → `ai` 스키마 코퍼스). home 데이터 불필요 |
| (미래) 가격 예측 질문 | api 예측 API 경유 (D18 — ml-inference 직접 호출 금지) |

## 4. 목표 구조 (apps/ai)

```
apps/ai/
├─ pyproject.toml / requirements
├─ alembic/                      # ai 스키마 전용 마이그레이션
└─ ai_service/
    ├─ main.py                   # FastAPI 조립 + SSE
    ├─ config.py                 # LLM provider·키·DB 접속 (ai_service 역할)
    ├─ facts/
    │   └─ facts_repository.py   # ai_read 뷰 조회 단일 진입점.
    │                            # 뷰 이름이 등장하는 유일한 모듈
    ├─ chat/                     # kosa service/ 계승
    │   ├─ splitter.py / planner.py / orchestrator.py / supervisor.py
    │   ├─ tools/                # feature tool 어댑터
    │   ├─ answer/               # composer + formatters
    │   ├─ streaming.py / memory.py
    ├─ features/                 # kosa features/ 계승 (dao → facts_repository 위임)
    │   ├─ simple_lookup / price_trend / comparison / recommendation
    │   └─ legal_contract/rag/   # 자기완결 RAG (ingest/indexing/query)
    └─ embedding/                # openai / local 이중 클라이언트
```

규율:

- SQL이 등장하는 곳은 `facts/`(ai_read 뷰)와 `legal_contract/rag/dao`(ai 스키마)
  둘뿐이다. feature 코드에 SQL 산재 금지 — 뷰 계약의 의미를 지키는 조건.
- LLM provider 호출은 supervisor/embedding 클라이언트로 격리. 모델명은 설정으로.
- ml-inference(apps/ml)와 코드·의존성·배포를 공유하지 않는다 (D18).

## 5. 단계적 이식 순서 (re-scope 이후)

```
A0  선행 (core 쪽): ai_read 스키마 + 뷰 2~3개 + ai_service 역할 Flyway 추가
    api BFF 중계 경로 (async SSE) 골격
A1  파이프라인 골격 + simple_lookup + price_trend
    (뷰 소비 검증이 목적. planner는 rule 경로만으로 시작 가능)
A2  comparison + supervisor(LLM fallback) + streaming 완성
A3  legal_contract RAG 이식 (ai 스키마 + pgvector + 법령 ingest 배치)
    ※ home 데이터 무의존이라 A1과 순서 교체 가능
A4  recommendation (POI 제외 버전) + 대화 메모리 DB 승격 검토
```

각 단계는 지도/거래 표면에 영향 없음이 완료 조건에 포함된다
(ai-service 중단 상태에서 map/trade 엔드포인트 정상 동작 확인).

## 6. 운영·보안 메모

- 시크릿: LLM API key, 법령 API key는 ai-service 전용. property-data의 공공 API
  키와 저장·주입 경로를 분리한다.
- 비용 가드: supervisor(LLM) 경로 호출률과 토큰 사용량을 메트릭으로 노출.
  rule planner가 처리한 비율이 떨어지면 프롬프트/신호 사전을 먼저 점검.
- 배포: 독립 컨테이너. compose에 서비스 1개 추가 (RESTRUCTURING_PLAN §5.4의
  배포 그림과 동일 패턴). 스케줄성 작업(법령 재색인)은 EventBridge 스케줄
  패턴(D12)을 재사용.

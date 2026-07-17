# ADR 0001: 근거 데이터 중심 챗봇과 브라우저 대화 저장

- 상태: Accepted
- 결정일: 2026-07-16
- 범위: `apps/ai`, `apps/chat-bff`, `apps/web`, `home_search_ai`

## Context

참조 프로토타입은 단순 조회, 가격 추이, 비교, 추천, 법률 RAG, 복합 질문과
대화 memory 흐름을 제공한다. 그러나 Home Search는 전국 운영 데이터의 출처와
품질을 설명해야 하며, 출처가 불명확한 POI나 LLM의 일반 지식을 수치·추천 근거로
사용할 수 없다. 사용자 대화를 서버에 장기 보관할 제품 요구도 없다.

## Decision

1. 질문 유형별 Data Readiness gate를 둔다. 출처, 품질, freshness가
   [Capability Registry](../CHATBOT_CAPABILITY_REGISTRY.md)를 통과한 기능만 실행한다.
2. 모든 답변은 LLM을 통과하되 LLM 입력을 검증된 observation과 limitation으로
   제한한다. LLM이 반환한 `factId`와 수치·단위·citation을 서버가 검증한 뒤에만
   응답한다.
3. `apps/ai`는 `home_search.ai_read`를 SELECT-only로 읽고, reference/RAG 데이터는
   별도 `home_search_ai`에 저장한다. DB 간 SQL join은 금지한다.
4. `apps/chat-bff`가 JWT, subject rate limit, JSON/SSE 공개 계약과 오류 변환을
   소유한다. ai-service도 JWT를 다시 검증한다.
5. 대화는 `apps/web`의 IndexedDB에만 저장한다. 최근 문맥 일부를 매 요청에
   전달하며 서버는 이를 신뢰할 수 없는 힌트로 다시 검증한다.
6. 사용자 질문, 답변, context, access token을 서버 DB, 일반 로그, trace에 저장하지 않는다.
7. SSE도 완성된 답변 검증 뒤에만 answer chunk를 보낸다.

## Alternatives

### 서버 DB conversation memory

멀티 디바이스 동기화는 쉬워지지만 개인정보·보존·삭제 정책과 침해 범위가 커진다.
현재 요구에는 필요하지 않아 채택하지 않는다.

### LLM 일반 지식과 실시간 검색 우선

초기 질문 범위는 넓지만 사실 재현성과 출처·기준일을 보장하기 어렵다. 공식 dataset이
준비되지 않은 질문은 비활성화하는 방식을 채택한다.

### 정형 template만 반환

fact 안전성은 높지만 모든 사용자 답변이 LLM을 통과해야 한다는 제품 요구를 충족하지
못한다. 대신 LLM output을 구조 검증하고 실패 시 응답을 차단한다.

## Consequences

- 신규 질문의 출시는 코드보다 dataset 수집·품질·게시 작업이 먼저다.
- 브라우저 데이터 삭제나 기기 변경 시 대화가 사라지며 멀티 디바이스 동기화는 없다.
- context 변조는 가능하므로 단지·기간·조건을 매번 다시 검증해야 한다.
- provider 장애 시 정형 fact만 노출하는 fallback은 없고 명시적 `503`/SSE error가 발생한다.
- 기존 property-data 공개 API와 DB 쓰기 경로는 영향을 받지 않는다.

## Revisit Conditions

서버 대화 동기화가 별도 제품 요구가 되거나 법적 보존 의무가 생기면 개인정보 영향,
암호화, 보존 기간, 삭제·export 계약을 별도 ADR로 승인한 뒤 재검토한다.

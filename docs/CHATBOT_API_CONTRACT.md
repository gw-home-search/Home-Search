# 챗봇 공개 API 계약

## 범위와 소유권

이 문서는 `apps/chat-bff`가 소유할 인증 챗봇 공개 계약이다. 기존 property-data
공개 계약은 계속 [API_CONTRACT.md](API_CONTRACT.md)가 단독으로 소유하며,
이 문서는 그 URL, 응답 shape, 단위, 오류 의미를 변경하지 않는다.

- `POST /api/v1/chatbot/query`
- `POST /api/v1/chatbot/query/stream`
- 두 endpoint 모두 user-service access token이 필요하다.
- JSON과 SSE는 하나의 use case를 실행하고 동일한 최종 response 의미를 가진다.
- 모든 성공 답변은 LLM을 통과하지만 검증된 fact 밖의 사실은 포함할 수 없다.

## 인증과 공통 헤더

`Authorization: Bearer <access-token>`을 사용한다. BFF와 ai-service는 각각
signature, `kid`, `iss=user-service`, `aud=home-search-user-api`, expiry, positive
numeric `sub`, `role=USER`를 검증한다. 사용자 id는 request body에서 받지 않는다.

- BFF는 유효한 `X-Request-Id`가 있으면 사용하고, 없거나 유효하지 않으면 UUID를 생성한다.
- 모든 response는 `X-Request-Id`를 반환한다.
- access token과 `question`, `answer`, `conversationContext`는 로그·trace에 기록하지 않는다.

## 요청

```json
{
  "question": "잠실엘스 전용 84㎡ 최근 1년 실거래를 알려줘",
  "conversationContext": {
    "messages": [
      { "role": "user", "content": "잠실엘스 위치 알려줘" },
      { "role": "assistant", "content": "..." }
    ]
  }
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `question` | string | yes | trim 후 1..2000자 |
| `conversationContext` | object | no | 브라우저가 선택한 최근 문맥; 신뢰할 수 없는 힌트 |
| `conversationContext.messages` | array | no | 최근 순서 최대 12개, 전체 `content` 합계 최대 12000자 |
| `role` | `user|assistant` | yes | `system`, `tool` 등은 허용하지 않음 |
| `content` | string | yes | trim 후 1..2000자 |

알 수 없는 top-level/context 필드는 `400 INVALID_CHATBOT_REQUEST`로 거부한다.
`conversationContext`의 단지, 기간, 조건은 권한이나 fact가 아니며 현재 요청에서
다시 식별·검증한다. 서버는 대화 context를 DB에 저장하거나 다음 요청을 위해 보존하지 않는다.

## JSON 응답

`POST /api/v1/chatbot/query`는 `application/json`으로 아래 top-level shape를 반환한다.
legacy 호환 필드는 유지하고 근거 metadata를 추가한다.

```json
{
  "success": true,
  "status": "success",
  "question": "잠실엘스 전용 84㎡ 최근 1년 실거래를 알려줘",
  "fragments": [],
  "result": {},
  "message": "",
  "executionSummary": { "total": 1, "succeeded": 1, "failed": 0 },
  "answer": "...",
  "resolvedQuestion": "...",
  "conversationResolution": null,
  "conversationMemoryPatch": null,
  "uiActions": [],
  "uiArtifacts": [],
  "uiSummary": null,
  "requestId": "b8f12b67-0369-4e4a-bf5f-ce8af0315386",
  "citations": [
    {
      "citationId": "citation-1",
      "sourceId": "property.ai_read",
      "sourceName": "Home Search 실거래",
      "sourceUrl": null,
      "evidenceGrade": "A",
      "datasetVersion": "property-2026-07-16",
      "dataAsOf": "2026-07-15",
      "observedAt": null,
      "factIds": ["fact-trade-501"]
    }
  ],
  "dataAsOf": "2026-07-15",
  "limitations": ["신고 취소 또는 지연 신고가 이후 반영될 수 있습니다."],
  "evidenceSummary": {
    "status": "supported",
    "capabilities": ["recent_trade_lookup"],
    "factCount": 1,
    "citationCount": 1
  }
}
```

### 실행 상태와 근거 준비 상태

legacy `success`와 `status` 의미는 유지한다.

- 모든 fragment 처리: `success=true`, `status=success`
- 하나 이상 처리: `success=true`, `status=partial_success`
- 처리 가능한 fragment 없음: `success=false`, `status=failed`

근거 준비 상태는 별도 `evidenceSummary.status`가 소유한다.

- `supported`: 요청한 모든 Capability가 근거 기준을 충족했다.
- `partial`: 일부 Capability만 충족했다. `limitations`에 누락 범위를 표시한다.
- `unavailable`: 필수 dataset 또는 freshness가 부족하다. 추정 대신 부족한 데이터와
  가능한 다음 질문을 LLM이 설명한다.

`partial`과 `unavailable`도 처리된 사용자 질문 결과이므로 HTTP `200`이다.
`unavailable`이면 legacy 실행 상태는 `success=false`, `status=failed`이고, LLM이
작성한 데이터 부족 안내를 `answer`로 반환한다. 인증, validation, rate limit,
내부·provider 장애는 ProblemDetail 또는 SSE `error`다.

### 근거 필드 규칙

- `answer`의 모든 사실·수치 문장은 하나 이상의 유효한 `factId`를 사용해야 한다.
- `citations[].factIds`는 실제 observation에 존재하는 id만 포함한다.
- `dataAsOf`는 사용한 snapshot 기준일 중 가장 오래된 날짜다. 실시간 응답만 사용한
  경우 `null`이고 각 citation의 `observedAt`을 사용한다.
- `limitations`는 항상 array이며 없으면 `[]`이다.
- `sourceUrl`은 공개 가능한 공식 landing/original URL만 허용하고 인증 query를 포함하지 않는다.
- `result`, `fragments`, `uiArtifacts`에 내부 prompt, provider credential, SQL, raw
  LLM trace를 노출하지 않는다.

## SSE 응답

`POST /api/v1/chatbot/query/stream`은 `text/event-stream`을 반환한다.

```text
event: status
data: {"requestId":"...","stage":"validating_evidence"}

event: artifacts
data: {"requestId":"...","uiActions":[],"uiArtifacts":[]}

event: answer_delta
data: {"requestId":"...","delta":"검증된 답변 일부"}

event: final
data: {"requestId":"...","response":{...JSON 응답과 동일한 객체...}}
```

허용 event는 `status`, `artifacts`, `answer_delta`, `final`, `error`다.

1. 서버는 도구 실행, LLM 생성, fact/citation 검증을 모두 완료한다.
2. 검증 완료 전에는 `answer_delta`와 `artifacts`를 보내지 않는다.
3. 검증된 완성 답변만 순서대로 chunking한다.
4. `answer_delta`를 합치면 `final.response.answer`와 byte-for-byte 동일해야 한다.
5. 정상 stream은 `final` 한 번으로 끝난다.
6. stream 실패 후 클라이언트가 JSON endpoint를 자동 호출하면 안 된다.

HTTP response가 시작되기 전 오류는 아래 ProblemDetail을 반환한다. 시작된 뒤 오류는
다음 event로 종료하며 `final`을 보내지 않는다.

```text
event: error
data: {"requestId":"...","code":"CHATBOT_PROVIDER_UNAVAILABLE","message":"답변을 생성하지 못했습니다."}
```

## 오류 계약

오류 body는 Spring `ProblemDetail` 기본 필드와 `code`, `requestId`를 가진다.

```json
{
  "type": "about:blank",
  "title": "Authentication required",
  "status": 401,
  "detail": "로그인이 필요합니다.",
  "instance": "/api/v1/chatbot/query",
  "code": "AUTHENTICATION_REQUIRED",
  "requestId": "b8f12b67-0369-4e4a-bf5f-ce8af0315386"
}
```

| HTTP | code | 의미 |
|---|---|---|
| `400` | `INVALID_CHATBOT_REQUEST` | 질문/context shape 또는 길이 오류 |
| `401` | `AUTHENTICATION_REQUIRED` | token 누락·만료·검증 실패 |
| `429` | `CHATBOT_RATE_LIMITED` | subject 기반 요청 제한 또는 비용 예산 초과 |
| `503` | `CHATBOT_RATE_LIMIT_UNAVAILABLE` | Redis guard 장애로 fail-closed |
| `503` | `CHATBOT_PROVIDER_UNAVAILABLE` | 1차 재시도와 2차 provider 모두 실패 |
| `504` | `CHATBOT_TIMEOUT` | BFF의 bounded ai-service timeout |

오류에는 stack trace, 내부 URL, model/provider 이름, prompt, 질문 원문을 넣지 않는다.

## Empty와 실패 의미

- 검증된 검색 범위 fact가 있는 정상 조회 0건은 HTTP `200`,
  `evidenceSummary.status=supported`로 반환할 수 있다. 필수 observation이나 dataset이
  부족한 0건은 `unavailable|partial`로 반환한다.
- Kakao 0건을 시설 부재로 표현하지 않는다.
- Data Readiness 실패 시 도구를 실행하지 않고 필요한 dataset을 설명한다.
- LLM 검증 실패 시 정형 observation을 대신 최종 답변으로 노출하지 않는다.
- 모든 model 시도가 실패하면 `503` 또는 시작된 SSE의 `error`로 종료한다.

## 호환성 규칙

- 기존 property-data endpoint는 로그인·챗봇 상태에 의존하지 않는다.
- additive chatbot metadata는 위 필드명과 타입을 유지한다.
- JSON/SSE fixture는 같은 use case 결과의 의미와 citation 집합을 비교한다.
- 계약 변경은 이 문서, BFF contract test, web adapter fixture를 같은 slice에서 갱신한다.

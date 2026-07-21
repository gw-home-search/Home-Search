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
  "uiSummary": {
    "version": 1,
    "scopeNotice": {
      "text": "‘잠실엘스’ 단지를 기준으로 확인했습니다.",
      "factIds": ["property-complex-501"]
    },
    "headline": {
      "text": "잠실엘스의 확인된 단지 정보를 정리했습니다.",
      "factIds": ["property-complex-501"]
    },
    "criteria": [],
    "interpretations": [],
    "followUp": "최근 실거래, 가격 흐름 또는 주변 시설을 이어서 확인할 수 있습니다.",
    "fragmentSummaries": []
  },
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

### 복합 질문 fragment

단일 Capability 질문은 호환을 위해 `fragments=[]`를 유지한다. 두 개 이상의
Capability로 분해된 질문은 정적 catalog 순서로 최대 4개의 검증 완료 fragment를
채운다. 같은 Capability의 호환 가능한 list 조건은 한 plan으로 합치며, 서로 충돌하는
단지·기간·면적·반경 조건의 중복 plan은 provider validation 실패로 처리한다.

```json
{
  "fragmentId": "fragment-1",
  "capability": "comparison",
  "status": "success",
  "answer": "...",
  "factIds": ["fact-comparison-501"],
  "artifactIds": ["comparison-501-502"],
  "actionIds": [],
  "limitations": []
}
```

- `fragmentId`는 response 안에서 `fragment-1`부터 순서대로 부여한다.
- `status`는 `success|failed`만 허용한다. source inactive/stale, 단지 좌표 없음,
  정상적인 근거 부족은 해당 fragment만 `failed`가 되며 다른 fragment를 제거하지 않는다.
- `factIds`는 해당 fragment가 검증에 사용한 fact만 포함하고 모두 top-level citation의
  `factIds`에 존재해야 한다.
- `artifactIds`와 `actionIds`는 같은 response의 `uiArtifacts[].artifactId`와
  `uiActions[].actionId`에 실제로 존재하는 값만 포함한다.
- fragment별 observation은 3초로 제한하고, 전체 요청은 기존 query timeout을 유지한다.
  provider/internal/grounding validation 장애는 부분 성공으로 위장하지 않고 기존
  ProblemDetail 또는 SSE `error`로 처리한다.

### 근거 필드 규칙

- `answer`의 모든 사실·수치 문장은 하나 이상의 유효한 `factId`를 사용해야 한다.
- `citations[].factIds`는 실제 observation에 존재하는 id만 포함한다.
- `dataAsOf`는 사용한 snapshot 기준일 중 가장 오래된 날짜다. 실시간 응답만 사용한
  경우 `null`이고 각 citation의 `observedAt`을 사용한다.
- `limitations`는 항상 array이며 없으면 `[]`이다.
- `sourceUrl`은 공개 가능한 공식 landing/original URL만 허용하고 인증 query를 포함하지 않는다.
- `result`, `fragments`, `uiArtifacts`에 내부 prompt, provider credential, SQL, raw
  LLM trace를 노출하지 않는다.

### 구조화 답변 artifact 계약

`uiArtifacts`는 검증된 `EvidenceFact`에서 서버가 결정론적으로 조립한 표시 모델이다.
LLM이 artifact의 값, 점수, 순서 또는 `factIds`를 만들지 않는다. 클라이언트는 모르는
`type`을 무시하고 `answer`와 `citations`를 계속 표시해야 한다. Markdown parsing,
임의 HTML, generic component registry, dynamic import는 계약에 포함되지 않는다.

공통 제한:

- response당 `fragments` 최대 4개, `uiArtifacts` 최대 8개다.
- `uiArtifacts` 전체 JSON 직렬화 크기는 UTF-8 기준 최대 65,536 bytes다.
- 모든 `label`, row/column header, card title은 trim 후 1..100자다.
- 사용자에게 표시하는 개별 문자열은 trim 후 1..2,000자다.
- `factIds`는 해당 response observation에 실제로 존재하는 id만 포함하고 중복할 수 없다.
- 외부 source 문자열은 text로만 렌더링하며 HTML로 해석하지 않는다.
- 제한을 넘거나 schema가 잘못된 artifact 하나는 전체 답변을 실패시키지 않고 제외한다.

허용 artifact는 아래 여섯 종류뿐이다. `answer`는 구조화 표시 여부와 무관하게
항상 보존하는 text fallback이다.

### `uiSummary/v1`

`uiSummary`는 기존 nullable seam을 사용하는 optional additive field다. `null`도 계속
유효하며 URL, request body, `result`, `answer`, JSON/SSE 상태 의미는 바꾸지 않는다.

- `scopeNotice`: `null` 또는 1개. 질문을 어떤 단지·지역 범위로 해석했는지 표시한다.
- `headline`: 정확히 1개.
- `criteria`: 최대 8개. 각 항목은 `key`, `label`, `value`, `factIds`를 가진다.
- `interpretations`: 최대 4개. 각 항목은 `key`, `label`, `text`, `factIds`를 가진다.
- `followUp`: `null` 또는 최대 2,000자의 사용 안내다.
- `fragmentSummaries`: 복합 질문에서만 최대 4개다.
- 사실 문장은 실제 observation에 존재하는 `factIds`를 하나 이상 사용한다.
- client는 exact `version=1`과 strict shape를 모두 확인한다. unknown version이나 malformed
  object는 무시하고 `answer`를 표시한다.
- 유효한 summary가 있으면 Web은 범위 → 결론 → 조건 → artifact → 해석 → 제한사항 →
  후속 안내/action → 답변 근거 순서로 표시하며 전체 `answer`를 중복 표시하지 않는다.

#### `factList/v1`

```json
{
  "type": "factList",
  "version": 1,
  "artifactId": "artifact-1",
  "title": "확인된 단지 정보",
  "items": [{
    "label": "단지명",
    "value": "잠실엘스",
    "factIds": ["property-complex-501"]
  }]
}
```

- `items`는 1..10개다.
- 각 item의 `factIds`는 비어 있을 수 없다.
- `value`는 표시 문자열이며 source 원문을 HTML로 포함하지 않는다.

#### `comparisonTable/v1`

```json
{
  "type": "comparisonTable",
  "version": 1,
  "artifactId": "artifact-2",
  "title": "동일 기준 단지 비교",
  "columns": [
    { "key": "501", "label": "잠실엘스", "factIds": ["property-complex-501"] },
    { "key": "502", "label": "헬리오시티", "factIds": ["property-complex-502"] }
  ],
  "rows": [{
    "key": "latestTrade",
    "label": "가장 최근 거래",
    "cells": [
      {
        "availability": "available",
        "value": "20억 5,000만원",
        "unit": "10_000_KRW",
        "reason": null,
        "factIds": ["fact-trade-501"]
      },
      {
        "availability": "unavailable",
        "value": null,
        "unit": "10_000_KRW",
        "reason": "동일 면적의 최근 거래 표본이 부족합니다.",
        "factIds": []
      }
    ]
  }],
  "basis": {
    "cutoffDate": "2026-07-20",
    "startDate": "2025-07-21",
    "exclusiveAreaSquareMeters": 84
  }
}
```

- `columns`는 2..4개, `rows`는 1..12개이며 각 row의 cell 수는 column 수와 같다.
- 각 column의 `factIds`는 단지 식별 fact를 하나 이상 포함한다.
- `availability`는 `available|unavailable`이다.
- `available` cell은 `value`와 비어 있지 않은 `factIds`가 필요하다.
- `unavailable` cell은 `value=null`, 구체적인 `reason`이 필요하다. 관측된 준비상태나
  표본 부족 fact가 있으면 그 id를 사용하고, 그런 fact가 없으면 `factIds=[]`를 사용한다.
- 금액 unit은 기존 `10_000_KRW` 의미를 바꾸지 않는다.
- `basis`는 모든 column에 동일하게 적용된 cutoff, 365일 window 시작일,
  전용면적(㎡)을 담으며 LLM이 생성하거나 column별로 바꿀 수 없다.
- 현재 질문에 학생 조건이 명시되면 요청 level별 최근접 운영 학교와 800m Sbiz 교육업소
  수를, 영유아 조건이 명시되면 800m 공식 운영 어린이집 수와 최근접 시설을 조건부 row로
  추가한다. 필요한 데이터가 아직 준비되지 않은 cell은 0이 아니라 이유가 있는
  `unavailable`이다.

#### `recommendationTable/v1`

가격·면적이 필수가 아닌 조건 기반 추천 결과다. 후보, metric, 정렬 순서와 `factIds`는
서버의 고정 catalog와 `criteria-recommendation-policy-v1`이 결정한다.

```json
{
  "type": "recommendationTable",
  "version": 1,
  "artifactId": "criteria-recommendation-2026-07-20-500-academy",
  "title": "조건 기반 후보",
  "policyVersion": "criteria-recommendation-policy-v1",
  "basis": {
    "scopeType": "ADMIN_REGION",
    "scopeLabel": "영등포구",
    "criteriaOrder": ["ACADEMY"],
    "minimumUnitCount": 500,
    "radiusMeters": 800
  },
  "rows": [{
    "order": 1,
    "complexId": 503,
    "complexName": "후보 단지",
    "unitCount": 1200,
    "metrics": {
      "ACADEMY": {
        "availability": "available",
        "value": 10,
        "unit": "COUNT",
        "nearestDistanceMeters": 100,
        "reason": null,
        "factIds": ["criteria-academy-503-2026-07-01"]
      }
    },
    "factIds": ["property-complex-503", "criteria-academy-503-2026-07-01"]
  }]
}
```

- `scopeType`: `ADMIN_REGION|STATION_RADIUS`. 역 범위는 active 철도 exact occurrence의
  좌표만 사용하고 property DB에는 좌표와 반경만 전달한다.
- `criteriaOrder`와 `metrics` key는 `TRANSIT|ACADEMY|SCHOOL|SHOPPING`만 허용한다.
- `MIN_UNIT_COUNT`는 source batch 관측 전에 적용한다.
- metric 하나는 해당 관찰값으로, 둘 이상은 사용자가 명시한 순서로 lexicographic
  정렬한다. 동점은 `complexId` 오름차순이다.
- `ACADEMY`는 800m 내 Sbiz 교육업소 count 내림차순, 최근접 거리 오름차순이다.
- `SCHOOL`, `TRANSIT`, `SHOPPING`은 available 직선거리 오름차순이다.
- `SHOPPING`은 active 공식 원장 중 좌표가 확인된 범위만 사용한다. 현재 source 전용
  최소 coverage는 88%이며, 미확인 행을 0건이나 시설 부재로 해석하지 않는다.
- `unavailable`은 0으로 바꾸거나 열세로 해석하지 않는다.
- row는 최대 5개, scope 후보는 최대 100개다.
- `CHILDCARE`는 source·타입 구현을 보존하지만 이 version의 active metric, 점수, 표,
  runtime allowlist에서 제외한다. 유치원은 별도 공식 source 승인 전 포함하지 않는다.

#### `recommendationCards/v1`

```json
{
  "type": "recommendationCards",
  "version": 1,
  "artifactId": "artifact-3",
  "title": "조건을 충족한 단지",
  "policyVersion": "recommendation-policy-v1",
  "cards": [{
    "rank": 1,
    "complexId": 501,
    "complexName": "잠실엘스",
    "totalScore": 87.5,
    "latestTrade": {
      "date": "2026-07-20",
      "amountTenThousandKrw": 195000,
      "factIds": ["fact-trades-501"]
    },
    "recentThreeMedian": {
      "amountTenThousandKrw": 198000,
      "factIds": ["fact-trades-501"]
    },
    "scoreBreakdown": [
      {
        "key": "PRICE",
        "label": "예산 조건",
        "weight": 60,
        "points": 60,
        "distanceMeters": null,
        "factIds": ["fact-trades-501"],
        "details": []
      },
      {
        "key": "TRANSIT",
        "label": "철도 접근성",
        "weight": 25,
        "points": 20,
        "distanceMeters": 300,
        "factIds": ["fact-rail-501"],
        "details": []
      },
      {
        "key": "SHOPPING",
        "label": "대규모점포 접근성",
        "weight": 15,
        "points": 7.5,
        "distanceMeters": 500,
        "factIds": ["fact-retail-501"],
        "details": []
      }
    ],
    "limitations": ["최근 365일 동일 면적 거래 3건과 직선거리 기준입니다."],
    "factIds": [
      "property-complex-501",
      "fact-trades-501",
      "fact-rail-501",
      "fact-retail-501"
    ],
    "activeThemes": []
  }]
}
```

- `cards`는 1..5개이며 `rank`와 정렬은 서버 정책 결과와 같아야 한다.
- 지역·최대 예산·전용면적 중 하나라도 없으면 observation과 추천을 실행하지 않고 누락
  조건을 `limitations`로 안내한다.
- `policyVersion`은 점수 정책을 고정하며 LLM은 `totalScore`와 breakdown을 변경하지 않는다.
- 기본 `recommendation-policy-v1`은 예산 조건 통과 60점, 최근접 철도역
  0..1,500m 선형 25점, 최근접 대규모점포 0..1,000m 선형 15점이다. 예산을 통과한
  후보 사이에는 가격 차이로 추가 점수를 주지 않는다.
- 후보는 요청 지역 또는 하위 지역, marker-safe 좌표, 전역 최신 거래일 기준 최근 365일,
  요청 전용면적 ±1.0㎡의 가장 최근 거래 3건을 모두 만족해야 하며 observation은 최대
  100개, 최종 card는 최대 5개다.
- 정렬은 `totalScore` 내림차순, 동점이면 `complexId` 오름차순이며 LLM이 바꿀 수 없다.
- 철도 또는 대규모점포 데이터가 준비되지 않으면 거리를 0점으로 바꾸지 않고 추천 전체를
  `unavailable`로 처리한다. 정상 active source에서 반경 내 시설이 없는 경우만 0점이다.
- 대규모점포 source는 전국 좌표 coverage가 88% 미만이면 준비되지 않은 것으로 처리하고,
  통과하더라도 좌표가 확인된 공식 원장 범위만 반영했다는 제한을 표시한다.
- 현재 질문에서 명시적으로 검증된 `TRANSIT|STUDENT|YOUNG_CHILD|SHOPPING`만
  `activeThemes`에 canonical order로 포함한다. 1개면 동적 25점 전부, 2개면 각 12.5점,
  3개면 각 `25/3`점을 배분하며 내부 계산은 반올림하지 않고 최종 `totalScore`만 소수점
  1자리로 반올림한다. 철도 기본 10점과 대규모점포 기본 5점은 항상 유지한다.
- `STUDENT`는 요청 level별 최근접 운영 학교 거리 50%와 800m 내 Sbiz 교육업소 수
  (5곳 cap) 50%다. 이는 통학구역·학군·학교 품질 또는 공식 등록 학원 수가 아니다.
- `YOUNG_CHILD` 계산 구조는 후속 연결을 위해 보존하지만 현재 runtime에서는 실행하지
  않는다. 어린이집·유치원은 각각 공식 source 승인과 별도 activation 전까지 추천 근거가
  아니다.
- 활성 생활조건 데이터가 freshness/coverage 기준을 충족하지 못하면 해당 점수를
  0으로 바꾸지 않고 추천을 `unavailable`로 처리한다.
- card, 거래 표시값, score breakdown의 사실 필드는 각각 비어 있지 않은 `factIds`가
  필요하고 실제 observation의 값·단위와 일치해야 한다.
- 투자성, 미래가격, 품질, 입소 가능 여부처럼 근거로 허용되지 않은 badge나 field는
  추가하지 않는다.

#### `tradeTable/v1`

- 최근 실거래 row는 최대 10개다.
- 각 row는 `tradeId`, `dealDate`, `exclusiveAreaSquareMeters`,
  `amountTenThousandKrw`, nullable `floor`, `factIds`를 가진다.
- artifact의 `amountUnit`은 `10_000_KRW`다.
- 정상 0건이면 표를 만들지 않고 확인 범위와 0건의 의미를 설명한다.

#### `trendTable/v1`

- 월별 row는 최대 24개이며 `month`, 평균·최솟값·최댓값, 거래 수,
  `availability=available|unavailable`, nullable `reason`, `factIds`를 가진다.
- 금액 unit은 `10_000_KRW`다.
- `unavailable` row는 금액과 거래 수가 `null`이고 구체적인 reason을 표시한다.
- 과거 관찰값만 전달하며 미래 추세를 나타내는 field나 chart schema는 포함하지 않는다.

### 지도 UI action 계약

`uiActions`는 서버가 검증된 단지 좌표 fact에서 만든 one-shot 명령이다. response당
최대 4개, 전체 JSON 직렬화 크기는 UTF-8 기준 최대 16,384 bytes다. AI와 BFF는
Kakao 장소 검색을 실행하지 않고, 사용자가 버튼을 누른 뒤 web이 기존 viewport
주변시설 endpoint를 호출한다.

허용 action은 `showNearbyCategory/v1` 하나뿐이다.

```json
{
  "type": "showNearbyCategory",
  "version": 1,
  "actionId": "action-1",
  "label": "지도에서 병원 보기",
  "category": "HOSPITAL",
  "center": { "lat": 37.5, "lng": 127.1 },
  "level": 4,
  "factIds": ["property-complex-501"]
}
```

- `category`는 이 기능에서 `HOSPITAL|DAYCARE_KINDERGARTEN`만 허용한다.
- `center`는 WGS84 유한 좌표이고 `level`은 정확히 `4`다.
- `factIds`는 marker-safe 단지 좌표를 증명하는 id를 하나 이상 포함해야 한다.
- 같은 `actionId`는 한 web session에서 한 번만 소비한다.
- action 실행 실패는 chat message를 실패시키거나 panel을 닫지 않는다.
- Kakao 장소 응답, 전화, URL은 chat message, server DB, IndexedDB archive에 저장하지 않는다.

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
- `uiSummary/v1`, `tradeTable/v1`, `trendTable/v1` 추가의 계약 판정은
  `compatible/additive`다. BFF는 payload를 재작성하지 않고 계속 passthrough한다.

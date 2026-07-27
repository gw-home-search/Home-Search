# ADR 0010: 근거 검증형 Agentic 챗봇 선택

- 상태: Accepted
- 결정일: 2026-07-27
- 범위: `apps/ai`, `apps/property-data`, `apps/chat-bff`, `apps/web`, `infra`

## Context

기존 live 추천은 서버가 후보와 순서를 결정하고 LLM은 설명만 생성한다. 이 구조는
근거 안전성은 높지만 조건 없는 질문을 세대수 중심 후보와 반복 문구로 축소하고,
suffix·오타·복수 단지 식별 및 종합 평가를 충분히 다루지 못한다.

## Decision

1. Responses function tool loop가 질문을 해석하고 검증된 read-only 도구를 최대 4 round,
   12 call 안에서 선택한다. 독립 read call은 병렬 실행한다.
2. 서버는 명시 예산·면적·지역·최소 세대수를 hard filter로 적용하고 marker-safe 후보를
   최대 40개까지만 모델에 제공한다. AI는 이 후보 밖의 단지를 선택할 수 없다.
3. 최종 후보와 순서는 AI가 결정한다. 서버는 선택 ID, 중복, hard filter, 수치·단위,
   `factIds`, citation과 금지 주장을 다시 검증한다.
4. 조건 없는 추천은 `BALANCED_V1`로 거래 활동, 규모, 연식, 교통, 생활 인프라의
   관측 가능한 근거를 비교한다. 점수나 미래 가격을 만들지 않는다.
5. 공식 웹 검색은 최신성 또는 내부 근거 공백이 있을 때만 노출하며 HTTPS allowlist
   공식 domain의 D등급 citation으로만 사용한다. 웹 근거는 A등급 내부 사실을 덮지 않는다.
6. primary, 동일 facts repair 1회, secondary 순으로 시도한다. 모두 실패하거나 60초 hard
   timeout에 도달한 경우에만 결정형 presenter를 `PARTIAL` 최소 fallback으로 사용한다.
7. 기존 endpoint와 top-level 응답은 유지한다. 신규 응답은 additive
   `recommendationTable/v2`를 사용하고 v1 artifact와 browser archive는 계속 지원한다.
8. SSE `status`는 제한된 진행 상태만 전달하며 grounding 완료 전 `answer_delta`를 보내지 않는다.
9. 질문, 답변, context, tool argument/result, 웹 검색어와 원문은 DB·일반 로그·trace에 저장하지 않는다.

## Consequences

- 추천 선택은 생성형이지만 사실 경계와 후보 membership은 계속 서버가 소유한다.
- provider 장애 시에도 검증된 최소 후보를 표시할 수 있으나 정상 Agentic 추천으로
  위장하지 않고 `PARTIAL`을 명시한다.
- `HOME_AI_AGENTIC_ORCHESTRATION_ENABLED`와
  `HOME_AI_OFFICIAL_WEB_SEARCH_ENABLED`를 독립 rollback flag로 사용한다.
- 기존 map/search/detail/trade URL, `complex_id` 의미, raw-first/dedupe 경로는 영향을 받지 않는다.

## Superseded Policy

ADR 0001의 근거 검증·browser-only memory 결정은 유지한다. 기존 문서의 “LLM은 추천
후보·순서를 변경하지 않는다”는 문장만 신규 Agentic v2 경로에서 부분 대체한다.
`recommendationTable/v1`, `recommendationCards/v1`, v1 archive 재생과 maintenance
fallback에는 기존 결정형 정책이 계속 적용된다.

## Rollback

두 rollout flag를 `false`로 바꾸면 migration rollback 없이 기존 v1/결정형 경로로
복귀한다. migration은 additive view와 SELECT grant이므로 삭제하지 않는다.

# ADR 0003: 최근 완료 배치 기준 rolling 7일 거래 인사이트

- 상태: Accepted
- 일자: 2026-07-23
- 대체 결정: ADR 0002의 공개 calendar-week insight 의미
- 부분 대체: ADR 0004의 등록일 결손 fallback 결정

## Context

RTMS는 거래월 단위로 조회되지만 사용자는 월요일부터 일요일까지의 과거
주간보다 최신 정상 수집에서 확인된 거래를 원한다. 벽시계만으로 기간을
앞당기면 실패하거나 진행 중인 배치를 최신 데이터처럼 보이게 하고,
과거 일곱 개 DAILY 실행을 요구하면 현재 등록일 기준 snapshot 발행을
불필요하게 막는다.

## Decision

기존 공개 URL `GET /api/v1/insights/trades/weekly`를 유지하되, 응답 의미를
최신 완료 `DAILY/NATIONWIDE` 실행의 `runDate-6..runDate`로 변경한다.
`weekStart`는 제거하며 전달 시 `400` ProblemDetail을 반환한다.

발행은 정확히 같은 `runDate`의 가장 최신 실행 하나를 사용한다. 모든 계획
work unit이 `COMPLETED`이고 `partial=0`, `failed=0`일 때만 전국과 17개
시도를 한 transaction에서 발행한다. 같은 실행은 idempotent하며 같은 날짜의
더 최신 정상 실행은 기존 18개를 `SUPERSEDED`하고 새 18개를 원자적으로
`PUBLISHED`한다.

후보 날짜는 raw payload 검색이 아니라 V19의 구조화된 `rgstDate`와
`cdealDay` 근거를 사용한다. `yy.MM.dd`를 2000년 기준으로 엄격 파싱하되
결손/오류는 raw-first 저장이나 정상 거래 적재를 막지 않는다. 날짜가 없는
source identity는 관련 section에서만 제외하고 DB/API `quality`에 남긴다.

## Consequences

- 새로운 날짜의 정상 배치 전까지 직전 snapshot은 `FRESH`를 유지한다.
- 더 최신 실행이 진행·실패했거나 아직 materialize되지 않았으면 직전
  snapshot은 `STALE`이다.
- 한 번도 rolling 발행이 없을 때만 `UNAVAILABLE`이다.
- 기존 V18, `WEEKLY`, `REJECTED` 및 이미 발행된 item은 수정하거나
  삭제하지 않는다.
- DAILY lookback은 현재 월과 이전 두 거래월로 확대하며, 허용 가능한
  provider quota를 넘으면 조용히 축소하지 않고 rollout을 중단한다.
- Web은 polling하지 않고 인사이트 재진입 및 탭 복귀 시 scope cache의
  5분 TTL 또는 KST 날짜 변경을 확인해 갱신한다.

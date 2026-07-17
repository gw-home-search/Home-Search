# Slice 4 근거 답변 kernel 준비 보고서

기준일: 2026-07-17
판정: `Partial` — `complex_identity`, `recent_trade_lookup`, `price_trend`의
`ai_read` 조회, observation, 구조화 LLM port, fact/citation·수치 검증 kernel은
구현했다. 운영 primary/secondary LLM provider가 아직 선택되지 않아 Capability는
활성화하지 않고 fail-closed 상태를 유지한다.

## 데이터와 적용 범위

- 출처: `home_search.ai_read.complex_fact`, `home_search.ai_read.trade_fact`
- 근거 등급: `A`
- 최신 거래일: 2026-07-16
- 단지 fact: 44,200행
- 거래 fact: 7,543,829행
- DB 경계: `home_search_ai_reader`, `SELECT` only
- 기존 property-data 공개 URL·응답 변경: 없음
- 대화·질문·답변 서버 저장: 없음

## 구현 결과

- 단지명과 optional 지역 조건으로 최대 6개 후보를 조회한다.
- `%`, `_`, `\`는 SQL wildcard가 아닌 검색 문자로 escape한다.
- 동명 단지가 둘 이상이면 임의 선택하지 않고 후보 fact와 추가 조건 필요 limitation을
  LLM에 전달한다.
- 최근 실거래는 기간 양끝을 포함하고 최신 거래일·trade id 순으로 최대 10건을
  조회한다.
- 전용면적 조건은 요청값 ±1.0㎡로 고정하며 다른 평형을 같은 조건으로 합치지 않는다.
- 가격 추이는 같은 단지·기간·면적 조건으로 월별 평균·최소·최대·거래량을 계산한다.
- 최신 기준일 조회는 현재 연도 partition부터 역순으로 확인하고 5분간 process-local
  cache하여 전국 trade 전체 scan을 피한다.
- LLM draft의 모든 지원·부분지원 문장은 존재하는 `factId`와 하나 이상의 구조화 claim을
  반환해야 한다. claim의 값과 단위가 observation과 다르거나 문장에 observation 밖
  숫자가 있으면 응답을 차단한다.
- primary model을 한 번 재시도한 뒤 secondary model로 전환하며 모두 실패하면
  `CHATBOT_PROVIDER_UNAVAILABLE`로 종료한다.

## 실제 local DB 대조

`SET ROLE home_search_ai_reader`로 확인한 잠실엘스 대표 결과다.

| 검사 | 결과 |
|---|---|
| 식별 | `complex_id=11471`, `잠실동 잠실엘스`, marker-safe |
| 전용 84.8㎡ 최근 거래 | 2026-06-24, 330,000만원 등 최신순 5건 확인 |
| 2026-01 월평균/거래량 | 339,571만원 / 7건 |
| 2026-06 월평균/거래량 | 335,000만원 / 4건 |
| dataset 최신 거래일 | 2026-07-16 |
| `public.trade` 직접 조회 | permission denied |

## TDD 근거

- 최초 RED: grounded kernel module 부재, PostgreSQL repository 부재, fallback LLM port
  부재를 각각 독립 test로 확인했다.
- 최소 GREEN: provider-agnostic plan/draft port, `ai_read` repository, observation과
  검증 response 조립만 추가했다.
- 경계 RED: 존재하지 않는 fact, claim 없는 근거 문장, 999,999만원 수치 생성,
  동명 단지 임의 선택, 잘못된 기간·면적·limit, literal wildcard, 빈 결과를
  차단하거나 limitation으로 전환했다.
- 회귀 RED: DB 설정이 body validation보다 먼저 실패해 invalid request가 `400` 대신
  `503`이 되던 문제를 lazy engine으로 수정했다.
- 성능 RED: `max(deal_date)` 전국 scan이 약 6.2초 걸려 5초 timeout을 초과했다.
  연도 partition pruning 조회는 실제 local DB에서 5초 이내에 최신일을 반환했다.

## 활성화 가능 질문과 검증 공백

| Capability | kernel/data | 운영 상태 | 잔여 조건 |
|---|---|---|---|
| `complex_identity` | Pass | 데이터 준비 중 | primary/secondary LLM adapter |
| `recent_trade_lookup` | Pass | 데이터 준비 중 | primary/secondary LLM adapter와 골든 답변 |
| `price_trend` | Pass | 데이터 준비 중 | primary/secondary LLM adapter와 골든 답변 |

운영 LLM adapter가 없을 때 template이나 정형 observation을 최종 답변으로 노출하지
않는다. provider·model·credential·quota 계약을 승인한 뒤 실제 model 골든 질문과
fallback 검증을 통과해야 Registry 상태를 `지원`으로 변경할 수 있다.

## 잔여 위험

- LLM provider 선택과 live structured-output 호환성은 미검증이다.
- 전용면적 ±1.0㎡ 기준은 응답 limitation과 UI 표시에서 사용자에게 명확히 전달해야 한다.
- 좌표 없는 586개 단지는 위치 좌표를 제공하지 않는다.
- 신고 취소·지연 신고와 Slice 3의 격리 데이터 limitation은 계속 표시해야 한다.

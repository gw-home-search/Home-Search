# Slice 5B 가격 추이 활성화 준비도 보고서

기준일: 2026-07-18

판정: `Pass` — 운영 `ai_read` 월별 집계, OpenAI live 대표 질문, 실제 signed JWT
JSON/SSE를 같은 fact/citation 집합으로 검증하고 `price_trend`를 활성화했다.

## 범위와 데이터

- Capability: `price_trend`
- 데이터: 기존 `home_search.ai_read` read-only view
- 대표 조건: 잠실엘스, 전용 84㎡, 2026-01-01..2026-06-30
- 관찰값: 월별 fact 6건, A등급 citation 1건, `dataAsOf=2026-07-16`
- 허용 주장: 월별 평균·최저·최고 거래금액과 거래량
- 금지 주장: 미래 가격·상승 보장·표본 밖 추정

## TDD 근거

- 최초 RED: triple Capability가 AI와 local runner에서 거부되고, trend fact에는
  서버 계산 한국어 금액 claim이 없으며 AI 전체 query budget도 없었다.
- 예상 RED 실패: runtime 활성화 불가, LLM 임의 단위 환산 가능성, BFF `55s`
  timeout 이후 AI가 늦게 완료되는 불일치였다.
- 최소 GREEN: exact cumulative allowlist, 평균·최저·최고 표시 claim, AI 전체
  query timeout을 추가했다. 사용자 운영값 `60s`를 허용하고 BFF를 `70s`로 정렬했다.
- live 분류 RED: 최초 live는 `CAPABILITY_MISMATCH`, prompt 보강 후에는 안전 진단상
  `PLAN_PROVIDER_RESPONSE_INVALID`였다. plan/draft 단계 진단을 비노출 reason code로
  분리하고 plan schema의 숫자 범위를 서버 검증과 일치시켜 GREEN으로 전환했다.
- Structured Outputs schema는 공식
  [지원 범위](https://developers.openai.com/api/docs/guides/structured-outputs#supported-schemas)의
  `pattern`, `minimum`, `maximum`, `exclusiveMinimum`만 사용한다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| 최초 RED | Pass — AI 3건 및 runner activation 실패를 의도대로 확인 |
| 최소 GREEN 집중 회귀 | Pass — AI 41건, runner preflight |
| 전체 AI gate | Pass — 193 tests, coverage 92.19% |
| 운영 DB offline golden | Pass — 4 cases, trend facts 6, citations 1 |
| OpenAI live trend case | Pass — supported, facts 6, citations 1, `dataAsOf=2026-07-16` |
| 실제 runtime 설정 | Pass — triple Capability, AI `60s`, BFF `70s` |
| signed JWT JSON | Pass — `200`, supported, facts 6, citations 1 |
| signed JWT SSE | Pass — answer_delta 결합=final, final 1, error 0 |
| 공개 chatbot 계약 | compatible — URL, field, unit, error shape 유지 |
| 기존 property API | 변경 없음 |

## 활성화와 롤백

활성값은
`complex_identity,recent_trade_lookup,price_trend`이다. 롤백은 기존 승인값인
`complex_identity,recent_trade_lookup` 또는 `complex_identity`로 낮춘 뒤 AI/BFF를
재기동한다. DB migration, 데이터 재수집, volume 변경은 없다.

## 검증 공백과 잔여 위험

- AI `60s`는 전체 coroutine 응답 경계다. 취소 시 이미 실행 중인 blocking provider
  thread가 즉시 중단된다고 보장하지 않으므로 provider 비용 관측은 운영 강화 항목이다.
- 대표 case 이외의 단지·기간은 동일한 서버 검증을 통과하지만 별도 live 골든으로
  전수 검증하지 않았다.
- 월별 값은 과거 관찰이며 미래 가격을 의미하지 않는다.

## 보안 영향

질문·답변·JWT·DSN·API key·provider body는 report와 기본 로그에 기록하지 않는다.
단계 진단은 `PLAN|DRAFT`와 고정 allowlist reason code만 결합한다. runtime은 전용
reader role, exact Capability allowlist, bounded provider/query/BFF timeout을 유지한다.
SSE는 완성된 grounding 검증 후에만 delta를 전송한다.

security-audit: 지적사항 = none

검증 범위: secret 비노출, JWT 이중 검증, read-only DB, provider 저장 비활성,
timeout·retry·Capability fail-closed, JSON/SSE completed-answer 경계를 확인했다.

code-review: 지적사항 = none

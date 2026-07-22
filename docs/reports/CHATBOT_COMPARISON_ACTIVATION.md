# 단지 비교 제한 활성화 보고서

기준일: 2026-07-21

판정: `Pass (제한 지원)` — 동일 면적·기간의 실거래, 세대수, 사용승인일, 철도와
사용자가 명시한 학교/학원 관찰값을 비교한다. 쇼핑은 좌표 readiness 미달로
`unavailable`을 유지한다.

## 범위와 정책

- 비교 단지: 2..4개, 서로 다른 단지로 정확히 식별
- 가격 기준: 동일 종료일, 최근 365일, 요청 전용면적 ±1.0㎡, 최근 3건
- 시설 거리: 준비된 source의 단지 표시 좌표 기준 직선거리
- 해석: available cell만 사용하고 overall winner를 만들지 않음
- unavailable: 0이나 열세로 처리하지 않고 이유를 표시
- 활성 property allowlist:
  `complex_identity,recent_trade_lookup,price_trend,recommendation,comparison`
- rollback: `complex_identity,recent_trade_lookup,price_trend,recommendation`

## TDD 근거

- 최초 RED: comparison 누적 allowlist가 거부됐다.
- repository RED: `송파구`가 동 단위 `region_name`과 직접 일치하지 않아 두 단지를
  식별하지 못했다. exact unique 행정구역의 descendant `region_code`를 batch query에
  적용해 GREEN으로 전환했다.
- grounding RED: 근거에 없는 비교 대상 수가 숫자 claim으로 거부됐다. 근거 없는
  개수·목록 번호를 금지하고 fallback을 최대 6문장·fact당 한 claim으로 제한했다.
- live 진단: 단일 질문 planner의 이름 순서는 의미가 없으므로 정확한 두 이름 집합으로
  재검증한다. 질문에 없는 이름·지역·면적은 계속 거부한다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| comparison handler | Pass — batch complex 1회, trade 1회, source별 batch 1회 |
| 상위 행정구역 resolver | Pass — Testcontainers RED→GREEN |
| OpenAI live 대표 질문 | Pass — fact 6건, citation 4건, 기준일 `2026-06-12` |
| 쇼핑 unavailable | Pass — 두 cell 모두 unavailable, 우열 해석 제외 |
| local runtime | Pass — exact 누적값과 secret 비노출 |
| 전체 AI gate | Pass — `905 passed`, coverage `90.10%` |
| signed JWT JSON/SSE | Pass |
| 공개 chatbot 계약 | `compatible/additive` |
| property-data 공개 API | 변경 없음 |

## 쇼핑 blocker

후속 상태: `CHATBOT_RETAIL_BUDGET_ACTIVATION.md`에서 exact PNU 211건 보완과
`88.7931%` 제한 활성화를 승인했다. 아래 문단은 비교 최초 activation 당시 기록이다.

공식 대규모점포 원본 4,176건 중 좌표 제공은 3,497건으로 `83.7404%`다. 좌표가 없는
679건은 adapter 누락이 아니라 원본 X/Y 결측이며, 운영 중 483건도 포함한다. 임의
geocoding이나 좌표 없는 행의 제외로 95% 기준을 우회하지 않는다. 승인된 공식
주소→좌표 보완 source와 credential, license, exact-match 품질 검증 전까지
`retail_location`과 `SHOPPING`은 활성화하지 않는다.

## 계약 영향

`api-contract: compatible` — 기존 JSON/SSE URL, method, request/response field,
`result`, `answer`, error shape를 유지한다. 새 comparison 값은 기존 capability 및
artifact seam 안의 additive 응답이다.

## 보안 영향

property/reference DB는 분리된 read-only role을 유지한다. region resolver는 escaped
parameter와 exact unique root의 descendant만 사용한다. live runner는 승인 case 한 건,
최대 6회 요청, 30초 provider·60초 total query timeout으로 제한하고 secret과 provider
body를 출력하지 않는다.

security-audit: 지적사항 = none

code-review: 지적사항 = none

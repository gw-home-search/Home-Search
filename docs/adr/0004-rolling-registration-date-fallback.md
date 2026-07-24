# ADR 0004: 등록일 결손 거래의 계약일 fallback

- 상태: Accepted
- 일자: 2026-07-24
- 대체 결정: ADR 0003의 등록일 결손 거래 제외 정책

## Context

RTMS 거래 행은 계약일, 거래금액, source identity가 정상이어도
`rgstDate`가 공백일 수 있다. 이 거래를 모두 제외하면 실제 정상 거래가
최근 7일 인사이트에서 빠지고, 전체 수집 범위의 등록일 결손 수를 사용자에게
“순위 제외”로 안내해 최근 7일 후보 수처럼 오해하게 만든다.

등록일이 없는 3개월 수집 행을 모두 포함하면 rolling 7일 의미가 깨지므로
기간을 판단할 대체 날짜는 여전히 필요하다.

## Decision

등록일 기반 section은 유효한 `registration_date`를 우선 사용한다. 등록일이
없거나 형식 오류이고 취소되지 않은 거래는 canonical `trade.deal_date`를
기간 및 tie-breaker의 fallback으로 사용한다.

- fallback 계약일도 `periodStart..periodEnd` 안에 있어야 한다.
- 취소된 거래는 등록일 유무와 관계없이 등록일 기반 5개 section에서 제외한다.
- 취소 section은 계속 유효한 `cancellation_date`를 요구한다.
- 등록일 품질 건수는 현재 scope에서 실제 fallback으로 포함된 unique source
  identity만 집계한다.
- `excludedCount`는 취소일 누락·형식 오류로 취소 순위에서 제외된 건수만
  합산한다.

Web은 `등록일 우선 · 계약일 보완`을 기준으로 설명하고 fallback 행에
`등록일 미제공 · 계약일 기준`을 표시한다. 등록일 fallback 건수는 별도 품질
메시지로 강조하지 않는다.

## Consequences

- 등록일이 공백이어도 정상 거래는 계약일이 최근 7일이면 순위 후보가 된다.
- 등록일이 있는 거래의 기존 기간 의미와 정렬은 유지된다.
- 계약일 fallback은 오래된 수집월 거래를 최근 거래로 위장하지 않는다.
- 기존 snapshot과 item은 수정·삭제하지 않으며 다음 정상 DAILY 실행이 새
  rolling snapshot을 발행한다.

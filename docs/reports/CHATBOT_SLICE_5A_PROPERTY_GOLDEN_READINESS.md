# Slice 5A 부동산 골든 질문 준비도 보고서

기준일: 2026-07-18

판정: `Partial` — production read-only repository와 grounded answer kernel을
통과하는 offline 골든 검증기를 구현하고 격리 PostgreSQL fixture에서 검증했다.
운영 DB에서도 `home_search_ai_reader` 역할로 대상 단지·거래·추이를 직접
감사하고 reader DSN을 사용한 offline CLI 전체 4건을 통과했다. 실제 OpenAI live
1건은 최초 `PROVIDER_UNAVAILABLE`, 안전 진단 경계 적용 후 재실행에서는
`PROVIDER_RATE_LIMITED`로 차단됐지만 사용 한도 등록 후 `complex_identity`가
통과했다. 이어 `recent_trade_lookup`은 grounding 숫자 검증 실패를 안전 reason
code로 확인하고 evidence-aware schema, grounding 재시도, 서버 계산 한국어 금액
표시 claim을 적용한 뒤 live 대표 case를 통과했다. 누적 runtime allowlist와 signed
JWT JSON/SSE를 검증해 두 Capability를 `지원`으로 활성화했다. 전체 판정은 live
검증이 남은 `price_trend` 때문에 계속 `Partial`이다.

## 검증 범위

- `complex_identity`: 단지 식별 fact, A등급 citation, 좌표 limitation 검증
- `recent_trade_lookup`: 기간·면적·limit 적용, 거래 fact 전체, 최신 거래일 검증
- `price_trend`: 월별 fact 전체, 거래량·가격 관찰값, 미래 가격 limitation 검증
- 결과 없음: `unavailable`, 빈 fact/citation, 데이터 부족 limitation 검증
- 공통: Capability, readiness, fact ID 집합, citation metadata, `dataAsOf`,
  success/status, limitation을 독립 repository 관찰값과 대조

Offline replay는 질문의 의도나 답변 내용을 새로 생성하지 않는다. catalog에
고정한 plan과 observation claim만 사용하며, production repository와 engine의
DB 조회·fact 조립·grounding·citation 검증 경로는 그대로 실행한다.

## 실행 안전장치

- offline catalog 최대 12건, catalog 64KiB 제한과 strict field 검증
- live는 정확히 한 `--case-id`와 일회성 확인값
  `RUN_ONE_LIVE_GOLDEN_CASE`가 없으면 실행 거부
- 현재 primary 1회 재시도와 secondary fallback을 반영한 live 1건의 provider
  HTTP request upper bound는 6
- report와 오류에는 case ID, readiness, count, 기준일, reason code만 표시
- 질문·답변·DSN·API key·provider 원문·예외 상세는 출력하지 않음
- repository는 `home_search_ai_reader`와 `home_search`를 확인하고 read-only
  transaction 및 5초 statement timeout을 유지

## TDD 근거

- 최초 RED: `ai_service.property_chat.golden` 모듈이 없어 test collection이
  `ModuleNotFoundError`로 실패했다.
- 예상 RED 실패: production repository 관찰값과 응답 fact/citation을 독립 비교할
  실행 경계가 없었다.
- 최소 GREEN: 고정 catalog, deterministic replay model, 골든 runner, strict
  validator, 1건 live 실행 정책, 비밀 비노출 report만 추가했다.
- 회귀 보강: 누락 fact, readiness drift, 변조 citation, 잘못된 catalog,
  다건 live 실행, provider/예외 상세 비노출을 거부하는 계약 테스트를 추가했다.
- 운영 RED: 잘못된 reader password로 CLI를 실행했을 때 최종 reason code는
  정규화됐지만 `psycopg.pool`이 host와 role을 포함한 연결 오류를 반복 출력했다.
  최소 GREEN은 골든 CLI 실행 범위에서만 pool logger를 비활성화하고 종료 시 원래
  상태를 복원하는 것이다.
- 로컬 실행기 최초 RED: `.env`를 source하지 않으면서 필요한 값만 전달하고 live를
  대표 1건으로 고정하는 실행 경계가 없어 runner 계약 테스트 3건이 파일 부재로
  실패했다.
- 로컬 실행기 최소 GREEN: regular file·권한·전용 reader DSN·provider 설정을
  검증하고 offline credential 격리 및 live 확인값/고정 case를 강제하는 전용
  실행기를 추가했다. 권한 오류, 승인되지 않은 DSN, 중복 provider key와 secret
  비노출 회귀를 포함한 6건을 통과했다.
- provider 진단 최초 RED: OpenAI HTTP 실패가 모두 detail 없는
  `OpenAIResponsesError`로 합쳐지고 골든 CLI cause도 보존되지 않아 안전한 실패
  범주를 검증하는 7건이 실패했다.
- provider 진단 최소 GREEN: provider 실패 body는 읽지 않고 HTTP status를 고정
  allowlist reason code로만 보존한다. 공개 chatbot 오류 계약은 그대로 유지하고,
  로컬 골든 CLI에서만 인증·권한·모델·quota·server·request 실패를 구분한다.
- 자체 검토 RED: malformed provider JSON이 transport 실패로 잘못 분류되는 회귀가
  확인됐다. requester 호출과 response parsing 경계를 분리하고 allowlist 외 reason
  code 거부 테스트를 추가했다.
- Capability gate 최초 RED: 엔진이 활성 allowlist를 받지 않아 비활성 최근 거래·가격
  추이를 repository 조회 전에 차단할 수 없었고, 신규 query 테스트 2건이 생성자 인자
  부재로 실패했다.
- Capability gate 최소 GREEN: production composition이 exact
  `complex_identity`만 허용한다. 비활성 plan은 repository를 조회하지 않고 fact 없는
  limitation만 LLM에 전달하며 JSON과 SSE는 같은 `200/unavailable` 최종 의미를 갖는다.
- 보호 runner 최초 RED: AI vars에는 allowlist가 있었지만 runner가 Compose 환경으로
  전달하지 않아 fake runtime에서 `capabilities=missing`으로 실패했다. 최소 GREEN은
  exact-value preflight, Compose 주입, 누락·혼합값 거부와 비밀 비노출 검증이다.
- 통합 runtime RED: 기존 property `DB_*` 계약과 중복된 bootstrap 변수, 서로 다른
  `kid` mapping, URL-encode되지 않은 reader password, `/bin/sh`에서 실행한 Bash 전용
  healthcheck, AI retry budget보다 짧은 BFF `5s` timeout이 순서대로 기동·E2E를
  차단했다.
- 통합 runtime 최소 GREEN: 검증된 role alias와 active `kid` 파생 mapping, 고정 host의
  percent-encoded reader DSN, `bash -ec` healthcheck, bounded `55s` BFF timeout,
  base 서비스 health preflight와 `--no-deps` 기동을 적용했다.
- live case 선택 최초 RED: local runner가 `complex_identity` case를 고정 실행해
  recent-trade 대표 case를 안전하게 선택할 수 없었다. 최소 GREEN은 승인된 case ID
  하나와 일회성 확인값만 허용하고 결과 없음·미지정·다건 실행을 거부한다.
- grounding 재시도 최초 RED: provider JSON은 유효하지만 fact/claim/숫자 검증이
  실패한 draft를 primary/secondary 재시도 대상으로 처리하지 않았다. 최소 GREEN은
  draft를 서버 validator로 확인한 뒤 primary 2회, secondary 1회 상한 안에서만
  재시도한다.
- evidence-aware schema 최초 RED: strict schema가 supported 응답의 빈 `factIds`와
  `claims`를 허용했다. 최소 GREEN은 공식 지원 `minItems|maxItems|enum`으로 제공된
  fact ID만 선택하게 하고, facts가 없을 때는 임의 참조를 금지한다.
- 금액 표시 최초 RED: LLM이 `10_000_KRW`를 억원 표현으로 환산해 observation 밖
  숫자를 만들었다. 최소 GREEN은 원본 금액 claim을 보존하면서 서버가 계산한
  `KOREAN_KRW_DISPLAY` claim을 추가해 LLM이 표시값을 그대로 인용하게 한다.
- 누적 Capability 최초 RED: AI와 local runner가
  `complex_identity,recent_trade_lookup`을 거부했다. 최소 GREEN은 identity-only
  rollback과 승인된 누적 문자열만 허용하고 순서 변경·중복·price-trend 혼합을
  fail-closed한다.
- SSE 최초 RED: 성공 stream이 `final`만 보내고 `answer_delta`가 없어 delta 결합
  계약을 위반했다. 최소 GREEN은 검증된 answer를 128 Unicode code point 단위로
  분할하고 final을 한 번만 전송한다.
- 완료 review RED: supported 응답이 관찰된 거래 fact 일부를 생략해도 성공할 수
  있었다. 최소 GREEN은 모든 observation fact가 사용되지 않으면
  `GROUNDING_FACTS_OMITTED`로 차단하고 동일 retry 경계로 보낸다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| 집중 AI/grounding 회귀 | Pass — 관련 103 tests 및 최종 전체 gate |
| 로컬 실행기 계약 테스트 | Pass — 8 tests + shell preflight |
| `uv sync --frozen --group test` | Pass |
| `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` | Pass — 181 tests, coverage 91.96% |
| 잘못된 reader password CLI | Pass — pool detail 없이 stable reason code만 출력 |
| 전용 실행기의 실제 `.env` offline 실행 | Pass — 4 cases, supported 3, unavailable 1 |
| production OpenAI live 1건 | Fail — `complex-identity-jamsil-ells`, `PROVIDER_UNAVAILABLE`, 검증되지 않은 답변 비노출 |
| 안전 진단 적용 후 OpenAI live 재실행 | Fail — `complex-identity-jamsil-ells`, `PROVIDER_RATE_LIMITED`, 검증되지 않은 답변 비노출 |
| 사용 한도 등록 후 OpenAI live 재실행 | Pass — `complex-identity-jamsil-ells`, supported, facts 1, citations 1, `dataAsOf=2026-07-12` |
| recent-trade OpenAI live 대표 case | Pass — supported, facts 3, citations 1, `dataAsOf=2026-07-16`, request upper bound 6 |
| OpenAI 모델 공식 계약 | Pass — `gpt-5.6-luna`, `gpt-5.6-terra` 모두 Responses API Structured Outputs 지원 |
| 운영 `ai_read` 역할·데이터 직접 감사 | Pass — reader `SELECT` 2개 view, 단지 단일 식별, 최근 거래 3건, 월별 추이 6개월 |
| 운영 reader DSN 기반 offline CLI 전체 실행 | Pass — 4 cases, supported 3, unavailable 1 |
| `./gradlew chatBffQualityCheck --no-daemon --stacktrace` | Pass — `BUILD SUCCESSFUL` |
| `infra/chatbot/test-run-local-chatbot.sh` | Pass — 기존 DB 변수 호환, role 오용 거부, DSN encoding, Capability gate, 비밀 비노출 |
| base + chatbot Compose `config --quiet` | Pass — synthetic non-secret validation values |
| 실제 local runtime health | Pass — user-service, AI, BFF, gateway 기동; AI/BFF healthy |
| 실제 signed JWT identity JSON | Pass — `200`, supported, facts 1, citations 1, `dataAsOf=2026-07-12` |
| 실제 signed JWT identity SSE | Pass — `200`, final 1, error 0, JSON과 동일 fact/citation 의미 |
| 실제 signed JWT recent-trade JSON | Pass — `200`, supported, facts 3, citations 1, `dataAsOf=2026-07-16` |
| 실제 signed JWT recent-trade SSE | Pass — `200`, answer_delta 결합=final answer, final 1, error 0, facts 3 |
| 비활성 price-trend JSON | Partial — BFF `504`; AI는 timeout 뒤 `200` 완료, partial fact 비노출 |
| chatbot JSON/SSE 공개 계약 영향 | compatible — URL/field/error shape 유지, 기존 answer_delta 규칙 구현 |
| 기존 property public API URL·response 변경 | 없음 |

## 활성화 가능한 질문 유형

| Capability | 현재 판정 | 활성화 여부 |
|---|---|---|
| `complex_identity` | `Pass` | 활성 — exact runtime allowlist |
| `recent_trade_lookup` | `Pass` | 활성 — 누적 exact runtime allowlist |
| `price_trend` | `Partial` | 비활성 |

## 검증 공백과 잔여 위험

- 운영 DB에서 잠실엘스는 `complex_id=11471`로 단일 식별되며 marker-safe이고,
  최신 거래일은 `2026-07-16`이다. 대상 면적의 최근 거래 3건과 2026년 1~6월
  월별 추이를 전체 CLI의 fact/citation 검증까지 포함해 확인했다.
- 첫 live 검증은 승인된 대표 1건과 호출 상한 안에서 실행됐지만 provider 단계에서
  실패했다. 실행 당시 adapter가 세부 범주를 안전하게 보존하지 않아 인증·권한·모델
  접근·quota·request 거부 중 어느 원인인지 추가 호출 없이 확정할 수 없다.
- [OpenAI 최신 모델 가이드](https://developers.openai.com/api/docs/guides/latest-model.md)와
  공식 [Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)·
  [Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra) page에서 두
  모델 ID와 Structured Outputs 지원을 확인했으므로 모델 문자열 자체의 오타는
  제외했다.
- 새 allowlist 진단 경계로 원인을 확인하려면 비용이 발생할 수 있는 대표 live 1건을
  다시 실행했고 OpenAI HTTP `429` 범주인 `PROVIDER_RATE_LIMITED`를 확인했다.
  Provider 실패 body를 읽지 않는 정책상 순간 rate limit과 계정 quota/billing 제한은
  더 세분화하지 않는다. 사용 한도 등록 후 같은 대표 case를 다시 실행해 grounded
  fact/citation 검증까지 통과했다.
- 실제 live 검증은 `complex_identity`와 `recent_trade_lookup` 대표 case가 통과했다.
  가격 추이는 offline production DB 경로만 통과했고 live 안정성은 아직 확인하지 않았다.
- Runtime은 identity-only rollback 또는 `complex_identity,recent_trade_lookup` 누적
  값만 승인한다. 무인자 local runner는 누적 값을 기본 주입하고 사용자 지정 4인자
  실행은 exact allowlist 누락·순서 변경·오류를 fail-closed한다.
- 실제 local runtime에서 DB password 예약문자를 percent-encode한 고정 reader DSN,
  user active `kid` 기반 public-key mapping, BFF `55s` bounded timeout을 적용해 signed
  JWT JSON/SSE와 비활성 Capability 차단까지 확인했다.
- 비활성 `price_trend` 질문 한 건은 primary retry 경로가 `55s`를 넘어 BFF
  `504 CHATBOT_TIMEOUT`으로 종료됐고 AI는 이후 `200`을 완료했다. 공개 오류 계약과
  partial fact 비노출은 지켜졌지만, worst-case provider retry budget과 BFF timeout의
  정렬은 다음 운영 강화 slice의 검증 공백이다.
- catalog는 운영 데이터 변경에 따라 readiness가 달라질 수 있다. 이 경우 기대값을
  자동 완화하지 않고 데이터 준비도 또는 catalog 기준을 재검토해야 한다.

## 보안 영향

보안 영향: CLI와 전용 로컬 실행기는 DB·provider credential을 출력하지 않는다.
전용 실행기는 `.env`를 source하지 않고 필요한 exact key만 읽으며 symlink,
group/other 권한, 중복·빈 값, 승인되지 않은 reader DSN을 거부한다.
운영 DB 연결은 `home_search_ai_reader`, database name, read-only transaction,
statement timeout을 강제한다. live 실행은 한 case와 일회성 확인값으로 제한하며,
질문·답변·provider 원문·예외 상세는 report에 포함하지 않는다. 문서 예시는 secret을
명령행에 직접 적지 않고 보호된 runtime injection으로 제공하도록 수정했다.
Provider HTTP 실패 body는 읽지 않으며 로컬 CLI에 출력 가능한 reason code는 코드의
고정 allowlist로 제한한다.
Runtime Capability 설정도 고정 allowlist와 strict parser를 통과해야 하며, 환경값만으로
live 검증을 받지 않은 Capability를 활성화할 수 없다.
무인자 runner는 secret을 출력하지 않고 DB role을 검증한 뒤 reader DSN의 password
부분만 percent-encode하며, `--no-deps`로 기존 Postgres·Redis·property API를 재생성하지
않는다. evidence-aware schema의 enum은 서버 생성 fact ID만 포함하고 provider 입력과
reason code 출력에는 credential, 질문, 답변 원문을 포함하지 않는다. SSE는 완성된
검증 answer 이후에만 delta를 생성한다.

security-audit: 지적사항 = none

검증 범위: catalog/case allowlist, DB 권한/timeout, live 호출 상한, 동적 schema,
grounding retry와 fact 완전성, 질문·답변·DSN·API key·provider 오류 비노출,
JWT/SSE completed-answer 경계, runtime Capability fail-closed를 확인했다.

code-review: 지적사항 = none

## 다음 승인 조건

1. `price_trend`는 대표 live case가 통과하기 전까지 `데이터 준비 중`과 비활성을
   유지한다.
2. 다음 활성화는 `price_trend`의 동일 조건 DB fact/citation과 JSON/SSE 검증을 별도
   승인한 뒤 진행한다.

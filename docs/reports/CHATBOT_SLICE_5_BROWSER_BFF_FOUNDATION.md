# Slice 5 BFF·브라우저 기반 준비 보고서

기준일: 2026-07-17
판정: `Partial` — BFF의 JWT 검증 후 subject 기준 Redis 요청 제한,
브라우저 IndexedDB 다중 대화, JSON 질문 UI와 근거 표시를 구현했다.
기본 stack과 분리된 opt-in AI/BFF/gateway overlay와 JWT·DB preflight를
구현했다. OpenAI Responses provider adapter의 로컬 계약은 구현했지만
runtime secret 주입과 live 호출은 아직 검증하지 않아 실제 답변 Capability는
활성화하지 않았다.

## 구현 결과

- `apps/chat-bff`
  - 인증을 통과한 positive numeric `sub`를 Redis key에 사용한다.
  - Lua `INCR`/`PEXPIRE`로 고정 window 카운터를 원자적으로 갱신한다.
  - 한도 초과는 `429 CHATBOT_RATE_LIMITED`, Redis 오류·응답 누락은
    `503 CHATBOT_RATE_LIMIT_UNAVAILABLE`로 fail-closed 처리하며 AI를 호출하지 않는다.
  - 오류 응답에 `Cache-Control: no-store`와 request ID를 유지한다.
- `apps/web`
  - 대화를 서버 DB가 아닌 browser IndexedDB `home-search-chat`에만 저장한다.
  - 다중 대화, 새 대화, 선택·전체 삭제, versioned JSON 내보내기·가져오기를
    지원한다.
  - import는 10MB, 100개 대화, 대화당 500개 message 한계와 shape 검증을
    통과해야 하며 citation URL은 credential 없는 HTTPS만 허용한다. invalid
    archive는 부분 저장하지 않는다.
  - 서버에는 최근 12개, message당 2,000자, 총 12,000자로 제한한
    `conversationContext`만 전송한다.
  - JWT는 기존 memory-only auth client에서 고정 chatbot URL로만 전달하고,
    대화 archive에 저장하지 않는다.
  - 답변의 `dataAsOf`, 출처명·근거 등급, citation·fact 수, `limitations`를
    대화와 함께 표시·저장한다.
- local runtime
  - `infra/docker-compose.chatbot.yml`을 명시했을 때만 AI, BFF, chatbot gateway
    route를 추가한다. 기본 Compose 파일은 변경하지 않았다.
  - AI image는 uv lock으로 build하고 non-root `home-ai`로 Uvicorn을 실행한다.
    BFF도 Compose에서 numeric non-root `10001:10001`로 실행한다.
  - BFF real-boot test에서 누락된 `WebClient.Builder` bean을 발견·수정했다.
  - runner는 env 파일을 source하지 않고 필요 key만 parsing한다. RSA pair,
    active `kid`, BFF/AI mapping, user runtime password, AI reader DSN, artifact를
    검증한 뒤 `config --quiet` 후에만 `up`을 실행한다.
  - gateway는 두 chatbot POST URL만 BFF로 전달하고, CORS preflight를
    처리하며 SSE buffering을 비활성화한다. 나머지 chatbot 경로는 404다.
- LLM provider adapter
  - 고정 `https://api.openai.com/v1/responses` endpoint와 strict JSON Schema를
    사용하며 provider 저장을 끄기 위해 모든 요청에 `store: false`를 보낸다.
  - plan은 500, draft는 1,600 output token으로 제한하고 HTTP timeout은
    기본 8초·허용 범위 `1..30`, 응답 body는 최대 256KiB로 제한한다.
  - 설정된 primary model을 1회 재시도한 뒤 secondary model을 호출하며 모두
    실패하면 기존 `503 CHATBOT_PROVIDER_UNAVAILABLE`로 정규화한다.
  - refusal, incomplete, malformed JSON, 과대 응답, local schema 위반은
    provider 원문이나 API key를 노출하지 않고 거부한다.
  - model 출력은 최종 답변이 아니며 기존 fact ID·claim·수치·citation 검증을
    통과해야만 사용자에게 반환된다.
  - 공식 계약 근거는 OpenAI의
    [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)와
    [Responses API migration](https://developers.openai.com/api/docs/guides/migrate-to-responses)
    문서를 기준으로 했다.

## TDD 근거

- 최초 RED: rate limiter class/package 부재, IndexedDB store 부재, public chatbot
  auth target 부재, chatbot response adapter·panel 부재를 각각 독립 test로 확인했다.
- 최소 GREEN: subject counter, fail-closed filter, browser store, allowlist auth request,
  JSON adapter, map-first launcher/panel만 추가했다.
- 경계 RED: 429·503에서 AI 미호출, invalid archive의 원자적 거부,
  replace import, context 길이, 임의 public URL JWT 전달 거부, 새로고침 후
  대화·근거 복원을 검증했다.
- runtime RED: chatbot nginx template 부재, local runner 부재, user/AI DB password
  불일치, 실제 BFF context의 `WebClient.Builder` 누락을 각각 실패로 확인했다.
- signed JWT E2E RED: 실제 provider가 없는 production AI engine으로 요청했을 때
  기대한 `200` 대신 DB 연결 대기 후 `504`가 발생했다. 최소 GREEN은 production
  인증을 유지하고 test-only engine만 read-only mount하여 전송 경계를 격리했다.
- provider adapter RED: `openai_responses` production module이 없어 planning·draft
  contract test가 collection 단계에서 실패했다. 최소 GREEN은 표준 라이브러리
  HTTP adapter, strict parser, bounded request/response, 환경설정 조립만 추가했다.
- provider 경계 RED: incomplete/refusal/malformed/과대 응답, 의미적으로 잘못된
  plan·draft, 누락·부분·잘못된 timeout 설정이 모두 fail-closed하는지 검증했다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| `./gradlew chatBffQualityCheck --no-daemon --stacktrace` | Pass |
| `npm run test` | Pass — 46 files, 227 tests |
| `npm run lint` | Pass — error 0, 기존 warning 8 |
| `npm run build` | Pass |
| `npm audit` | Pass — vulnerability 0 |
| `infra/nginx/test-chatbot-public.sh` | Pass — property 회귀, JSON/SSE, CORS, 차단 경로 |
| `infra/nginx/test-property-public.sh` | Pass |
| `infra/chatbot/test-run-local-chatbot.sh` | Pass — preflight·OpenAI key 비노출·model/timeout 검증 |
| base + chatbot Compose `config --quiet` | Pass |
| AI image build·container `/health` | Pass |
| Redis + AI + BFF container smoke | Pass — non-root, health UP, 무인증 401 |
| `infra/chatbot/test-signed-jwt-e2e.sh` | Pass — JSON/SSE, 이중 JWT 검증, 잘못된 issuer 401, property 회귀 |
| production local runtime signed JWT E2E | Pass — `complex_identity` JSON/SSE supported, 비활성 최근 거래 unavailable |
| `cd apps/ai && uv run pytest` | Pass — 103 tests, coverage 91.30% |
| OpenAI provider fake-transport contract | Pass — `store: false`, strict schema, token/byte/timeout limit, refusal·오류 비노출 |
| 기존 property-data public URL·response 변경 | 없음 |
| 대화·질문·답변 서버 저장 | 없음 |

## 보안 영향

보안 영향: JWT는 memory-only로 유지하고 allowlist된 chatbot URL에만
전달한다. 질문·답변은 서버 로그·DB에 저장하지 않으며, imported
archive는 크기·shape·HTTPS citation URL을 검증한다. Redis 장애 시
비용 보호를 위해 fail-closed한다. Runtime runner는 변수 파일을
source하지 않고 값을 출력하지 않으며, BFF와 AI에는 user public key만
mount한다. OpenAI API key는 AI container에만 주입하고 primary/secondary model과
`1..30`초 timeout을 기동 전에 검증한다. AI와 BFF runtime은 모두 non-root로 고정한다.
Signed JWT E2E는 실행마다 임시 RSA key와 5분 token을 생성하고 token은 권한
`600` curl config에만 기록하며, 출력하지 않고 종료 시 `unlink`한다.
OpenAI 요청은 `store: false`로 provider-side 저장을 끄고
`previous_response_id`를 사용하지 않는다. endpoint와 TLS host/path를 고정하고
HTTP redirect를 거부하며 API key, provider 응답 원문, 질문 원문을 예외
메시지에 넣지 않는다.

security-audit: 지적사항 = none

검증 범위: 임시 RSA/JWT 수명·권한·출력, loopback port, public-key-only mount,
JWT issuer 이중 검증, gateway allowlist, container/network cleanup과 OpenAI
고정 TLS host/path, redirect 거부, key·원문 비노출, 저장·token·byte·timeout 제한을
확인했다.

code-review: 지적사항 = none

## 검증 공백과 잔여 위험

- 실제 signed user JWT gateway → BFF → production AI E2E에서
  `complex_identity` JSON/SSE의 fact 1건·citation 1건과 비활성 최근 거래의 fact 0건을
  확인했다. primary/secondary 전체 실패 fallback 경로의 실제 provider 재현은 비용과
  장애 유발 위험 때문에 fake transport 검증으로 유지한다.
- UI는 자동화된 DOM 계약을 검증했지만 실제 브라우저 screenshot 기반
  시각 QA는 아직 실행하지 않았다.
- Redis 고정 window는 최초 요청 시점을 기준으로 하며 sliding window가 아니다.
- overlay를 기동해도 provider 없이 보낸 실질 질문은 계약대로 `503`이다.
- local Compose에 주입한 DB DSN은 Docker daemon 관리자에게 container metadata로
  조회될 수 있다. 운영 배포에서는 orchestrator secret injection으로 교체해야 한다.
- `shellcheck`가 로컬에 설치되어 있지 않아 실행하지 못했다. `bash -n`과 runner/nginx
  실행 검증은 통과했다.

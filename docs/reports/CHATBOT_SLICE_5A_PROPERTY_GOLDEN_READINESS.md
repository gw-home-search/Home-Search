# Slice 5A 부동산 골든 질문 준비도 보고서

기준일: 2026-07-18

판정: `Partial` — production read-only repository와 grounded answer kernel을
통과하는 offline 골든 검증기를 구현하고 격리 PostgreSQL fixture에서 검증했다.
운영 DB에서도 `home_search_ai_reader` 역할로 대상 단지·거래·추이를 직접
감사하고 reader DSN을 사용한 offline CLI 전체 4건을 통과했다. 실제 OpenAI live
1건은 실행하지 않았으므로 부동산 Capability를 아직 `지원`으로 활성화하지 않는다.

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

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| 집중 골든 테스트 | Pass — 41 tests |
| 로컬 실행기 계약 테스트 | Pass — 6 tests |
| `uv sync --frozen --group test` | Pass |
| `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` | Pass — 150 tests, coverage 91.95% |
| 잘못된 reader password CLI | Pass — pool detail 없이 stable reason code만 출력 |
| production OpenAI network request | not run |
| 전용 실행기의 실제 `.env` offline 실행 | not run — 파일 권한 가드가 `chmod 600` 필요 상태로 거부 |
| 운영 `ai_read` 역할·데이터 직접 감사 | Pass — reader `SELECT` 2개 view, 단지 단일 식별, 최근 거래 3건, 월별 추이 6개월 |
| 운영 reader DSN 기반 offline CLI 전체 실행 | Pass — 4 cases, supported 3, unavailable 1 |
| 기존 property public API URL·response 변경 | 없음 |

## 활성화 가능한 질문 유형

| Capability | 현재 판정 | 활성화 여부 |
|---|---|---|
| `complex_identity` | `Partial` | 비활성 |
| `recent_trade_lookup` | `Partial` | 비활성 |
| `price_trend` | `Partial` | 비활성 |

## 검증 공백과 잔여 위험

- 운영 DB에서 잠실엘스는 `complex_id=11471`로 단일 식별되며 marker-safe이고,
  최신 거래일은 `2026-07-16`이다. 대상 면적의 최근 거래 3건과 2026년 1~6월
  월별 추이를 전체 CLI의 fact/citation 검증까지 포함해 확인했다.
- live model이 세 Capability의 plan과 모든 observed fact를 안정적으로 반환하는지
  확인하지 않았다. 첫 live 검증은 비용 경계를 확인하기 위해 1건만 실행해야 한다.
- 저장소 hook 정책상 agent가 `.env` 권한을 직접 변경하지 않았다. 사용자가
  `chmod 600 apps/ai/.env`를 적용한 뒤 전용 실행기로 offline과 승인된 live 1건을
  순서대로 실행해야 한다.
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

security-audit: 지적사항 = none

검증 범위: catalog 입력 경계, DB 권한/timeout, live 호출 상한, 질문·답변·DSN·API
key·provider 오류 비노출, package 포함 파일과 public API 무변경을 확인했다.

code-review: 지적사항 = none

## 다음 승인 조건

1. 승인된 대표 live case 1건은 `.env` 권한을 `600`으로 제한하고 offline 재검증을
   통과한 직후 실행한다.
2. 계약 회귀, `code-review`, `security-audit` 결과와 함께 Capability 활성화를
   별도 승인한다.

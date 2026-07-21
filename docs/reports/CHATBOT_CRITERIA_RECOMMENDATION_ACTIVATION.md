# 조건 기반 추천 제한 활성화 보고서

기준일: 2026-07-21

판정: `Pass (제한 지원)` — 최초 활성화에서는 `recommendation`의 `CRITERIA` mode 중
사용자가 명시한 `MIN_UNIT_COUNT`와 시설 조건 `ACADEMY`·`TRANSIT`를 활성화했다.
2026-07-21 학교 activation에서 `SCHOOL`을 같은 제한 mode에 추가했다. 가격 기반
추천, 쇼핑, 어린이집, 유치원, 비교는 아직 포함하지 않았다.

## 범위와 정책

- 대표 질문: 영등포구 500세대 이상, 학원 접근성 우선, 교통 다음 후보
- property allowlist:
  `complex_identity,recent_trade_lookup,price_trend,recommendation`
- reference allowlist: `academy_lookup,rail_station_lookup`
- runtime 추천 mode: `CRITERIA`만 허용
- 최소 실행 조건: `MIN_UNIT_COUNT` 또는 활성 시설 조건 1개 이상
- 정렬: 사용자 명시 우선순위의 lexicographic order, 동점은 `complexId` 오름차순
- 후보: 지역 scope 최대 100건, 최종 최대 5건, source별 batch query
- LLM 역할: typed plan과 근거 문장 작성. 후보·값·순위·표는 서버가 결정

`BUDGET` 요청은 runtime mode gate에서 관찰 쿼리 전에 unavailable로 종료한다.
`SHOPPING`은 활성 repository가 없어 실행하지 않으며, 어린이집·유치원 문구는 서버가
현재 질문에서 재확인해 `UNSUPPORTED_CHILDCARE` 안내로 종료한다. `SCHOOL`은 학교
activation 이후 사용자가 명시한 조건에서만 공식 학교 위치 snapshot을 사용한다.

## 데이터 준비도

| Source | 판정 | 활성화 범위 |
|---|---|---|
| Sbiz 교육업소 | Pass | 800m count·최근접 직선거리, B등급 |
| 철도역 위치 | Pass | 최근접 역 1,500m 직선거리, A등급 |
| 대규모점포 | Partial | 좌표 `3,497/4,176` (`83.7404%`)로 95% 기준 미달; 비활성 |
| 학교 | Pass | 전국 12,011행·좌표 100%·live golden; explicit `SCHOOL` 활성 |
| 어린이집 | 준비 코드만 유지 | collector·projection·typed handler·fixture 보존; 승인 key와 전국 coverage 전 비활성 |
| 유치원 | 연결 경계만 유지 | `DAYCARE_KINDERGARTEN` 지도 action은 Kakao 탐색 전용; 공식 source 승인·adapter·readiness 전 추천 근거로 사용하지 않음 |

## TDD 근거

- 최초 RED: 추천이 포함된 누적 property allowlist가 거부되고 local runtime template은
  이전 가격·추이 값만 사용했다.
- 예상 RED 실패: `recommendation`을 단순 활성화하면 `BUDGET` mode까지 handler에
  진입하고, 어린이집을 누적 allowlist에 추가해도 local runner가 구분하지 못할 수 있었다.
- 최소 GREEN: exact 누적 allowlist 한 개를 추가하고 runtime 추천 mode를 정적
  `CRITERIA`로 제한했다. 어린이집이 섞인 설정은 AI composition과 local runner에서
  fail-closed한다.
- live harness RED: 실제 planner의 단일 질문 응답이 `QueryPlanBundle`인데 harness가
  `QueryPlan`만 허용해 실패했다. 정확히 한 fragment만 해제하도록 수정한 뒤 통과했다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| 집중 회귀 | Pass — recommendation·composition·activation·학원명 정책 111건 |
| 전체 AI gate | Pass — 848 tests, coverage 90.02% |
| Python 별도 lint | not run — 현재 uv 환경에 `ruff` 실행 파일 없음; pytest import·실행으로 구문 회귀 확인 |
| OpenAI live 대표 질문 | Pass — `CRITERIA`, `ACADEMY→TRANSIT`, fact 4건 |
| local runtime preflight | Pass — 승인 누적값 허용, childcare 혼합값 거부, 비밀값 비노출 |
| reference 문서 결정성 | Pass |
| signed JWT JSON/SSE transport 회귀 | Pass — 실제 서명, 잘못된 issuer 401, 기존 property route |
| 공개 chatbot 계약 | `compatible/additive` — URL·method·request·`result`·`answer`·JSON/SSE 의미 유지 |
| property-data 공개 API | 변경 없음 |

signed JWT 검사는 transport·인증·기존 route 회귀이며 추천 observation을 대신하지 않는다.
추천 observation은 deterministic handler·repository·artifact tests로, 실제 모델 경계는
별도 승인 live case로 분리해 검증했다.

## 어린이집·유치원 후속 활성화 조건

어린이집은 기존 collector, raw-first lifecycle, projection, repository, typed handler,
grounding policy, fixture를 삭제하지 않는다. 승인 key로 실제 schema를 확인하고 전국·지역별
좌표 coverage, freshness, live golden, signed JWT JSON/SSE를 통과한 뒤 reference allowlist와
추천 조건을 별도 activation commit으로 추가한다.

유치원은 어린이집 API 결과로 간주하지 않는다. 승인된 공식 유치원 source의 계약과
운영상태·좌표 의미를 먼저 정의하고 source-specific adapter/read view를 추가해야 한다.
현재 `DAYCARE_KINDERGARTEN` action은 사용자가 누를 때 Kakao에서 탐색하는 기능으로만
남으며 공식 현황·추천 점수의 근거가 아니다.

## 활성화와 롤백

활성값은
`complex_identity,recent_trade_lookup,price_trend,recommendation`이다. 문제 발생 시
`complex_identity,recent_trade_lookup,price_trend`로 되돌리고 AI/BFF를 재기동한다.
reference allowlist는 학교 활성화 후 누적
`academy_lookup,rail_station_lookup,school_location`을 사용한다. 학교 문제만 발생하면
직전 `academy_lookup,rail_station_lookup`으로 되돌린다. migration, 데이터 재수집,
Docker volume 변경은 없다.

## 검증 공백과 잔여 위험

- 실제 live 모델 검증은 sanitized 서버 사실을 사용해 planner·grounding 경계를 확인했다.
  특정 지역의 실제 후보 수치는 실행 시점 active snapshot에 따라 달라진다.
- 최종 코드 검증 중 live case가 고정 비노출 오류로 1회 종료된 뒤 재실행에서 통과했다.
  이후에는 provider 본문 없이 `PLAN_STAGE`·`PLAN_POLICY`·`DRAFT_STAGE`·
  `DRAFT_GROUNDING` 단계만 식별하도록 보강했다. provider 변동과 일시 오류는 잔여 위험이다.
- 대규모점포 좌표 coverage가 기준에 미달하므로 가격 기반 추천과 쇼핑 조건을 활성화하면
  안 된다.
- 어린이집·유치원은 source별 승인과 live readiness 없이는 사용할 수 없다.

## 보안 영향

live runner는 승인된 case ID 한 건만 실행하고 provider 설정을 출력하지 않는다. 새
runtime 설정은 exact allowlist이며 어린이집 혼합, 순서 변경, 중복, 공백 포함 값을
fail-closed한다. 추천 mode gate는 비활성 mode의 DB 관찰 전에 종료한다. SQL, DB role,
source credential, 저장 schema는 변경하지 않았다.

security-audit: 지적사항 = none

검증 범위: secret 비노출, bounded live 호출, read-only repository 조립, exact
Capability/reference allowlist, mode fail-closed, 기존 JWT·JSON/SSE 경계를 확인했다.

code-review: 지적사항 = none

# 학교 위치 활성화 보고서

기준일: 2026-07-21

판정: `Pass` — `school_location`과 explicit `CRITERIA` 추천의 `SCHOOL` 조건을
공식 전국 학교 위치 snapshot으로 활성화했다. 어린이집·유치원은 포함하지 않았다.

## 범위와 근거

- source: `edu.school-location` / 전국초중등학교위치표준데이터
- active dataset: `2026-03-20-b148752f1e38`
- 품질: 12,011행, rejected `0`, 17개 시도교육청, 좌표 오류 `0`
- 조회 의미: 운영 중 학교의 학교급·위치·직선거리
- 제외 의미: 통학구역, 학교군, 품질·서열, 진학 성과, 도보시간
- reference allowlist: `academy_lookup,rail_station_lookup,school_location`
- rollback: blank 또는 `academy_lookup,rail_station_lookup`

## TDD 근거

- 최초 RED: 학교를 포함한 누적 reference 설정이 composition에서 거부되고 live
  runner가 학교 activation case를 허용하지 않았다.
- 예상 RED 실패: incomplete structured response, citation source 누락, 잘못된
  `uiSummary`, 학교 artifact 누락이 activation을 통과할 수 있었다.
- 최소 GREEN: exact 누적 tuple, 실제 property/reference observation preflight,
  `school_location`·두 source citation·`uiSummary/v1`·artifact를 검사하는 bounded
  live harness를 추가했다.
- grounding RED: 첫 live draft가 scope fact를 생략했고, 다음 draft는 서로 다른
  fact의 value와 unit을 섞었다. prompt가 모든 supplied fact를 사용하고 claim의
  `factId`·`value`·`unit`을 동일한 supplied fact에서 복사하도록 제한한 뒤 통과했다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| actual active snapshot | Pass — `2026-03-20-b148752f1e38`, 12,011행 |
| 동일 원본 재수집 | Pass — acquisition/publication 재사용 |
| runtime query 성능 | Pass — 20회 p95 `114.066ms`, 기준 200ms 이하 |
| OpenAI live 대표 질문 | Pass — fact 4건, citation 2건, 기준일 `2026-03-20` |
| structured response | Pass — `uiSummary/v1`, 학교 fact artifact |
| 전체 AI gate | Pass — 874 tests, coverage 90.03% |
| reference docs·local runner | Pass — 결정성, exact allowlist, 비밀값 비노출 |
| signed JWT JSON/SSE | Pass — 실제 서명, 잘못된 issuer `401`, property 회귀 |
| public chatbot 계약 | `compatible/additive` |
| property-data 공개 API | 변경 없음 |

최종 전체 회귀와 transport gate 수치는 activation commit 직전 검증 결과를 따른다.

## 활성화와 롤백

승인된 reference 설정은 exact cumulative
`academy_lookup,rail_station_lookup,school_location`이다. 순서 변경, 중복, 학교
단독, 어린이집·유치원 혼합 설정은 fail-closed한다. 문제 발생 시 직전 누적값
`academy_lookup,rail_station_lookup`으로 되돌리고 AI/BFF를 재기동한다. migration,
재수집, Docker volume 변경은 필요하지 않다.

## 계약 영향

`api-contract: compatible` — 기존 JSON/SSE URL, method, request/response field,
`result`, `answer`, error shape와 상태 의미를 유지한다. `school_location`과 학교
citation은 기존 additive 구조 안에서만 생성된다.

## 검증 공백과 잔여 위험

- provider 응답 변동은 strict grounding에서 fail-closed할 수 있다.
- 반기 갱신 source가 grace 기간을 넘으면 학교 observation은 unavailable로 종료한다.
- 어린이집·유치원은 source 승인·전국 coverage·별도 live activation 전까지 실행하지
  않는다.

## 보안 영향

live runner는 승인 case 한 건과 최대 6회 provider 요청만 허용한다. property와
reference는 분리된 read-only role을 사용하며 DSN·API key·provider body를 출력하지
않는다. 임시 stderr 파일은 mode `600`으로 만들고 정상·오류·signal 종료 시 삭제한다.

security-audit: 지적사항 = none

검증 범위: secret 비노출, bounded live 호출, read-only repository, exact allowlist,
grounding fact 일치, 기존 JSON/SSE 경계를 확인했다.

code-review: 지적사항 = none

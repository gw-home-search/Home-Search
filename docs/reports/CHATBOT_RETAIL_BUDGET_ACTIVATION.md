# 대규모점포·가격 기반 추천 제한 활성화 보고서

기준일: 2026-07-22

기능 판정: `Limited Pass (제한 지원)`

운영 배포 전 live gate: `Fail`

## 범위

- `retail_location`: 단지 중심 지정 반경의 좌표 확인 대규모점포
- `comparison`: 기존 비교표의 최근접 대규모점포 행
- `recommendation/CRITERIA`: 사용자가 명시한 `SHOPPING` 조건
- `recommendation/BUDGET`: 지역·최대 예산·전용면적 선필터 후 기존
  `recommendation-policy-v1` 적용
- runtime reference allowlist:
  `academy_lookup,rail_station_lookup,school_location,retail_location`
- rollback reference allowlist:
  `academy_lookup,rail_station_lookup,school_location`

어린이집·유치원은 구현 seam과 fixture만 보존하고 handler repository, 추천 metric,
runtime allowlist에는 연결하지 않는다.

## 데이터 결정

공식 원장 `4,176`행 중 원본 좌표 `3,497`행과 exact lot/PNU로 보완한 `211`행을
합쳐 `3,708`행 (`88.7931%`)을 사용할 수 있다. 미확인 `468`행은 결과에서 빠질 수
있으므로 시설 부재를 단정하지 않는다.

이번 단계는 공식 주소 좌표 API를 요구하지 않는다. 보완 작업은 기존 법정동 경로와
Coordinate Source DB를 서로 다른 read-only 연결로 조회하며, 정확한 PNU 한 건이
확인될 때만 불변 evidence를 추가한다. 주소 API는 후속 coverage 개선 작업으로 남긴다.

## TDD 근거

- 최초 RED: 좌표 보완 module 부재, `SHOPPING`의 고정 95% gate,
  runtime retail 누적 allowlist 거부, batch query의 저장 category 불일치
- 예상 RED 실패: `88.79%` source가 unavailable로 종료되고 `RETAIL` 행이 있는데도
  batch 결과가 비어 있음
- 최소 GREEN: exact PNU 보완 evidence, retail 전용 `88%` gate, 실제 저장 category
  `RETAIL` 조회, 정적 누적 allowlist와 repository composition만 추가
- 회귀 정책: coverage 미달은 0점이나 열세로 바꾸지 않고 해당 capability를
  unavailable로 종료
- live 최초 RED: 송파구 후보 조회가 3초 statement timeout으로 종료됨
- 최소 GREEN: 행정구역을 먼저 exact/descendant code로 확정하고, 단지별 최근 거래
  3건을 한 번의 bounded SQL에서 조회하도록 변경. repository 통합·성능 테스트 통과

## 계약 영향

- `api-contract: compatible`
- property-data 공개 URL·method·request/response는 변경하지 않았다.
- chatbot의 기존 `result`, `answer`, JSON/SSE 상태 의미와 artifact schema를 유지했다.
- `HOME_AI_ENABLED_REFERENCE_CAPABILITIES`의 exact 누적값 하나만 추가했다.

## 보안 영향

- AI importer만 보완 evidence를 INSERT할 수 있고 runtime role은 read view만 조회한다.
- coordinate-source 연결은 read-only, 3초 timeout, 최대 1,000 PNU exact lookup이다.
- runner는 DSN과 password를 출력하지 않으며 Docker volume을 변경하거나 삭제하지 않는다.
- DB 간 직접 join, 외부 geocoding, fuzzy 주소 매칭은 없다.

security-audit: 지적사항 = none

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| 전체 AI gate | Pass — 948 tests, coverage 90.01% |
| 좌표 보완 runner | Pass — DB 경계 전달, secret 비노출, idempotent 재실행 |
| local chatbot preflight | Pass — exact 누적 allowlist, childcare 혼합 거부, secret 비노출 |
| reference 문서 결정성 | Pass |
| signed JWT JSON/SSE | Pass — 실제 서명, 잘못된 issuer 401, property 회귀 |
| service DB boundary | Pass — credential 분리, runtime Flyway 비활성 |
| change classifier·diff | Pass |
| OpenAI live 대표 질문 | Fail — observation 수정 후에도 답변 생성 단계가 `PROVIDER_TRANSPORT_FAILED` |

## 검증 공백과 잔여 위험

- 승인된 case `budget-recommendation-songpa-84-retail`을 실행했다. 최초 실행은 송파구
  후보 SQL timeout으로 실패했고, 쿼리 수정 뒤 데이터 관찰은 통과했다.
- 기본 5곳과 명시적 3곳 질문 모두 답변 생성 단계에서
  `BUDGET_RETAIL_DRAFT_PROVIDER_TRANSPORT_FAILED`로 종료됐다. runner는 prompt, 답변,
  provider body, secret을 출력하지 않았고 실패 응답도 사용자 답변으로 노출하지 않았다.
- 동일 오류가 축소된 근거 묶음에서도 재현되어 추가 유료 재시도는 중단했다. provider
  답변 생성 경계가 통과하기 전에는 이 기능을 운영 배포 승인으로 간주하지 않는다.
- 88%는 대규모점포 source에만 적용한 임시 최소선이다. 현재 88.7931%와의 여유가
  작으므로 새 active snapshot에서 기준 미달 시 자동 비활성화될 수 있다.
- 좌표 미확인 `468`행과 provider 행정코드 mapping 부재 때문에 정상 0건을 확정하지
  않는다.
- 후속 공식 주소 좌표 source 승인은 coverage 개선에는 유용하지만 현재 제한 활성화의
  필수 조건은 아니다.

code-review: 지적사항 = none

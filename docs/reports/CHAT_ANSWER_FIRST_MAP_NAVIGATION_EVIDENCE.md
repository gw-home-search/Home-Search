# 답변 우선·대표 후보·지도 이동 검증 보고서

기준일: 2026-08-01

판정: `Partial` — AI/BFF/Web과 repository/infra 자동 검증은 통과했다. 로컬
Property DB가 열려 있지 않아 offline golden과 `test:live-api`/시각 검증은 실행하지
못했으며, staging 7일 안정화 근거가 없으므로 release와 production 배포는 진행하지
않는다.

## 목표

- 동명 후보가 있어도 exact 조건 데이터가 있는 대표 `complexId` 하나로 답변한다.
- 후보 fact와 실거래·월별 추이 result fact를 분리해 후보 수를 관찰값으로 세지 않는다.
- `focusComplex/v1`으로 대표 자동 이동과 대안 A→B→A 반복 이동을 지원한다.
- 같은 `parcelId`여도 거래·상세는 선택한 `complexId` 하나만 사용한다.
- 모든 후보가 0건이면 기간·면적을 자동 완화하지 않고 대표 기본정보와 후속 질문을
  제공한다.

## 계약 영향

`api-contract: compatible additive`

- 기존 chatbot JSON/SSE URL, method와 최상위 필드를 유지한다.
- 기존 property-data map/search/detail/trade URL과 response shape는 변경하지 않는다.
- `uiActions`에 strict `focusComplex/v1`만 additive로 추가한다.
- BFF는 business selection 없이 JSON/SSE final payload를 그대로 전달한다.
- 구 Web은 알 수 없는 action을 무시할 수 있다.

## TDD 근거

- 최초 RED: 동명 후보가 2개 이상이면 capability handler가 실행되지 않아
  `tradeTable/v1`/`trendTable/v1`과 `primaryArtifactId`가 없었다.
- 예상 RED 실패: Web parser가 `focusComplex/v1`을 폐기했고, 후보 row action,
  `uiReport`와 follow-up 동시 표시, 자동/복원 실행 구분 테스트가 실패했다.
- 최소 GREEN: bounded exact observation query, deterministic/grounded 대표 선택,
  result fact 기반 table, strict action parser, 저장 성공 후 1회 자동 실행과 반복 수동
  실행을 구현했다.
- self-review 회귀: marker-unsafe 대표의 대안 자동 승격, grounded reason fact 누락,
  전체 0건에서 마지막 후보가 대표가 되는 경로를 각각 테스트로 고정해 수정했다.

## 인수 기준

- Mapo fixture: `complexId=7756`, `parcelId=8015`, 실제 trade row와 해당 table이
  primary artifact다. 다른 후보는 `다른 후보 단지`로 분리한다.
- Helio fixture: `complexId=12416`, 월별 8행·총 거래량 20건을 trend row에서 계산한다.
- exact data 후보가 있으면 0건 후보를 대표 eligibility에서 제외한다.
- 모든 후보가 0건이면 deterministic 대표를 유지하고 자동 조건 완화를 하지 않는다.
- response당 `focusComplex` 최대 6개, `showNearbyCategory` 최대 4개, 전체 최대 10개,
  `autoRun=true` 최대 하나다.
- restored conversation은 자동 실행하지 않고, 수동 `focusComplex`는 반복 실행한다.
- same-parcel/different-complex 선택은 매번 exact `complexId`로 detail을 요청한다.

## 검증 근거 확인

| 검사 | 결과 |
|---|---|
| AI property_chat 전체 | Pass — `668 passed` |
| 후보/action 대상 회귀 | Pass — marker-safe, reason fact, action cap, all-empty 대표 유지 |
| Web lint | Pass — error 0, 기존 warning 6 |
| Web test | Pass — `72 files`, `425 passed` |
| Web production build | Pass |
| chat-bff quality | Pass — JSON/SSE `focusComplex/v1` passthrough 포함 |
| property-data quality | Pass — `backendQualityCheck`, 51 tasks, 40m 37s |
| change classifier | Pass |
| nginx public route | Pass |
| service boundary | Pass |
| compose config | Pass |
| `git diff --check` | Pass |

## 검증 공백

- `ops/run-local-property-golden.sh offline`: `127.0.0.1:15432` Property DB가 열려
  있지 않아 `GOLDEN_EXECUTION_FAILED`로 case 실행 전 중단했다.
- `npm run test:live-api`: local/staging API가 준비되지 않아 실행하지 않았다.
- 1536×1024, 1280×800, 1024×768, 390×844, 844×390 browser 시각 검증은
  실행 가능한 Kakao map/local API 환경이 없어 수행하지 않았다.
- staging 성능, 7일 안정화, release manifest/SBOM/Grype, production zero-destroy와
  smoke evidence는 아직 존재하지 않는다.

## 잔여 위험

- 실제 Mapo/Helio 운영 데이터와 Kakao map에서 표·동일 좌표 후보 전환을 확인해야 한다.
- responsive drawer/detail 전환과 map control/attribution 겹침은 staging browser에서
  확인해야 한다.
- grounded selector provider timeout·schema 오류는 자동 테스트로 fallback을 검증했지만
  운영 latency와 rejection 비율은 staging 관측이 필요하다.

## 보안 영향

- action은 URL·command를 포함하지 않고 positive public IDs와 대한민국 범위 좌표만
  허용한다.
- marker-safe complex fact의 `complexId`, `parcelId`, 좌표, `factIds`가 일치하지 않으면
  action을 폐기한다.
- `complex_pk`, `apt_seq`, `source`, `source_key`, PNU를 public action에 노출하지 않는다.
- dependency, auth/JWT, server-side conversation persistence, secret, frontend telemetry를
  변경하지 않았다.
- React text rendering을 유지하며 HTML 삽입을 추가하지 않았다.

security-audit: 지적사항 = none

code-review: 지적사항 = none

## rollback

- `HOME_AI_ANSWER_FIRST_ORCHESTRATION_ENABLED=false`는 동명 후보를 기존 ambiguity
  fallback으로 되돌리는 임시 rollback 경로다.
- DB migration이 없으므로 배포 후 문제 시 AI/BFF/Web 이전 immutable digest로만
  복귀한다.
- DB down migration, Docker volume 삭제, raw/trade row 수정, mutable tag 재지정은
  수행하지 않는다.

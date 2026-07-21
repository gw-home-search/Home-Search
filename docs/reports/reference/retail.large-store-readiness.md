# `retail.large-store` readiness

기준일: 2026-07-21

상태: `Limited Pass`

실제 데이터 readiness: `제한 지원`

## 검증 근거 확인

- 공식 파일 상세 `15045013`의 이용허락, 일간 갱신, CSV download URL을
  source evidence로 고정했다.
- active datasetVersion은 `20260720-e61a2e1ce389`이며 원본 `4,176`행,
  rejected `0`, 두 번째 수집 `NoChange`, verified raw 복구를 확인했다.
- 원본 좌표는 `3,497`행에 있다. 좌표가 없는 `679`행 중 법정동과 지번을 정확히
  식별해 19자리 PNU를 만들 수 있고 Coordinate Source DB에서 같은 PNU가 한 건으로
  확인된 `211`행만 별도 불변 evidence로 게시했다.
- 현재 좌표 확인 범위는 `3,708/4,176` (`88.7931%`)이고 미확인 원장은 `468`행이다.
- property DB와 coordinate-source DB는 애플리케이션에서 각각 읽고 DB 간 SQL join을
  하지 않는다. 원본 registry fact와 active pointer도 변경하지 않는다.
- 주소 API, 임의 geocoding, fuzzy 주소 매칭은 사용하지 않았다. `읍·면·리`, 불완전
  지번, 복수 해석 주소는 미확인으로 남긴다.
- 1km query 20회 p95 `57.356ms`, 3km query 20회 p95 `53.259ms`로 200ms 기준을
  통과한 기존 공간 조회 경로를 그대로 사용한다.

## 제한 활성화 결정

프로젝트 책임자가 현 단계의 `88.79%`를 허용했으므로 이 source에만 적용하는
`88%` fail-closed 기준으로 `retail_location`, 비교의 대규모점포 행,
`CRITERIA/SHOPPING`, `BUDGET` 추천을 제한 활성화한다.

- 모든 사용자 응답에는 좌표가 확인된 공식 원장 범위만 반영했다는 제한을 표시한다.
- 좌표 미확인 `468`행을 0건이나 부재로 해석하지 않으며 `verifiedZero=false`를 유지한다.
- coverage가 `88%` 아래로 내려가거나 active metadata가 없으면 실행 전에 unavailable로
  종료한다.
- 어린이집·유치원은 이 결정과 무관하며 runtime allowlist에 포함하지 않는다.
- 향후 공식 주소 좌표 source가 승인되면 별도 보완 Slice에서 coverage를 높일 수 있다.

## 검증 공백과 롤백

- 승인된 OpenAI live 대표 질문은 데이터 관찰까지 통과했지만 답변 생성 단계에서
  시작된 timeout은 `reasoning.effort=none`으로 해소됐다. 다만 claim-only 입력과 rail
  validator 보완 뒤에도 `GROUNDING_RAIL_TEXT_OUTSIDE_OBSERVATION`이 재현되어 운영 배포
  gate는 `Fail`이다. deterministic observation, grounding, artifact, local runtime,
  JSON/SSE transport 검증은 유지한다.
- 문제 발생 시 reference allowlist를
  `academy_lookup,rail_station_lookup,school_location`으로 되돌리면 retail repository와
  `BUDGET` mode가 함께 비활성화된다. 원본·보완 evidence는 삭제하지 않는다.

license evidence:
`apps/ai/config/license_evidence/retail.large-store.txt`

SHA-256:
`db24e0107b7b2e42abb55389468f64d2503bee116551e49c37eb20f58d719b38`

`api-contract: compatible`

security-audit: 지적사항 = none

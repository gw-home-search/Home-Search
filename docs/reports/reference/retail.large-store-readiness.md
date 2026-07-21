# `retail.large-store` readiness

기준일: 2026-07-20

상태: `Partial`

실제 데이터 readiness: `8.0/10 Partial`

## 검증 근거 확인

- 공식 파일 상세 `15045013`의 `이용허락범위 제한 없음`, 일간 갱신·2일 전 기준
  현행화, CSV download URL을 source별 evidence로 고정했다.
- collector는 고정된 `file.localdata.go.kr/file/download/large_scale_retail_stores/info`
  와 같은 host의 tracked `Referer`만 사용한다. provider key는 읽거나 전달하지 않는다.
- filename에 검증 가능한 release date가 없으므로 계약은 수집 시작 UTC의
  `OBSERVED_AT`이며, 실제 CP949 body를 `large-store-v3`로 분리했다.
- 현재 공식 status와 업태 값은 explicit mapping한다. 새로운 nonblank 값, 필수 ID
  중복, 대한민국 밖 좌표는 publication을 차단한다. 전화번호는 raw 밖으로 투영하지 않는다.

## live 결과

- 첫 수집: `Pass`, 1 file, raw/accepted `4,176`, rejected `0`, active datasetVersion
  `20260720-e61a2e1ce389`.
- projection: 고유 fact `4,176`, spatial `3,497`, non-spatial `679`, OPEN `2,970`,
  공식 분류 `8`종, unknown region `0`.
- 좌표 coverage는 `83.7404%`로 전국 `95%` readiness 기준에 미달한다.
- 좌표가 없는 679건은 원본 X/Y가 모두 비어 있으며 운영 중인 점포 483건도
  포함한다. 679건 모두 주소 후보는 있으나 `호`처럼 좌표 보완에 사용할 수 없는
  불완전 주소도 있어 단순 주소 변환으로 exact-match 품질을 보장할 수 없다.
- 두 번째 수집: `NoChange`, staging `0`, publication 총 `1`; active pointer 유지.
- 서울 대표 좌표의 1km 공간 query 20회 실측 p95는 `57.356ms`, max는
  `59.828ms`로 200ms 기준을 통과했다.
- 최대 반경 3km의 warm-up 후 20회 p95는 `53.259ms`, max는 `53.945ms`다.
- runtime static composition과 공식 fileData citation URL을 검증했지만 승인 질문의
  live JSON은 `503`, SSE는 error event로 끝나 chatbot golden을 통과하지 못했다.
- 최신 raw bundle은 MinIO version object에서 복구해 DB와 동일한 SHA-256
  `e43231a24aa74adf41274d2bbee6222a9fae510c81c036845b7b31ae96ceed0e`,
  `1,183,918` bytes를 확인했다.
- 이전 OpenAPI `API_AUTHENTICATION_FAILED` refresh evidence는 삭제하지 않고 audit에
  보존했다.

## 잔여 위험과 활성화

- 좌표 coverage 미달이므로 `retail_location` activation은 금지한다.
- 승인된 공식 주소→좌표 보완 source의 credential·license·exact-match 검증 전에는
  임의 geocoding을 적용하거나 좌표 없는 행을 제외해 coverage 기준을 우회하지 않는다.
- provider 개방자치단체 code와 property 법정동 code mapping 전에는 정상 0건도
  `verifiedZero=false`를 유지한다.
- chatbot JSON/SSE live golden은 실패 상태로 보존한다.
- local publication과 raw evidence는 보존하며 rollback은 이전 pointer 전환만 사용한다.

license evidence:
`apps/ai/config/license_evidence/retail.large-store.txt`

SHA-256:
`db24e0107b7b2e42abb55389468f64d2503bee116551e49c37eb20f58d719b38`

`api-contract: compatible`

security-audit: 지적사항 = none

# place.sbiz-academy readiness

- 상태: `Pass` (`10.0/10`)
- 통과: 공식 tracked taxonomy 18개 partition, 201 pages·191,250 고유 ID,
  rejected/좌표 누락 0, verified S3 raw 복구, typed projection·active pointer,
  두 번째 `NoChange`와 staging 0, signed JWT JSON/SSE의 Sbiz B+NEIS exact A
  dual citation, 800m query 20회 p95 `25.373ms`, 최대 반경 2km query 20회
  p95 `156.927ms`
- 중단: 없음
- 안전 제한: fuzzy match 금지, 행정코드 mapping 전 `verifiedZero=false`
- 활성화: 2026-07-21 local runtime template의 `academy_lookup` 승인; rollback은 빈 값
- activation smoke: 제한 재검증 JSON/SSE `200/success`, A+B citation, SSE error 0

최초 live taxonomy parse failure는 삭제하지 않았다. taxonomy evidence는 원문 문자를
보존하며 store 행 code/name만 exact allowlist로 검증한다. 최신 raw 복구 SHA-256은
`e0736be2c05d3d41f90e0e694424c32e3a37e1c6c8026e0e34e6862d80e3d1ee`다.

`api-contract: compatible`

security-audit: 지적사항 = none

# retail.large-store readiness

- 상태: `Partial` (`8.0/10`)
- 통과: 공식 CP949 CSV full acquisition, 4,176 고유 ID, rejected 0, verified S3 raw
  복구, typed projection·active pointer, 두 번째 `NoChange`와 staging 0,
  1km query 20회 p95 `57.356ms`, 3km query 20회 p95 `53.259ms`
- 중단: 좌표 `3,497/4,176` (`83.7404%`)로 전국 `95%` 기준 미달 및
  live JSON `503`·SSE error
- 안전 제한: provider 행정코드 mapping 전 `verifiedZero=false`
- 활성화: 금지

local DB에는 spatial `3,497`, non-spatial `679` fact를 구분해 보존했다. filename에
검증 가능한 release date가 없어 수집 시작 UTC의 `OBSERVED_AT`을 사용하며 공식
2일 provider lag를 evidence에 기록한다. 이전 API 인증 실패 audit와 모든 raw object는
삭제하지 않았다.

license evidence SHA-256:
`db24e0107b7b2e42abb55389468f64d2503bee116551e49c37eb20f58d719b38`

`api-contract: compatible`

security-audit: 지적사항 = none

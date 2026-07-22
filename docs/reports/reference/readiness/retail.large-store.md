# retail.large-store readiness

- 상태: `Limited Pass` (`제한 지원`)
- 통과: 공식 CP949 CSV `4,176` 고유 ID, rejected `0`, verified raw 복구,
  typed projection·active pointer, 두 번째 `NoChange`, exact lot/PNU 좌표 보완 `211`건,
  1km p95 `57.356ms`, 3km p95 `53.259ms`
- 현재 범위: 좌표 `3,708/4,176` (`88.7931%`), 미확인 `468`
- 안전 제한: 이 source 전용 `88%` fail-closed, 사용자 제한 문구, `verifiedZero=false`,
  주소 API·fuzzy geocoding 미사용
- 활성화: `retail_location`, comparison retail row, `CRITERIA/SHOPPING`, `BUDGET`
- 비활성 유지: 어린이집·유치원
- live gate: 결과 수 서버 재검증·provider 지침 보완 후 승인 case `Pass` —
  `recommendation`, fact 12건, citation 5건, 기준일 `2026-06-12`, provider 요청 상한 6

좌표 보완은 원본 registry fact를 수정하지 않는 additive evidence다. 법정동·지번으로
만든 정확한 19자리 PNU가 Coordinate Source DB에 존재할 때만 위치를 게시한다.

license evidence SHA-256:
`db24e0107b7b2e42abb55389468f64d2503bee116551e49c37eb20f58d719b38`

`api-contract: compatible`

security-audit: 지적사항 = none

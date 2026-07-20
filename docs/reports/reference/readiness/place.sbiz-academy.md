# place.sbiz-academy readiness

- 상태: `Partial` (`3.0/10`)
- 구현 품질: S2 collector `10.0/10 Pass`, S2-P grounded location `10.0/10 Pass`
- 통과: source별 무제한 이용 허락·제3자 provenance·private raw/internal derivative evidence fingerprint, 공식 247행 taxonomy artifact·P1 교육업종 18개 allowlist·checksum/fingerprint, 공식 가이드의 대분류→중분류 parent→소분류 parent scoped 요청·변경 시 store 요청 전 차단, parent별 taxonomy raw response bundle 보존, `newZipcd`·업종 code/name 계약, fake partition 수집, 중복 ID 차단, 단일 후보 exact match projection, 800m 경계·최대 5건·전국 95% coverage 차단·fuzzy match 거부 observer offline 검증
- 중단: live taxonomy가 tracked fingerprint와 불일치, store acquisition·실제 좌표·지역 90% coverage·S3·chatbot golden 미검증
- live: key 인증 후 unscoped 대·중·소 응답 count `25/266/1,255`가 공식 포털·활용가이드의 `10/75/247`과 불일치해 `TAXONOMY_CHANGED`, store partition·acquisition `0`
- 활성화: 금지
- 잔여 위험: 실제 현행 taxonomy와 tracked artifact 차이 가능성, Sbiz 행정코드와 property 법정동 코드 mapping 미검증으로 `verifiedZero=false`

2026-07-20 최초 preflight는 key 설정 전 exit `2`; 수정 후 live 호출은
`TAXONOMY_CHANGED`로 store 요청 전에 중단했다. 공식 가이드가 요구하는 parent scoped
taxonomy 요청은 offline 계약으로 수정했지만, 첫 대분류 응답부터 공식 count와 달라
live 재수집도 store 요청 전에 중단해야 한다.

`security-audit: 지적사항 = none`

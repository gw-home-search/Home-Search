# transport.rail-station readiness

- 상태: `Partial` (`3.0/10`)
- 통과: 공식 KRIC `id=32` fixed download endpoint와 2026-06-30 XLSX header preflight, secret fixed query 차단, secure-temp raw-first refresher, archive 보안 검사, occurrence projection, exact NFKC+250m 병합
- 이용조건: fileData 상세 `15093755`의 `이용허락범위 제한 없음`과 프로젝트 책임자가 보고한 KRIC 전화 승인을 근거로 `APPROVED`. 서면 transcript가 없다는 잔여 위험을 명시함
- 중단: provider 필수 ID 중복, S3 byte 복구·공간 query·chatbot golden 미검증
- 안전 제한: KRIC/data.go.kr landing HTML을 acquisition URL로 사용하지 않음
- offline 검증: static catalog 연결, safe reason code 기록, fixed-query 계약 집중 테스트 `31 passed`
- 활성화: 금지

2026-07-20 최초 live preflight: 승인 전 `CONFIGURATION_INVALID`, provider body 요청 없음.

KRIC 상세는 `전체_도시철도역사정보_20260630`으로 갱신됐다. 프로젝트 책임자는
KRIC에 전화로 계획된 Home Search 이용 가능 여부를 문의해 가능하다는 답변을 받았다고
2026-07-20 진술했고, 서면 기록 부재를 수용해 source contract 승인을 지시했다.
원본 공개 재배포는 승인하지 않으며 약관이나 dataset license 변경 시 재검토한다.

승인 범위는 원본 XLSX의 private S3 보관, 역명·노선·환승·좌표 normalized derivative의
사용자 응답 표시, 전화번호 비공개, 국가철도공단·철도산업정보센터 attribution이다.
전화 승인 진술과 공식 fileData 이용허락을 정규화한 evidence checksum을 고정한다.

승인 후 live 검증은 2026-06-30 release 1,099행을 읽었고 좌표 누락은 0행이었다. 다만
`운영기관명+노선번호+역번호` key 5개가 10행에서 중복됐고, row
`데이터기준일자`는 2022-04-27..2026-06-25로 혼재하며 공란 7행과 비정상
`1900-01-00` 6행을 포함했다. row 날짜는 nullable provenance로 분리한
`rail-station-v3`에서 1,094행이 유효하고 중복 key의 후행 5행만 거부됐다.
`DUPLICATE_UNIQUE_KEY`, `REJECTED_ROW_RATIO_EXCEEDED`로 publication을 차단했고 active
pointer는 없다. provider 정정 또는 dataset-specific identity 계약 없이는 임의
dedupe·최신행 선택을 하지 않는다.

`rail-station-v4` actual refresh는 같은 raw를 acquisition
`d246e9c4-cb19-4cda-862a-92e8a260adbd`로 재처리했다. malformed row 날짜 6건은
`RAIL_ROW_REFERENCE_DATE_INVALID` warning으로 저장했고 중복 후행 5행만 rejected 상태다.
두 번째 v4 refresh는 같은 acquisition을 재사용했지만 성공 publication이 없으므로 결과는
`NoChange`가 아니라 `QUALITY_FAILED`, source 상태는 계속 `Partial`이다.

license evidence SHA-256:
`14d6a007c272b75456998f5289e08ff8f61cb82f04bb2fc6a72ef9c11327ff0d`

`security-audit: 지적사항 = none`

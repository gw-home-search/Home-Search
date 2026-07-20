# transport.rail-station readiness

- 상태: `Partial` (`2.0/10`)
- 통과: 공식 KRIC `id=32` fixed download endpoint와 2026-06-30 XLSX header preflight, secret fixed query 차단, secure-temp raw-first refresher, archive 보안 검사, occurrence projection, exact NFKC+250m 병합
- 중단: 공공데이터포털 fileData 상세 `15093755`의 `이용허락범위 제한 없음`과 KRIC 약관 제15조·제17조의 사전승낙 없는 영리·상업적 이용 금지가 충돌함. KRIC의 서면 승인 또는 dataset-specific 우선 적용 확인, 전체 artifact·실제 좌표 100%·S3·공간 query·chatbot golden 미검증
- 안전 제한: KRIC/data.go.kr landing HTML을 acquisition URL로 사용하지 않음
- offline 검증: static catalog 연결, safe reason code 기록, fixed-query 계약 집중 테스트 `31 passed`
- 활성화: 금지

2026-07-20 live preflight: `CONFIGURATION_INVALID`, provider body 요청 없음.

KRIC 상세는 `전체_도시철도역사정보_20260630`으로 갱신됐지만 release freshness가
이용조건 충돌을 해소하지는 않는다. 서면 확인 전에는 contract `PENDING`과 activation
금지를 유지한다.

서면 문의에는 다음 범위를 명시한다: Home Search 상용 서비스, 원본 XLSX의 private S3
보관, 역명·노선·환승·좌표 normalized derivative의 사용자 응답 표시, 전화번호 비공개,
국가철도공단 attribution. `data.go.kr` dataset-specific 무제한 이용허락이 KRIC 일반
약관 제15조·제17조보다 우선하는지 또는 별도 사전승낙으로 이 범위를 허용하는지 답변을
요청한다. 승인 메일/PDF의 원문 checksum을 고정하기 전에는 live download를 실행하지
않는다.

blocker evidence SHA-256:
`7c7b5bcc34c53cc7edbe8518394a4f507df610ae0f23c9dace4414dc88927ae9`

`security-audit: 지적사항 = none`

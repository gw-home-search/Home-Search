# transport.rail-station readiness

- 상태: `Partial` (`2.0/10`)
- 통과: 공식 KRIC `id=32` fixed download endpoint와 2026-06-30 XLSX header preflight, secret fixed query 차단, secure-temp raw-first refresher, archive 보안 검사, occurrence projection, exact NFKC+250m 병합
- 중단: dataset별 이용허락 값 공란, KRIC 사전 승낙 없는 영리행위 금지·제공기관 저작권·운영기관 원천 provenance, 전체 artifact·실제 좌표 100%·S3·공간 query·chatbot golden 미검증
- 안전 제한: KRIC/data.go.kr landing HTML을 acquisition URL로 사용하지 않음
- offline 검증: static catalog 연결, safe reason code 기록, fixed-query 계약 집중 테스트 `31 passed`
- 활성화: 금지

2026-07-20 live preflight: `CONFIGURATION_INVALID`, provider body 요청 없음.

blocker evidence SHA-256:
`7c7b5bcc34c53cc7edbe8518394a4f507df610ae0f23c9dace4414dc88927ae9`

`security-audit: 지적사항 = none`

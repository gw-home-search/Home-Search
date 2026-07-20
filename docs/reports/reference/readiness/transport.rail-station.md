# transport.rail-station readiness

- 상태: `Partial` (`2.0/10`)
- 통과: 공식 KRIC `id=32` fixed download endpoint와 2026-06-30 XLSX header preflight, secret fixed query 차단, secure-temp raw-first refresher, archive 보안 검사, occurrence projection, exact NFKC+250m 병합
- 중단: dataset별 이용조건 승인 없음, 전체 artifact·실제 좌표 100%·S3·공간 query·chatbot golden 미검증
- 안전 제한: KRIC/data.go.kr landing HTML을 acquisition URL로 사용하지 않음
- offline 검증: static catalog 연결, safe reason code 기록, fixed-query 계약 집중 테스트 `31 passed`
- 활성화: 금지

`security-audit: 지적사항 = none`

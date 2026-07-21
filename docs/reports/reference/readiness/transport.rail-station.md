# transport.rail-station readiness

- 상태: `Pass` (`10.0/10`)
- 통과: 공식 fixed XLSX acquisition, occurrence `1,097`, duplicate/좌표 누락 0,
  verified S3 raw 복구, typed projection·active pointer, 동일 release 재수집 재사용,
  1.5km query 20회 p95 `7.971ms`, 3km query 20회 p95 `25.565ms`, live exact 역 병합,
  signed JWT JSON/SSE `200`·A등급 citation·SSE error 0
- 중단: 없음
- 안전 제한: fuzzy name 병합 금지, latest-date 동률·날짜 부재 duplicate 차단
- 활성화: 2026-07-21 누적 local runtime template 승인; rollback은 `academy_lookup`
- activation smoke: JSON `200/success`, 제한 재검증 SSE final 1·error 0, A등급 citation

`rail-station-v5`는 `operator+line_number+line_name+station_number` occurrence를
보존하고 동일 identity의 유일한 최신 기준일만 유지한다. superseded 2행과 malformed
row date 6건은 warning으로 남겼다. raw 복구 SHA-256은
`919c7b7763fa146d65e5f7483d73d1ab19628b27daa7ba7f3e35321e189eb515`다.

`api-contract: compatible`

security-audit: 지적사항 = none

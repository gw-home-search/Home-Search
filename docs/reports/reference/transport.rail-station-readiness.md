# `transport.rail-station` readiness

기준일: 2026-07-19
상태: `Partial`

read-only/data-only XLSX adapter와 `운영기관+노선번호+역번호` 고유키, 환승노선 배열,
좌표 필수 검증을 구현했다. macro, external link, 중복 ZIP entry, 과도한 sheet/cell,
uncompressed size와 compression ratio를 fail-closed로 거부한다.

고정 release URL만 허용하는 collector orchestration을 static catalog에 연결했다.
artifact는 owner-only temp에 기록한 뒤 deterministic bundle과 verified S3 file upload를
거치며, landing HTML URL은 network 호출 전 거부한다. 수집 실패는 provider body 없이
safe reason code만 refresh-run에 기록한다.

이용조건은 `PENDING`이며 실제 연간 release, 500~2,000행, 좌표 100%,
역 단위 답변 병합, 1.5km/3km chatbot live golden은 미완료다. 운영 capability는
활성화하지 않았다.

security-audit: 지적사항 = listed

- 실제 release 구조와 header contract 검증, parser resource 측정, role permission
  smoke가 미완료다.

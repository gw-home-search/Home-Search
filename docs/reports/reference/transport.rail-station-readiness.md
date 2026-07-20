# `transport.rail-station` readiness

기준일: 2026-07-20
상태: `Partial`

read-only/data-only XLSX adapter와 `운영기관+노선번호+역번호` 고유키, 환승노선 배열,
좌표 필수 검증을 구현했다. macro, external link, 중복 ZIP entry, 과도한 sheet/cell,
uncompressed size와 compression ratio를 fail-closed로 거부한다.

공식 KRIC detail `id=32`가 제공하는 고정 download endpoint를 tracked config에 연결했다.
2026-07-20 body 수신 전 header preflight에서
`전체_도시철도역사정보_20260630.xlsx`, 313,132 bytes,
`application/octet-stream`을 확인했다. base URL query 금지는 유지하고 file source 전용
`fixed_query`만 분리 검증하며, secret 성격의 parameter와 query redirect는 거부한다.
artifact는 owner-only temp에 기록한 뒤 deterministic bundle과 verified S3 file upload를
거치며, landing HTML URL은 network 호출 전 거부한다. 수집 실패는 provider body 없이
safe reason code만 refresh-run에 기록한다.

이용조건은 `PENDING`이며 실제 연간 release, 500~2,000행, 좌표 100%,
역 단위 답변 병합, 1.5km/3km chatbot live golden은 미완료다. 운영 capability는
활성화하지 않았다.

## TDD 근거

- 최초 RED: KRIC fixed query 미지원, landing URL config, query 누락 transport로 6건 실패.
- 예상 RED 실패: 공식 download endpoint가 기존 `.xlsx` suffix와 no-query 계약에 막힘.
- 최소 GREEN: file-only non-secret `fixed_query`, exact query transport, 공식 media type,
  Content-Disposition source date 검증을 추가했다.
- 좁은 회귀: file contract·snapshot client·rail ingest `31 passed`; 철도 통합 `58 passed`.
- 전체 AI 회귀: `550 passed`, coverage `90.15%`.

`api-contract: compatible`

`security-audit: 지적사항 = none`

- 전체 release 구조·checksum, parser peak memory, 운영 role permission smoke가
  readiness 공백으로 남는다.

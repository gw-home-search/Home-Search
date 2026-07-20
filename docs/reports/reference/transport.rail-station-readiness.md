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

승인 후 첫 live GET은 KRIC가 `User-Agent` 없는 요청에 `200 text/html`을 반환해
`FILE_MEDIA_TYPE_INVALID`로 안전하게 중단했다. 동일 URL은 고정 importer
`User-Agent`에서 XLSX header를 반환함을 재현했고, TDD로 해당 header를 추가하면서
media type·length·source date 검증은 완화하지 않았다.

다음 live 시도는 2026-06-30 XLSX의 최신 exact header가 이전 fixture 명칭과 달라
`SOURCE_SCHEMA_MISMATCH`로 publication 전에 중단했다. 실제 header를 데이터 행 없이
확인해 exact alias를 추가하고 normalization schema를 `rail-station-v2`로 올렸다.
동일 raw의 이전 `PARSE_FAILED` acquisition을 삭제하지 않고 새 schema에서 재처리할 수
있도록 additive migration `0011_schema_scoped_acquisition_dedupe.sql`을 추가했다. 동일
schema 재수집과 품질 기준만 바뀐 contract는 계속 기존 acquisition을 재사용한다.

공공데이터포털 fileData `15093755`는 `이용허락범위 제한 없음`을 명시한다. 프로젝트
책임자는 KRIC에 전화로 계획된 Home Search 이용 가능 여부를 문의해 가능하다는 답변을
받았다고 2026-07-20 진술했고, 서면 transcript 부재를 수용해 contract 승인을 지시했다.
승인 범위는 원본 XLSX의 private 보관, 역명·노선·환승·좌표의 내부 가공과 출처표시
응답이며 원본 공개 재배포와 전화번호 projection은 금지한다. 실제 연간 release,
500~2,000행, 좌표 100%, 역 단위 답변 병합, 1.5km/3km chatbot live golden은
미완료다. 운영 capability는 활성화하지 않았다.

## TDD 근거

- 최초 RED: KRIC fixed query 미지원, landing URL config, query 누락 transport로 6건 실패.
- 예상 RED 실패: 공식 download endpoint가 기존 `.xlsx` suffix와 no-query 계약에 막힘.
- 최소 GREEN: file-only non-secret `fixed_query`, exact query transport, 공식 media type,
  Content-Disposition source date 검증을 추가했다.
- 좁은 회귀: file contract·snapshot client·rail ingest `31 passed`; 철도 통합 `58 passed`.
- 전체 AI 회귀: `554 passed`, coverage `90.18%`.
- live failure RED: `User-Agent` 부재 요청은 `200 text/html`, audit
  `FILE_MEDIA_TYPE_INVALID`; header 계약 2건 RED 후 file client `13 passed`, 전체 AI
  `564 passed`, coverage `90.10%`.
- schema recovery RED: 최신 KRIC header fixture는 `SOURCE_SCHEMA_MISMATCH`, 동일 raw의
  새 schema 재처리는 기존 실패를 반환; exact alias와 schema-scoped acquisition
  dedupe 후 Postgres 집중 회귀 `57 passed`, 전체 AI `566 passed`, coverage `90.08%`.

`api-contract: compatible`

`security-audit: 지적사항 = none`

- 전체 release 구조·checksum, parser peak memory, 운영 role permission smoke가
  readiness 공백으로 남는다.
- license evidence는
  `apps/ai/config/license_evidence/transport.rail-station.txt`에 고정했고 SHA-256은
  `14d6a007c272b75456998f5289e08ff8f61cb82f04bb2fc6a72ef9c11327ff0d`다.
- 2026-07-20 최초 live preflight는 승인 전 license `PENDING`을
  `CONFIGURATION_INVALID`로 반환해 provider body 요청 전 exit `2` 중단했다.

# Slice 6A 학교 위치 데이터 준비 보고서

기준일: 2026-07-19
판정: `Partial` — 학교 위치 이용조건과 local AI DB/chatbot transport는 검증했으나
MinIO importer credential 미설정으로 전국 import와 학교 live golden이 수행되지 않았다.

source별 최신 근거: `docs/reports/reference/edu.school-location-readiness.md`

## 출처와 이용 조건

- 데이터셋: `edu.school-location` / 전국초중등학교위치표준데이터
- 공식 landing URL: `https://www.data.go.kr/data/15021148/standard.do`
- 공식 acquisition endpoint: query와 credential을 제외한
  `https://api.data.go.kr/openapi/tn_pubr_public_elesch_mskul_lc_api`
- 공식 페이지 확인: 반기 갱신, 학교ID·학교급·운영상태·주소·교육청·위경도·
  데이터기준일자 제공, API page 크기 최대 1,000. 페이지 수정일은
  `2026-05-06`, 현재 표본 기준일은 `2026-03-20`이다.
- 이용 조건: `APPROVED`. 사용자가 현재 작업에서 명시적으로 승인한 학교 위치
  조건만 tracked contract에 기록했다. 다른 source에는 확대 적용하지 않는다.

## 원본과 품질 결과

- deterministic bundle: `manifest.json` 뒤에 `artifacts/page-NNNNNN.json`을 고정 순서·
  timestamp로 저장하고 page response bytes와 SHA-256을 보존한다.
- raw-first: bundle checksum의 immutable raw object 저장 뒤에만 adapter parsing과
  staging을 수행한다.
- 검증 fixture: page checksum, page 번호/크기, `totalCount`, row 수, 기준일,
  필수 필드, 학교ID 중복, 주소, 학교급, 대한민국 안전 좌표, 17개 시도교육청
  coverage, freshness, 이전 active 대비 증감을 검사한다.
- 코드 검증: `TESTCONTAINERS_RYUK_DISABLED=true uv run pytest` gate로 검증한다.
- 실제 checksum / 원본·정상·격리 row 수: `not run`.

## 활성화 범위

- `school_location`: `데이터 준비 중` 유지.
- `HOME_AI_ENABLED_REFERENCE_CAPABILITIES`: blank 유지.
- 기존 `complex_identity`, `recent_trade_lookup`, `price_trend`: 회귀 통과.
- 초등 통학구역, 중·고 학교군, 학교 평가, 도보시간, 지도 marker: 비범위.

## 검증 근거 확인

- migration discovery·checksum mismatch fail-fast 테스트
- deterministic ZIP 재생성 동일성·provider page bytes 복구 테스트
- timeout/5xx 1회 retry, 429/redirect/auth fail-closed 테스트
- 공식 provider result code `22`와 `30`을 각각 quota/authentication
  stable reason code로 분리하고 오류 body를 저장하지 않는 테스트
- 학교 adapter와 typed view/Haversine repository 테스트
- 정확한 800m 경계 포함, 초과·폐교·다른 학교급 제외 테스트
- 정상 0건 `supported`, stale `unavailable`, property/school citation 분리 테스트
- 배정학교 등 금지 주장 grounding 차단 테스트
- isolated PostgreSQL에서 runtime typed view `SELECT` 허용과 raw table
  `permission denied`, importer raw `UPDATE` 거부 테스트
- fresh-volume init과 기존-volume AI-only bootstrap이 같은 세 role/DB 경계를
  사용하며, fake Docker preflight에서 secret 비노출·멱등 exec 형태 검증
- chatbot runtime에는 reference DSN/allowlist만 전달되고 migrator/importer
  credential과 공공 API key가 전달되지 않는 static boundary 검증
- no-argument runner의 stale reference DSN 무시·안전한 재파생과 실제 chatbot
  image/health startup 검증
- 실제 서명 JWT JSON/SSE, 잘못된 issuer `401`, 기존 property route 회귀 검증
- local refresh wrapper의 protected env parsing, Docker argument secret 비노출,
  MinIO/migration/importer 실행 순서 검증
- `api-contract`: `compatible`. 기존 JSON/SSE URL·method·top-level field·오류
  shape는 유지되고 `school_location`과 학교 citation 값만 기존 필드 안에 추가됨

## 검증 공백

- `apps/ai/.env`의 `AWS_ACCESS_KEY_ID`와 나머지 MinIO importer 설정 완성
- 실제 기존 volume에서 dataset migration `0001..0004` 적용
- 공식 API 전체 page 수집과 10,000..50,000 row, rejected `0`, 17개 coverage 확인
- 동일 실제 bundle 재실행의 acquisition/publication 멱등성
- 운영 `home_search_ai_runtime` credential의 raw/staging/quarantine permission denied smoke
- 실제 Haversine query 실행계획과 대표 20회 응답시간
- 승인된 school observation/LLM live 질문

## 잔여 위험

- 실제 API JSON envelope가 문서와 fixture 가정과 다르면 adapter는
  `API_ENVELOPE_INVALID`로 게시를 차단한다.
- tracked contract가 `APPROVED`가 아니면 importer는 provider 호출 전에 exit `2`로
  중단한다.
- 실제 readiness `Pass` 전에는 Registry 상태와 runtime allowlist를 변경하지 않는다.

## 계약 영향

- 기존 `POST /api/v1/chatbot/query`와
  `POST /api/v1/chatbot/query/stream` URL, request/response field, ProblemDetail,
  SSE event 의미는 변경하지 않았다.
- 추가 근거 값은 기존 `citations`와 `evidenceSummary.capabilities` 안에서만 생성된다.
- 계약 영향 판정: `compatible`. 검증된 검색 범위 fact가 있는 정상 0건은 기존
  `supported` 상태 의미에 포함되도록 계약 문구를 명시했다.
- 기존 property-data 공개 API 계약 영향: `none`.

## 보안 영향

보안 영향: 외부 API key, 별도 importer/runtime DSN, ZIP/JSON parser, read-only
reference pool이 추가됐다. 고정 HTTPS host/path, redirect 거부, credential query·오류
body 비저장, 4 MiB page·128 MiB bundle 상한, 전용 DB/role 재검증, parameterized SQL,
runtime typed view 최소 권한과 학교 금지 주장 grounding을 검토했다.

security-audit: 지적사항 = listed

- 실제 MinIO importer policy/bucket/object-lock, AWS SSE-KMS/IAM, 운영 role
  permission smoke가 미완료다.

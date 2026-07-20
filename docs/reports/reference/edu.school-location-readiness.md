# `edu.school-location` readiness

기준일: 2026-07-19
상태: `Partial`

## 출처 / 이용 조건

- 출처: `전국초중등학교위치표준데이터`
- 공식 URL: `https://www.data.go.kr/data/15021148/standard.do`
- tracked contract: `apps/ai/config/reference_sources.toml`
- 이용조건 상태: `APPROVED` — 현재 작업에서 사용자가 승인한 학교 위치 조건만
  `reviewed_on=2026-07-19`, `reviewed_by=user-approved-plan`으로 기록했다.
- 이 승인은 다른 공공데이터 source로 확대하지 않는다.

## 구현·검증 근거 확인

- deterministic `manifest.json` + `artifacts/page-NNNNNN.json` bundle
- S3 conditional upload와 HEAD checksum/length/version 검증 뒤 DB metadata 등록
- lifecycle v2, semantic `NO_CHANGE`, `psycopg COPY`, typed school projection
- 17개 교육청 coverage, 학교 ID 중복, 좌표·학교급·운영상태·기준일 검증 테스트
- runtime active read view만 SELECT, raw/projection base table 쓰기 차단 테스트
- 공개 JSON/SSE URL·field·error shape 변경 없음
- 실제 AI DB role bootstrap과 chatbot image/health startup 통과
- local refresh가 기존 volume의 AI DB role password를 멱등 동기화한 뒤 실제
  migration에 성공
- 실제 MinIO bucket private 설정, versioning, Object Lock `GOVERNANCE 365DAYS`,
  importer 전용 `GetObject|PutObject` policy 적용 통과
- 실제 서명 JWT JSON/SSE, 잘못된 issuer `401`, property route 회귀 통과
- local refresh wrapper의 secret 비노출과
  `AI DB role bootstrap → MinIO → migration → importer` 실행 순서 테스트 통과
- 첫 page provider 실패가 allowlist reason code만 출력하고 key, query URL,
  provider body를 출력하지 않는 회귀 테스트와 실제 실행 확인
- 실제 첫 page transport 실패가 `dataset_refresh_run=FAIL`,
  `dataset_refresh_run_item=FAIL`, `acquisition_id=NULL`,
  `reason_codes={API_TRANSPORT_FAILED}`로 기록됨을 직접 SQL로 확인

## 검증 공백

- 대표 공간 query 20회 p95와 live chatbot golden 근거가 아직 없다.
- 2026-07-19 실제 실행에서 Docker와 host 모두 `api.data.go.kr:443` DNS·TLS
  연결 뒤 응답을 받지 못해 `API_TRANSPORT_FAILED`로 중단됐다. 공식 dataset
  페이지는 수정일 `2026-05-06`, 표본 데이터 기준일 `2026-03-20`으로 계속
  제공 중이므로 source contract를 임의 변경하지 않고 provider 복구 뒤 재시도한다.
- 2026-07-20 재시도는 HTTP 200과 JSON media type까지 확인했지만 JSON parsing 이전
  `API_ENVELOPE_INVALID`로 중단됐다. client는 non-JSON media type과 root/response/body/
  items 구조를 body 비노출 reason code로 분리하고 additive field를 허용하도록 보강했다.
  동일 결과가 유지되므로 dataset별 활용승인·Decoding key 또는 provider gateway 응답을
  확인하기 전 schema를 더 완화하지 않는다.
- 이후 safe 구조 진단에서 key는 decoded 형태이고 provider `resultCode='00'`임을 확인했다.
  실제 pagination 값이 JSON number가 아니라 decimal string인 반면 client가 `int`만
  허용한 것이 `API_ENVELOPE_INVALID`의 원인이었다. ASCII decimal string만 제한적으로
  허용하고 page timeout을 10초에서 20초로 조정했다.
- actual full refresh는 raw 12,011행을 모두 검증해 rejected `0`, 17개 시도교육청,
  invalid coordinate `0`을 확인했다. acquisition
  `2d9809e6-f732-42aa-847f-b9b946fb8bc7`, publication
  `09f11e92-f79a-4cfd-bdd3-87cc9329d1f2`, datasetVersion
  `2026-03-20-b148752f1e38`로 게시했다.
- 첫 publish에서 session refresh lock과 transaction publication lock이 같은 advisory
  key를 사용해 self-deadlock이 발생했다. `refresh:` namespace를 분리하고 validation 직후
  interruption된 acquisition을 checksum 재수집에서 resume하는 회귀 경로를 추가했다.
- raw S3 object는 7,254,385 bytes와 version ID를 확인했고 active pointer, snapshot,
  typed projection이 각각 정확히 한 publication과 12,011행을 가리킨다.
- 두 번째 actual refresh도 13페이지·12,011행·rejected `0`으로 `Pass`했다. 동일 raw
  checksum이라 acquisition `2d9809e6-f732-42aa-847f-b9b946fb8bc7`과 publication
  `09f11e92-f79a-4cfd-bdd3-87cc9329d1f2`를 idempotent 재사용했고 새 publication은
  생성하지 않았다. raw bytes가 달라지고 normalized rows만 같을 때 사용하는 semantic
  `NoChange`와 달리, byte-identical 재수집은 기존 `Pass`를 반환하는 계약이다.
- test engine 기반 signed JWT JSON/SSE E2E는 통과했지만 실제 학교 observation과
  LLM을 사용하는 live golden, 대표 공간 query 20회 p95 측정은 미완료다.

## 잔여 위험

- provider page latency가 길어 전체 13페이지 수집이 수 분 걸릴 수 있다.
- 실제 provider schema나 page total이 contract와 다르면 게시가 차단된다.
- 실제 readiness `Pass` 전까지 `HOME_AI_ENABLED_REFERENCE_CAPABILITIES`는 blank이며
  `school_location`을 운영에서 활성화하지 않는다.
- 기존 Docker volume과 생성된 MinIO volume은 삭제하거나 초기화하지 않았다.

## 계약 영향

`api-contract: compatible` — 기존 공개 챗봇 JSON/SSE URL, request/response field,
SSE error shape를 유지한다.

## 보안 영향

S3 raw는 private·versioned·object-lock 대상으로 두고 runtime에 credential을 전달하지
않는다. 로컬 MinIO root와 importer credential을 분리했으며 importer policy에는
`s3:GetObject`, `s3:PutObject`만 두고 삭제 권한을 주지 않았다. MinIO server/init은
전체 AI env 파일 대신 각자 필요한 secret만 명시적으로 받는다.

검증 범위: local secret 전달 경계, DB role bootstrap, MinIO private/versioning/
Object Lock/importer policy, provider 실패 출력과 Docker volume 보존을 확인했다.
AWS SSE-KMS/IAM/EventBridge IaC는 후속 Slice 범위로 아직 구현되지 않았다.

security-audit: 지적사항 = none

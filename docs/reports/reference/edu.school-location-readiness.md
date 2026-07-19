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
- 실제 서명 JWT JSON/SSE, 잘못된 issuer `401`, property route 회귀 통과
- local refresh wrapper의 secret 비노출과
  `MinIO → migration → importer` 실행 순서 테스트 통과

## 검증 공백

- no-argument chatbot runner가 runtime password에서 reference DSN을 재파생하도록
  복구했으며 실제 AI DB bootstrap과 chatbot health는 통과했다.
- 실제 전국 provider import, 10,000~50,000행, rejected `0`, 17개 교육청 실데이터
  coverage, 두 번째 `Pass|NoChange` 실행 근거가 없다.
- `apps/ai/.env`에 `AWS_ACCESS_KEY_ID`가 정확히 한 번 정의되지 않아 refresh가
  provider 호출과 migration 전에 exit `2`로 중단됐다. MinIO
  bucket/versioning/object-lock smoke도 이 지점에서 미완료다.
- test engine 기반 signed JWT JSON/SSE E2E는 통과했지만 실제 학교 observation과
  LLM을 사용하는 live golden, 대표 공간 query 20회 p95 측정은 미완료다.

## 잔여 위험

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

security-audit: 지적사항 = listed

- 실제 MinIO 전용 user/policy smoke와 AWS SSE-KMS/IAM/EventBridge IaC는 아직 없다.
- 실제 role credential permission smoke가 미완료다.

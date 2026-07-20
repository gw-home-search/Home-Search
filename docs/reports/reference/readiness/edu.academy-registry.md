# edu.academy-registry readiness

- 상태: `Partial` (`3.0/10`)
- 구현 품질: `Pass` (`10.0/10`)
- 통과: NEIS source별 이용조건·private raw 저장·내부 파생 승인 fingerprint, fake transport 17개 교육청 pagination, safe incomplete bundle, typed aggregate projection
- 중단: 실제 acquisition·S3 복구·row/freshness·chatbot golden 미검증
- 활성화: 금지
- 잔여 위험: 실제 NEIS schema와 fixture 차이, 운영 DB role·전국 집계·live golden 미검증

2026-07-20 live preflight: `API_SERVER_ERROR`, acquisition `0`, active publication 없음.

property DB의 시도·시군구 ancestor를 먼저 해석하고 AI DB를 별도 exact query하는
observer는 offline 검증을 통과했다. 이용조건 승인으로 readiness 1점을 추가했지만
실제 provider·S3·운영 DB·chatbot 검증 전에는 활성화하지 않는다.

`security-audit: 지적사항 = none`

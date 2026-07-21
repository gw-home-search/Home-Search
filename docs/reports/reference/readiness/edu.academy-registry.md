# edu.academy-registry readiness

- 상태: `Partial` (`8.0/10`)
- 구현 품질: `Pass` (`10.0/10`)
- 통과: 이용조건 fingerprint, 17개 교육청·146 pages 완전 수집, raw/accepted
  `138,412`, rejected·duplicate fact ID `0`, versioned S3 raw byte 복구와 SHA-256·length
  일치, typed aggregate projection과 active pointer, 두 번째 수집 `NoChange`, runtime
  exact-region query 20회 p95 `120.906ms`
- 중단: live JSON/signed JWT SSE golden과 active pointer rollback 검증이 남음
- 활성화: 금지
- 잔여 위험: live chatbot grounding과 rollback 증거가 없으므로 capability allowlist에
  추가하지 않음

2026-07-20 active datasetVersion은 `20260720-3bb7d33261d5`다. raw object의 지정
version을 MinIO에서 다시 스트리밍 복구해 byte length `101,792,940`과 DB SHA-256
`c2df59f229793e8dcd78c5e125b0f0867a061792f5b9e208a180c21b5fa80fca`가 일치했다.
같은 날 두 번째 full refresh는 normalized checksum이 같아 `NoChange`였고 새 staging과
publication을 만들지 않았다.

property DB의 시도·시군구 ancestor를 먼저 해석하고 AI DB를 별도 exact query하는
observer는 offline 검증을 통과했다. 실제 provider·S3·운영 DB 검증은 완료했지만 남은
readiness 항목을 통과하기 전에는 활성화하지 않는다.

`security-audit: 지적사항 = none`

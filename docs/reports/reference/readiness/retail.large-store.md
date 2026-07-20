# retail.large-store readiness

- 상태: `Partial` (`3.0/10`)
- 구현 품질: `Pass` (`9.5/10`)
- 통과: 공식 direct download·same-host Referer·공식 metadata date fallback, allowlisted one-hop streaming, EPSG:5174 projection, 1km 경계 fixture
- 중단: dataset별 이용허락 값 공란, 폐쇄된 LOCALDATA 본 사이트와 잔존 file endpoint의 운영 관계 미확정, freshness를 넘긴 `2025-11-27` metadata·전체 snapshot·S3 복구·전국 coverage 미검증
- 안전 제한: provider 행정코드와 property 법정동 코드 매핑 전 `verifiedZero=false`
- 활성화: 금지

2026-07-20 live preflight: `CONFIGURATION_INVALID`, provider body 요청 없음.

행정코드 mapping이 검증되기 전에는 query 결과가 0건이어도 `verifiedZero=false`를
강제한다. 구현 점수의 `0.5` 감점은 이용조건 승인 증거 부재에만 적용하며 실제 데이터
readiness와 활성화 상태는 올리지 않는다.

blocker evidence SHA-256:
`4efb46f84a1dbf11f27eb8ad84d60b4c88b453d1c1083d7f1ba9c226940b40b2`

`security-audit: 지적사항 = none`

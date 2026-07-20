# retail.large-store readiness

- 상태: `Partial` (`3.0/10`)
- 구현 품질: `Pass` (`9.5/10`)
- 통과: allowlisted one-hop streaming download, verified filename date, EPSG:5174 projection, 1km 경계 fixture
- 중단: 이용조건 승인·실제 snapshot·S3 복구·전국 coverage 미검증
- 안전 제한: provider 행정코드와 property 법정동 코드 매핑 전 `verifiedZero=false`
- 활성화: 금지

행정코드 mapping이 검증되기 전에는 query 결과가 0건이어도 `verifiedZero=false`를
강제한다. 구현 점수의 `0.5` 감점은 이용조건 승인 증거 부재에만 적용하며 실제 데이터
readiness와 활성화 상태는 올리지 않는다.

`security-audit: 지적사항 = none`

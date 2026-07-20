# retail.large-store readiness

- 상태: `Partial` (`2.0/10`)
- 구현 품질: `Pass` (`10.0/10`)
- 통과: 새 행정안전부 OpenAPI의 source별 `이용허락범위 제한 없음`, 일간·2일 전 현행화 계약, bounded 100행 pagination, raw JSON page 보존, 전화번호 비투영, EPSG:5174 projection, 1km 경계 fixture
- 중단: dataset `15154948` 활용신청 미반영으로 `API_AUTHENTICATION_FAILED`; 전체 snapshot·S3 복구·전국 coverage 미검증
- 안전 제한: provider 행정코드와 property 법정동 코드 매핑 전 `verifiedZero=false`
- 활성화: 금지

2026-07-20 live refresh: DB role·MinIO·migration preflight 후 첫 page
`API_AUTHENTICATION_FAILED`, acquisition `0`, provider body·key 비노출. 같은 key가 Sbiz
인증 단계를 통과했으므로 key 문자열 오류보다 새 API 활용신청 미반영으로 판단한다.

행정코드 mapping이 검증되기 전에는 query 결과가 0건이어도 `verifiedZero=false`를
강제한다. API 전환은 내부 acquisition 계약만 변경하며 public JSON/SSE field는
변경하지 않는다. 실제 데이터 readiness와 활성화 상태는 올리지 않는다.

license evidence SHA-256:
`02bce96a68d777801a4e5ac4cfe71c3e8d9d958ef20eb8a8eb362dc21e6fe2d4`

`security-audit: 지적사항 = none`

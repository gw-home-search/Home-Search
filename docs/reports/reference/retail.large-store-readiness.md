# `retail.large-store` readiness

기준일: 2026-07-19
상태: `Partial`

## 검증 근거 확인

- 공식 contract URL과 실제 file host/prefix를 tracked config에 등록했다.
- UTF-8 고정 CSV, provider status·업태 allowlist, EPSG:5174 원좌표 보존과
  EPSG:4326 변환, 대한민국 범위 검사를 구현했다.
- 좌표가 없는 유효 row는 `registry_fact`, 좌표 row는 `facility_point`로 분리한다.
- PostGIS `ST_DWithin` 정확한 1,000m 경계, OPEN filter, coverage 기반
  `verifiedZero`, 기본 1,000m·최대 3,000m 테스트가 통과했다.
- `retail_location` 내부 grounding은 생활권·상권·투자가치 평가와 미관측 시설명을
  차단한다.

## 검증 공백 / 잔여 위험

- 이용조건은 `PENDING`이다. provider 호출 전에 exit `2`로 중단한다.
- 실제 2,000~10,000행 import, 전국 95%·지역 90% 좌표 coverage, p95 측정,
  live golden이 없다.
- property 법정동 code와 provider 개방자치단체 code의 검증된 시군구 mapping이
  아직 없으므로 운영 0건 확정 표현을 활성화할 수 없다.
- 운영 reference allowlist에는 `retail_location`을 추가하지 않았다.

## 계약·보안 영향

`api-contract: compatible`. 공개 URL·field shape 변경 없음.

security-audit: 지적사항 = listed

- 실제 source 이용조건, region mapping, role permission, S3 복구 smoke가 미완료다.

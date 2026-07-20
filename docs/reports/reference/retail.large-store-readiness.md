# `retail.large-store` readiness

기준일: 2026-07-20
상태: `Partial`
구현 품질: `9.5/10 Pass`
실제 데이터 readiness: `3.0/10 Partial`

## 검증 근거 확인

- 공식 LOCALDATA 안내 페이지가 명시한 download path와 same-host `Referer`를 tracked
  config에 등록했다.
- 2026-07-20 body 수신 전 header preflight에서 1,183,334 bytes, `text/csv`, 날짜 없는
  filename을 확인했다. source date는 공공데이터포털 공식 파일 metadata
  `2025-11-27`만 사용하고 현재 시각으로 대체하지 않는다.
- 공공데이터포털 dataset 상세의 이용허락 값은 비어 있고, 파일 metadata는
  `2025-11-27`인 반면 “매일 갱신, 2일 전 기준 현행화”라고 표시되어 있다.
  LOCALDATA 본 사이트는 2026-04-16 폐쇄 안내로 전환됐지만 공공데이터포털은 여전히
  `file.localdata.go.kr` landing을 공식 URL로 게시한다.
- UTF-8 고정 CSV, provider status·업태 allowlist, EPSG:5174 원좌표 보존과
  EPSG:4326 변환, 대한민국 범위 검사를 구현했다.
- 좌표가 없는 유효 row는 `registry_fact`, 좌표 row는 `facility_point`로 분리한다.
- PostGIS `ST_DWithin` 정확한 1,000m 경계, OPEN filter, coverage 기반
  `verifiedZero`, 기본 1,000m·최대 3,000m 테스트가 통과했다.
- `retail_location` 내부 grounding은 생활권·상권·투자가치 평가와 미관측 시설명을
  차단한다.

## 검증 공백 / 잔여 위험

- 이용조건은 source별 허락 표시가 비어 있어 `PENDING`이다. provider body 호출 전에
  exit `2`로 중단한다.
- tracked source date `2025-11-27`은 40일 freshness를 초과하므로 공식 metadata가
  갱신되기 전 publication을 차단한다.
- 실제 2,000~10,000행 import, 전국 95%·지역 90% 좌표 coverage, p95 측정,
  live golden이 없다.
- property 법정동 code와 provider 개방자치단체 code의 검증된 시군구 mapping이
  아직 없으므로 운영 0건 확정 표현을 활성화할 수 없다.
- 운영 reference allowlist에는 `retail_location`을 추가하지 않았다.

## 구현 품질 평가

| 항목 | 점수 | 검증 근거 확인 |
|---|---:|---|
| 범위·최소성 | `1.0/1.0` | CSV collector·adapter·typed projection·기존 retail observer만 정적 합성했고 mapping table·scheduler를 추가하지 않음 |
| 공개·내부 계약 | `1.0/1.0` | fixed download path·same-host Referer·official metadata date, UTF-8 CSV, additive `retail_location` 내부 capability와 기존 JSON/SSE field 호환 검증 |
| 이용조건·출처 | `0.5/1.0` | 공식 landing/file URL과 A등급 source 후보는 고정했으나 이용허락·private raw 저장 fingerprint는 `PENDING` |
| 데이터 정확성·원자성 | `1.5/1.5` | verified raw-first, 관리번호 dedupe, status·업태 allowlist, EPSG:5174→4326, 대한민국 범위, typed projection 후 pointer 전환 검증 |
| 보안·개인정보 | `1.0/1.0` | HTTPS allowlist·same-host no-query Referer·one-hop redirect·owner-only temp·runtime read view만 허용하고 검증 전 `verifiedZero=false` 유지 |
| 실패·복구·관측 | `1.0/1.0` | media/length/official metadata date/size/redirect safe reason, partial file 정리, freshness 차단, refresh audit, publication rollback 경계 검증 |
| 테스트 품질 | `1.5/1.5` | collector·adapter·ingest·PostGIS 1km·grounding·composition 집중 테스트 `103 passed`; 전체 AI `554 passed`, coverage `90.18%` |
| 문서·운영 가능성 | `1.0/1.0` | source AsciiDoc, generated download/header/column/failure snippet, generic refresh·status·audit runbook 일치 |
| 성능·자원 제한 | `0.5/0.5` | 1MiB chunk download, 256MiB file limit, 2,000..10,000 row contract, 3초 runtime query timeout과 최대 5건 제한 검증 |
| 리뷰·commit 추적성 | `0.5/0.5` | file streaming·projection composition·observer 회귀 commit과 mapping 잔여 위험을 분리 기록 |

구현 점수는 `9.5/10 Pass`다. 이용조건 승인 증거 부재에만 `0.5`를 감점했고,
계약·데이터 정확성·보안·테스트 필수 항목은 감점하지 않았다. 행정코드 mapping이
검증되지 않은 동안 정상 0건을 주장하지 않는 것이 구현 인수 조건이며, 이 점수는
실제 import나 capability 활성화를 승인하지 않는다.

검증 명령:

```bash
cd apps/ai
TESTCONTAINERS_RYUK_DISABLED=true uv run pytest --no-cov \
  tests/datasets/test_file_snapshot_client.py \
  tests/datasets/test_reference_contract_config.py \
  tests/datasets/test_large_store_adapter.py \
  tests/datasets/test_large_store_ingest.py \
  tests/property_chat/test_reference_facility_policy.py \
  tests/property_chat/test_retail_grounded_engine.py \
  tests/property_chat/test_openai_responses_language_model.py \
  tests/datasets/test_reference_refresh.py \
  tests/datasets/test_projection_composition.py
# 103 passed
```

전체 coverage 근거는 `priority-reference-offline-verification.md`의 `554 passed`,
`90.18%` 결과다.

## 계약·보안 영향

`api-contract: compatible`. 공개 URL·field shape 변경 없음.

security-audit: 지적사항 = none

잔여 위험은 실제 source 이용조건, region mapping, 운영 role permission, S3 복구와
live golden을 아직 검증하지 않은 readiness 공백이다.

preflight blocker evidence는
`apps/ai/config/preflight_evidence/retail.large-store.txt`에 고정했고 SHA-256은
`4efb46f84a1dbf11f27eb8ad84d60b4c88b453d1c1083d7f1ba9c226940b40b2`다.

TDD 근거는 안내 HTML을 CSV로 취급하는 기존 URL, 날짜 없는 download filename,
Referer 미전달을 확인한 4개 최초 RED와 exact download·tracked source date·same-host
Referer 최소 GREEN이다. file contract·snapshot·ingest 좁은 회귀는 `29 passed`다.

2026-07-20 live preflight는 license `PENDING`을 `CONFIGURATION_INVALID`로 반환해
provider body 요청 전 exit `2` 중단했다.

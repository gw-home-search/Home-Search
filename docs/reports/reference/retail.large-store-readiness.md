# `retail.large-store` readiness

기준일: 2026-07-20

상태: `Partial`

구현 품질: `10.0/10 Pass`

실제 데이터 readiness: `2.0/10 Partial`

## 검증 근거 확인

- 기존 LOCALDATA CSV는 이용허락 공란과 stale `2025-11-27` metadata 때문에 운영
  acquisition에서 제외했다.
- 새 공식 `행정안전부_생활_대규모점포 조회서비스`는 source 상세에
  `이용허락범위 제한 없음`, 일간 갱신, 2일 전 기준 현행화, 자동승인을 명시한다.
- official Swagger의 `GET /info`, `pageNo`, 최대 `numOfRows=100`, `totalCount`,
  `items.item` 및 provider field를 fixture contract로 고정했다.
- page JSON은 키가 없는 endpoint identity와 함께 verified raw bundle에 그대로
  보존한다. 전화번호 `TELNO`는 normalized row와 projection에 포함하지 않는다.
- release date가 없으므로 수집 시작 UTC를 `OBSERVED_AT`으로 사용하며
  normalization schema는 `large-store-v2`로 분리했다.
- provider status·업태 allowlist, EPSG:5174 원좌표 보존과 EPSG:4326 변환,
  대한민국 범위, typed projection, PostGIS 1,000m 경계를 유지한다.
- 첫 page 실패는 raw를 만들지 않고 중간 실패는 safe reason의 incomplete bundle만
  보존한다. total/page 불일치와 100 page·256MiB 초과는 fail-closed다.

## live 결과와 잔여 위험

- 2026-07-20 새 API live refresh는 DB role·MinIO·migration preflight 후 첫 page에서
  `API_AUTHENTICATION_FAILED`로 중단했다. acquisition은 `0`이고 provider body·키는
  저장하거나 출력하지 않았다.
- 같은 `HOME_AI_DATA_GO_KR_SERVICE_KEY`가 Sbiz taxonomy endpoint 인증을 통과했으므로
  키 문자열 자체보다 dataset `15154948` 활용신청 미반영 가능성이 높다.
- 활용신청 승인 후 full pagination, 필수 ID·중복, row count·freshness, 전국 95%·지역
  90% 좌표 coverage, S3 byte 복구, 두 번째 `NoChange`, p95, JSON/SSE golden이 남아 있다.
- property 법정동 code와 provider 개방자치단체 code mapping 전에는
  `verifiedZero=false`를 유지한다.
- 운영 reference allowlist에는 `retail_location`을 추가하지 않았다.

## 구현 품질 평가

| 항목 | 점수 | 검증 근거 확인 |
|---|---:|---|
| 범위·최소성 | `1.0/1.0` | file path를 새 공식 API collector로만 교체, scheduler·mapping table 없음 |
| 공개·내부 계약 | `1.0/1.0` | 내부 acquisition만 변경, 공개 JSON/SSE URL·field shape 유지 |
| 이용조건·출처 | `1.0/1.0` | source별 무제한 이용허락 evidence와 attribution SHA-256 고정 |
| 데이터 정확성·원자성 | `1.5/1.5` | raw JSON first, pagination·total, dedupe, OBSERVED_AT checksum, atomic pointer |
| 보안·개인정보 | `1.0/1.0` | HTTPS fixed host/path, key·provider body 비노출, 전화번호 비투영, owner-only temp |
| 실패·복구·관측 | `1.0/1.0` | first/middle failure, incomplete, safe audit, 기존 active pointer 보존 |
| 테스트 품질 | `1.5/1.5` | 최초 RED 후 client·adapter·ingest·ops·전체 AI `564 passed`, coverage `90.10%` |
| 문서·운영 가능성 | `1.0/1.0` | source AsciiDoc·generated API snippets·wrapper 일치 |
| 성능·자원 제한 | `0.5/0.5` | 100행/page, 최대 100 pages, 4MiB/page, 256MiB bundle |
| 리뷰·commit 추적성 | `0.5/0.5` | API 전환 slice와 live activation을 분리 |

license evidence:
`apps/ai/config/license_evidence/retail.large-store.txt`

SHA-256:
`02bce96a68d777801a4e5ac4cfe71c3e8d9d958ef20eb8a8eb362dc21e6fe2d4`

검증:

```text
focused client/adapter/ingest/config/CLI: 35 passed
local reference refresh wrapper: Pass
reference docs deterministic HTML/secret check: Pass
AI full: 564 passed, coverage 90.10%
live audit: API_AUTHENTICATION_FAILED, acquisition 0
```

`api-contract: compatible`

security-audit: 지적사항 = none

# 생활 인프라 priority reference 품질 ledger

## 목표와 범위

`edu.academy-registry`, `place.sbiz-academy`, `retail.large-store`,
`transport.rail-station`을 raw-first lifecycle에 연결하고, offline 구현 점수와
실제 데이터 readiness 점수를 분리해 추적한다. scheduler, AWS 배포, 공개 API
추가, 지도 marker, fuzzy match는 범위 밖이다.

공개 계약 영향은 기존 JSON/SSE field 변경 없이
`evidenceSummary.capabilities[]`에 승인된 capability 문자열이 추가될 수 있는
`compatible`이다. readiness가 `9.0/10` 미만이면 runtime allowlist에 넣지 않는다.

## Capability 경계

| Capability | 근거 | 허용 주장 | 금지 주장 |
|---|---|---|---|
| `academy_registry_summary` | NEIS 공식 등록 원장의 시도교육청+시군구 집계 | 등록 총수, 운영 수, 관측일 | 반경, 거리, 교육 품질 |
| `academy_lookup` | Sbiz 교육업소 point, exact match 성공 시 NEIS 보조 | 지정 반경의 교육업소 위치 | Sbiz 수를 공식 등록 학원 수로 표현 |
| `retail_location` | 대규모점포 point snapshot | 업소명, 허용 업태, 직선거리 | 상권 품질, 투자 가치, 검증되지 않은 행정코드 0건 |
| `rail_station_lookup` | 도시철도 노선 occurrence snapshot | 역명, 노선, 환승, 직선거리 | 통근시간, 배차, 혼잡도 |

## 이용조건 preflight (2026-07-20)

공공데이터포털 공통 이용정책은 dataset별 공공누리 유형과 제3자 권리를 별도로
확인하도록 요구한다. 따라서 한 dataset의 활용승인이나 포털 공통정책을 다른
dataset의 private raw 저장·가공 승인으로 확대하지 않는다.

| sourceId | 공식 확인 내용 | contract 상태 | activation blocker |
|---|---|---|---|
| `edu.academy-registry` | NEIS 기반 전국 학원·교습소, 수시 갱신 | `PENDING` | source별 이용허락·private raw 저장·내부 파생물 조건과 fingerprint 미확정 |
| `place.sbiz-academy` | 상권업종 247개 체계와 store ID 비연속 변경 공지 확인 | `PENDING` | 활용가이드, 실제 taxonomy artifact, 정확한 교육업종 allowlist 미확정 |
| `retail.large-store` | LOCALDATA CSV URL, EPSG:5174, 수시/2일 전 현행화 확인 | `PENDING` | 이용허락 표시와 private raw 저장·파생 조건 fingerprint 미확정 |
| `transport.rail-station` | KRIC landing, 연간 XLSX, 1,073행, 기준일 2024-12-31 확인 | `PENDING` | 고정된 실제 XLSX release URL과 이용조건 fingerprint 미확정 |

빈 `terms_fingerprint`는 미검토가 아니라 승인 근거가 없음을 뜻한다. 승인 전에는
collector를 실제 호출하지 않고 readiness를 `Partial`로 유지한다.

## 구현 품질 평가

각 slice는 아래 10점 평가표를 사용한다. 계약, 데이터 정확성, 보안, 테스트는
감점할 수 없는 필수 항목이다.

| 항목 | 배점 | 필수 근거 |
|---|---:|---|
| 범위·최소성 | 1.0 | 비범위 기능·dependency·동적 plugin 없음 |
| 공개·내부 계약 | 1.0 | URL/JSON/SSE 호환, CLI/source contract test |
| 이용조건·출처 | 1.0 | source별 fingerprint, attribution, 저장·가공 조건 |
| 데이터 정확성·원자성 | 1.5 | S3 verified raw-first, dedupe, checksum, atomic pointer |
| 보안·개인정보 | 1.0 | secret·전화·교습비·provider body 비노출 |
| 실패·복구·관측 | 1.0 | incomplete, safe reason, rollback, audit evidence |
| 테스트 품질 | 1.5 | First RED, boundary/failure, 전체 coverage 90% 이상 |
| 문서·운영 가능성 | 1.0 | AsciiDoc와 CLI runbook 일치 |
| 성능·자원 제한 | 0.5 | page/file/memory/query 제한 증거 |
| 리뷰·commit 추적성 | 0.5 | 책임 단위 diff, review와 잔여 위험 |

## 실제 데이터 readiness 평가

각 항목 1점, 총 `9.0/10` 이상이어야 활성화한다. 이용조건, acquisition complete,
필수 ID, S3 checksum, chatbot grounding은 반드시 만점이다.

1. 이용조건과 source contract 승인
2. pagination/release acquisition complete
3. schema·필수 ID·중복 0건
4. row count·freshness·증감률
5. 좌표·지역 coverage
6. S3 object·raw checksum 복구
7. typed projection·active pointer
8. query 성능·반경 경계
9. fact·citation·JSON/SSE golden
10. status/audit·rollback·두 번째 수집

## 현재 ledger

| Slice | 구현 상태 | 구현 점수 | readiness | 활성화 |
|---|---|---:|---:|---|
| G0 계약·ledger | `Pass` | `9.0/10` | 해당 없음 | 해당 없음 |
| F1 verified raw streaming | `Partial` | 미채점 | 해당 없음 | 해당 없음 |
| F2 normalized spool·semantic `NoChange` | `Partial` | 미채점 | 해당 없음 | 해당 없음 |
| F3 static composition·CLI | `Partial` | 미채점 | 해당 없음 | 해당 없음 |
| D1 AsciiDoc artifact | `Pass` | `9.0/10` | 해당 없음 | 해당 없음 |
| NEIS | collector·projection offline `Pass`, observer·live 미완료 | 미채점 | `2.0/10 Partial` | 금지 |
| Sbiz | collector·adapter·exact projection offline `Pass`, taxonomy 미승인 | 미채점 | `2.0/10 Partial` | 금지 |
| 대규모점포 | streaming file client·기존 projection `Pass`, live 미실행 | 미채점 | `3.0/10 Partial` | 금지 |
| 철도 | fixed URL preflight·occurrence projection·merge `Pass`, URL 미확정 | 미채점 | `2.0/10 Partial` | 금지 |

상세 readiness 근거는 `docs/reports/reference/readiness/`에 source별로 기록한다.
2026-07-20 offline 회귀는 `460 passed`, coverage `90.42%`이며 실제 provider,
운영 DB, S3 live 검증은 실행하지 않았다.

## 공통 중단·롤백

- 중단: public field 변경, DB 간 SQL join, S3 checksum 불일치, 필수 ID 중복,
  coverage 기준 미달, 안정적인 rail release URL 부재, 이용조건 불명확.
- 롤백: capability allowlist 제거와 이전 publication pointer 전환만 사용한다.
- raw object, publication, quarantine, readiness evidence, Docker volume은 삭제하지 않는다.

`api-contract: compatible`

`security-audit: 지적사항 = none`은 최종 diff 보안 검토 뒤에만 확정한다.

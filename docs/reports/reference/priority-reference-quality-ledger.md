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
| `edu.academy-registry` | NEIS 상세 페이지의 `이용 허락 범위 제한없음`, provider·주기·attribution evidence SHA-256 고정 | `APPROVED` | acquisition·S3·전국 138,412행·p95 통과; chatbot golden·pointer rollback 미검증 |
| `place.sbiz-academy` | API의 무제한 이용 허락, 국세청/카드사 provenance·제3자 정책, private raw·내부 파생 evidence SHA-256 고정 | `APPROVED` | 필수 readiness 통과; `academy_lookup` 활성화 |
| `retail.large-store` | 공식 fileData 상세의 `이용허락범위 제한 없음`, 일간·2일 전 현행화, private raw·내부 파생 evidence SHA-256 고정 | `APPROVED` | 좌표 83.7404%와 live chatbot golden 실패로 activation 차단 |
| `transport.rail-station` | fileData `15093755`의 `이용허락범위 제한 없음`과 프로젝트 책임자가 보고한 KRIC 전화 승인; 서면 transcript 부재를 evidence에 명시 | `APPROVED` | 필수 readiness 통과; `rail_station_lookup` 활성화 |

빈 `terms_fingerprint`는 미검토가 아니라 승인 근거가 없음을 뜻한다. 철도는 공식
fileData 이용허락과 프로젝트 책임자의 KRIC 전화 승인 진술을 fingerprint로 고정했다.
서면 기록이 없다는 잔여 위험은 수용하되 약관 변경 시 재검토한다. 승인된 source도
live 필수 항목이 남으면 readiness를 `Partial`로 유지한다.

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
| F1 verified raw streaming | `Pass` | `9.5/10` | 해당 없음 | 해당 없음 |
| F2 normalized spool·semantic `NoChange` | `Pass` | `9.0/10` | 해당 없음 | 해당 없음 |
| F3 static composition·CLI | `Pass` | `9.5/10` | 해당 없음 | 해당 없음 |
| D1 AsciiDoc artifact | `Pass` | `9.0/10` | 해당 없음 | 해당 없음 |
| NEIS | source 이용조건·collector·projection·summary observer·전국 live 수집 `Pass` | `10.0/10` | `8.0/10 Partial` | 금지 |
| Sbiz S2 collector | 공식 taxonomy 18 partition·191,250행·S3 복구·`NoChange` `Pass` | `10.0/10` | `10.0/10 Pass` | 활성화 |
| Sbiz S2-P grounded location | exact projection·Sbiz B+NEIS A signed JWT JSON/SSE `Pass` | `10.0/10` | `10.0/10 Pass` | 활성화 |
| 대규모점포 | 공식 CSV 4,176행·S3 복구·`NoChange`·runtime composition `Pass` | `10.0/10` | `8.0/10 Partial` | coverage·golden으로 금지 |
| 철도 S4/S4-P | fixed XLSX·1,097 occurrence·좌표 100%·S3·병합·signed JWT JSON/SSE `Pass` | `9.5/10` | `10.0/10 Pass` | 활성화 |
| Offline priority integration | AI·PostGIS·MinIO·property-data·chat-bff·JWT·Compose `Pass` | `9.5/10` | 해당 없음 | 해당 없음 |

상세 readiness 근거는 `docs/reports/reference/readiness/`에 source별로 기록한다.
F1은 file source뿐 아니라 NEIS·Sbiz API page도 owner-only temp file에 순차 기록하고,
complete·incomplete deterministic bundle을 verified S3 file upload에 연결했다. 기존
bytes collector API는 characterization wrapper로만 유지한다. NEIS·Sbiz adapter는
ZIP manifest를 먼저 제한 검증하고 최대 8MiB artifact를 하나씩 checksum 검증·parse해
complete bundle 전체 read를 제거했다. 대규모점포 CSV와 철도 XLSX adapter도
prepared bundle과 artifact를 bytes로 재적재하지 않고 owner-only temp file로 1MiB씩
추출하면서 길이와 SHA-256을 검증한다. 9MiB artifact streaming fixture, unsafe target,
manifest·checksum 실패 경계를 통과했으며 기존 bytes API는 회귀 호환 wrapper로만
유지한다. 1GiB raw fixture를 실제 할당하지 않은 성능 증거 공백으로 `0.5`를 감점해
F1을 `9.5/10 Pass`로 평가했다. F2는
lazy `ParsedRow`, stream 중 parse failure rollback,
NEIS·Sbiz normalized iterator, 30만 행 `<256MiB` peak memory gate를 통과했다.
F3는 5개 source refresher와 feature-local projection writer를 고정 catalog로 합성하고,
repository transaction 안에서 projection 이후에만 active pointer를 전환한다. generic
CLI와 local wrapper는 source별 provider key만 선택하며 철도 XLSX와 대규모점포 CSV는 provider key 없이
실행된다. `--family priority`는 고정 순서와 source 실패 후 계속
실행을 유지하고 status/audit는 runtime read-only role과 3초 statement timeout을
사용한다. 실제 provider 실행은 readiness 단계로 남아 `0.5`를 감점해 `9.5/10 Pass`로
평가했다.
철도 file source는 계약상 최대 1만 행 이하로 제한된다. 철도 refresher는 고정 KRIC download
endpoint와 non-secret exact query, 공식 XLSX response media type,
owner-only temp, verified S3 file upload, safe refresh-run 실패 기록을 static catalog에
연결했으며 landing URL은 network 호출 전에 거부한다. 2026-07-20 전체 offline
회귀는 최신 기준 `630 passed`, coverage `90.03%`다. NEIS summary observer는 property DB의
시도·시군구 ancestor를 먼저 해석한 뒤 AI DB를 별도 exact query하며, 반경·거리 표현을
grounding 단계에서 거부한다. source 이용조건은 승인했지만 live readiness가
미승인이므로 runtime allowlist에는 추가하지 않았다. offline checkpoint에서는 실제
provider, 운영 DB, S3 live 검증을 실행하지 않았다.

후속 live preflight는 최초 key 설정 실패, NEIS `API_SERVER_ERROR`, Sbiz
`TAXONOMY_CHANGED`, retail `API_AUTHENTICATION_FAILED`, rail의 승인 전 `PENDING` 차단을
확인했다. 이후 Sbiz tracked taxonomy, retail official file, rail v5를 통해 세 source의
local publication과 재수집까지 완료했다. 철도는 프로젝트 책임자의 전화 승인 진술로 계약을 승인했다. additive migration `0010`과 runtime-only local
inspection wrapper로 acquisition 이전 실패도 0 counts·safe reason code로 audit한다.
상세 결과는 `priority-reference-live-preflight.md`에 기록했다.

NEIS 구현 점수는 source별 이용조건 evidence SHA-256, private raw·내부 파생 승인,
17개 교육청 pagination·total·coverage, verified raw-first, incomplete 보존, 개인정보
비투영, exact 지역 집계, A등급 grounding, 고정 자원 제한과 회귀 근거를 충족해
`10.0/10`으로 평가했다. 실제 key·provider·S3·운영 DB·chatbot 검증은 남아 실제
데이터 readiness는 `8.0/10 Partial`이고 activation은 금지한다.

Sbiz legacy taxonomy endpoint의 대·중·소 `25/266/1,255` 응답은 공식 포털 artifact
`10/75/247`과 달라 현행 계약으로 사용하지 않는다. collector는 checksum 고정된 공식
taxonomy 3개 artifact를 acquisition에 포함하고 실제 18개 store partition의 code/name을
allowlist와 exact 비교한다. 새 학교·온라인·인적용역 분류를 교육 위치 allowlist로
임의 편입하지 않는다.

Sbiz `academy_lookup` observer는 기본 800m, 명시 범위 100..2,000m, 최대 5건과
전국 좌표 coverage 95%를 fail-closed로 적용한다. Sbiz 위치는 B등급이며 NFKC 상호명,
canonical 도로명주소, 선택적 우편번호가 모두 exact match할 때만 NEIS A등급 근거를
추가한다. fuzzy match와 공식 등록 수 표현은 grounding 단계에서 거부한다. 검증된
행정코드 mapping이 없어 정상 0건은 확정하지 않는다. 2026-07-21 activation은
`academy_lookup`만 runtime template에 추가하고 빈 값을 rollback으로 유지한다.

Sbiz S2 collector 구현 점수는 source별 무제한 이용 허락과 제3자 provenance evidence,
공식 OAS endpoint·field fixture, 현행 taxonomy code/name exact preflight, 18개
allowlisted partition, verified raw-first, pagination·total·중복 ID 차단, safe
incomplete와 개인정보 비투영 근거를 충족해 `10.0/10`으로 평가했다. 실제 201 pages,
191,250행·좌표 100%를 게시하고 두 번째 수집 `NoChange`, staging 0과 S3 byte 복구를
확인했다. 800m query와 signed JWT JSON/SSE dual citation을 통과했고, nearest 5건과
해당 ID의 exact evidence만 분리 조회해 최대 2km query p95 `156.927ms`를 달성했다.
readiness는 `10.0/10 Pass`다.

Sbiz S2-P 구현 점수는 이용조건·범위·공개/내부 계약·데이터 정확성·보안·실패 처리·
테스트·문서·bounded query·리뷰 근거를 충족해 `10.0/10`으로 평가했다. 이는 실제
데이터 readiness나 capability 활성화를 승인하지 않는다.

대규모점포 collector는 공식 fileData CSV의 fixed host/path·Referer, owner-only streaming,
CP949 schema, OBSERVED_AT semantic checksum, 전화번호 비투영, status·업태·EPSG:5174
계약과 typed projection을 검증했다. 실제 4,176행 게시·두 번째 `NoChange`·S3 복구는
통과했지만 좌표 coverage `83.7404%`가 전국 95% 기준에 미달했다. 1km
query 20회 p95 `57.356ms`와 3km p95 `53.259ms`를 통과했지만 live chatbot
golden도 실패해 readiness는 `8.0/10 Partial`이다.
행정코드 mapping 전에는 정상 0건을 주장하지 않는다.

Offline priority integration은 실제 PostGIS와 MinIO container, raw version byte 복구,
property-data fresh Flyway, chat-bff, signed JWT JSON/SSE, DB role, base+chatbot Compose를
통과했다. 1GiB raw와 21GiB ephemeral storage를 실제 할당하지 않은 성능·자원 증거
공백으로 `0.5`를 감점했다. 명령별 결과와 잔여 위험은
`priority-reference-offline-verification.md`에 기록했다.

철도 S4 구현 점수는 범위·계약·이용조건 승인 기록·raw-first 원자성·보안·실패 기록·
테스트·문서·리뷰 근거를 만점으로 평가했다. fake transport와 file size bound는
검증했지만 실제 최대 XLSX의 peak memory 측정은 live 단계 전까지 남아 성능 항목
`0.5`를 감점했다. 최신 KRIC exact header는 `rail-station-v2`로 분리했고, row 기준일을
nullable provenance로 바로잡은 normalization은 `rail-station-v3`, malformed provenance
날짜를 queryable warning으로 남기는 계약은 `rail-station-v4`로 분리했다. 동일 raw의
이전 `PARSE_FAILED`를 보존하면서 새 normalization schema만 재처리하도록 acquisition
dedupe를 `(source_id, checksum, normalization_schema_version)`으로 제한했다.
실제 duplicate pair를 재검토해 `line_name`을 occurrence identity에 포함하고 유일 최신
유효일만 유지하는 `rail-station-v5`를 추가했다. 1,097 occurrence·좌표 100%를 게시하고
동일 release 재수집의 acquisition/publication 재사용과 raw byte 복구를 확인했다.
1.5km query p95 `7.971ms`, 3km p95 `25.565ms`, live exact 역 병합과 signed JWT
JSON/SSE A등급 grounding을 통과해 readiness는 `10.0/10 Pass`다. 2026-07-21
`academy_lookup,rail_station_lookup` 누적 activation을 승인했고 rollback은
`academy_lookup`이다.

## 공통 중단·롤백

- 중단: public field 변경, DB 간 SQL join, S3 checksum 불일치, 필수 ID 중복,
  coverage 기준 미달, rail release URL 변경, 이용조건 불명확.
- 롤백: capability allowlist 제거와 이전 publication pointer 전환만 사용한다.
- raw object, publication, quarantine, readiness evidence, Docker volume은 삭제하지 않는다.

`api-contract: compatible`

`security-audit: 지적사항 = none`

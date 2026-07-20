# `edu.academy-registry` readiness

기준일: 2026-07-20
상태: `Partial`
구현 품질: `10.0/10 Pass`
실제 데이터 readiness: `8.0/10 Partial`

17개 시도교육청 page coverage와 교육청별 `list_total_count` 일치를 강제하는 NEIS
adapter를 구현했다. 전화번호와 교습비는 normalized projection 후보에서 제외하고,
상호명·도로명주소는 Unicode NFKC와 공백 canonicalization만 적용한다.
property DB에는 기존 `public.region` 계층을 그대로 읽는 `ai_read.region_fact` V10을
추가했고 `home_search_ai_reader` SELECT-only 검증을 통과했다.

offline `academy_registry_summary` observer는 property DB에서 단지의 `si-do`와
`si-gun-gu` ancestor를 해석한 뒤 AI DB에서 교육청명+시군구명을 exact query한다.
DB 간 join은 없고 등록 총수·운영 수·관측일만 A등급 NEIS fact로 노출하며 반경·거리
표현은 grounding validator가 거부한다. 같은 시군구명이 다른 교육청에 존재하는
fixture와 missing/stale region evidence의 fail-closed 경계를 검증했다.

NEIS source 상세 페이지의 `이용 허락 범위 제한없음` 표시를 2026-07-20에
검토하고, provider·source·허락 문구·attribution·검토 결정을 owner-controlled
evidence file과 SHA-256 fingerprint로 고정했다. private raw 저장과 내부 파생 가공만
승인했고 공개 재배포는 보수적으로 금지했다. 실제 전국 snapshot과 시군구 집계는
게시했지만 live chatbot golden·query p95·rollback 검증은 미완료다.
`HOME_AI_ENABLED_REFERENCE_CAPABILITIES` allowlist에는 추가하지 않아 운영 capability는
활성화하지 않았다.

## 구현 품질 평가

| 항목 | 점수 | 검증 근거 확인 |
|---|---:|---|
| 범위·최소성 | `1.0/1.0` | NEIS collector·adapter·projection·summary observer만 정적 합성했고 scheduler·동적 plugin을 추가하지 않음 |
| 공개·내부 계약 | `1.0/1.0` | `acaInsTiInfo`, 17개 교육청, `pSize=1000`, additive `academy_registry_summary` capability와 기존 JSON/SSE field 호환 검증 |
| 이용조건·출처 | `1.0/1.0` | NEIS source 상세 페이지의 무제한 이용 허락, private raw·내부 파생 승인, 보수적 공개 재배포 금지, attribution과 evidence SHA-256을 고정 |
| 데이터 정확성·원자성 | `1.5/1.5` | 17개 교육청 coverage, page·total 일치, unique ID, verified raw-first, rejected row 차단, transaction 안 projection 후 pointer 전환 검증 |
| 보안·개인정보 | `1.0/1.0` | key·전체 query·provider 오류 body 비보존, 전화번호·교습비 비투영, runtime base table 접근 거부 검증 |
| 실패·복구·관측 | `1.0/1.0` | 첫 page 실패는 acquisition 없이 safe reason만 기록하고, 중간 실패는 incomplete raw만 보존하며 active publication을 변경하지 않음 |
| 테스트 품질 | `1.5/1.5` | collector·adapter·ingest·PostGIS projection·observer·LLM contract 집중 테스트 `119 passed`; 전체 AI `554 passed`, coverage `90.18%` |
| 문서·운영 가능성 | `1.0/1.0` | source AsciiDoc, generated request/field/failure snippet, generic refresh·status·audit runbook 일치 |
| 성능·자원 제한 | `0.5/0.5` | page 8MiB, bundle 512MiB, office당 300 page, timeout 1..30초, retry 1회, secure file streaming 제한 검증 |
| 리뷰·commit 추적성 | `0.5/0.5` | collector·projection·observer·streaming·composition 책임 commit과 readiness 잔여 위험을 분리 기록 |

구현 점수는 `10.0/10 Pass`다. 계약·데이터 정확성·보안·테스트 필수 항목을
포함해 offline 구현 평가를 충족했다. 이 점수는 실제 acquisition·S3·DB·chatbot
readiness나 capability 활성화를 승인하지 않는다.

검증 명령:

```bash
cd apps/ai
TESTCONTAINERS_RYUK_DISABLED=true uv run pytest --no-cov \
  tests/datasets/test_academy_registry_client.py \
  tests/datasets/test_academy_registry_adapter.py \
  tests/datasets/test_academy_registry_ingest.py \
  tests/datasets/test_academy_registry_projection.py \
  tests/property_chat/test_academy_registry_postgres.py \
  tests/property_chat/test_academy_registry_grounded_engine.py \
  tests/property_chat/test_openai_responses_language_model.py \
  tests/test_language_model_composition.py
# 119 passed
```

최신 전체 coverage 근거는 `589 passed`, `90.05%`다. 부분 테스트만 coverage와 함께 실행하면 저장소 전역
`fail-under=90` 특성상 동작이 모두 통과해도 종료 코드가 실패하므로 점수 근거로
사용하지 않는다.

security-audit: 지적사항 = none

이용조건 evidence:

- URL: `https://open.neis.go.kr/portal/data/service/selectServicePage.do?infId=OPEN19220231012134453534385&infSeq=1&page=1&rows=10&sortColumn=&sortDirection=`
- file: `apps/ai/config/license_evidence/edu.academy-registry.txt`
- SHA-256: `ce16b39dff31b1a41ea9cb8f362310c56258e725c5f470e4347cf81b73d4aac8`

## 실제 데이터 검증

NEIS gateway는 `Accept: application/json` 요청에 key 유효성과 무관하게
`HTTP 500 text/html`을 반환하고 `Accept: */*`에는 `HTTP 200 application/json`을
반환했다. `Type=json` query와 JSON parser·envelope 검증은 유지하면서 header만
gateway 호환값으로 고쳤다. 실제 key의 B10 `pSize=1000` probe는 `INFO-000`,
`list_total_count=25,522`, page row `1,000`으로 확인됐다.

첫 full parse에서 live status 138,412건이 모두 `개원`이고 provider name 공란이
1건임을 raw를 메모리에 전부 적재하지 않는 safe aggregate로 확인했다. `개원`을
`OPEN`으로 고정하고 공란 명칭은 안정 ID를 유지한 `명칭 미제공`/`nameMissing=true`로
보존하되 exact-match key는 `NULL`로 차단했다. 전화번호·교습비와 provider 오류 body는
projection과 진단 출력에 포함하지 않았다.

`academy-registry-v4` actual full refresh 결과는 17개 교육청, 146 pages,
raw/accepted `138,412`, rejected `0`, duplicate fact ID `0`, active datasetVersion
`20260720-3bb7d33261d5`다. active projection도 `138,412`건이고 raw object는 S3
verified checksum, byte length `101,792,940`, version ID를 보존한다. 학원 `95,264`,
교습소 `43,148`, `OPEN` `138,412`, 명칭 미제공·NULL match key는 각각 1건이다.

같은 날 두 번째 146-page actual refresh는 `NoChange`였고 v4 normalized checksum이
동일했다. `NoChange` acquisition staging은 `0`, v4 publication은 `1`, active pointer와
datasetVersion은 유지됐다. v3 이전 진단 publication 두 건과 quality-failed acquisition은
삭제하지 않고 감사 이력으로 보존한다.

잔여 위험은 승인된 대표 질문의 live JSON/signed JWT SSE golden, 대표 집계 query 20회
p95 200ms, active pointer rollback을 아직 검증하지 않은 readiness 공백이다. 따라서
readiness는 `8.0/10 Partial`이며 capability activation은 계속 보류한다.

`api-contract: compatible`

`security-audit: 지적사항 = none`

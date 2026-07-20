# `edu.academy-registry` readiness

기준일: 2026-07-20
상태: `Partial`
구현 품질: `9.5/10 Pass`
실제 데이터 readiness: `2.0/10 Partial`

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

이용조건은 `PENDING`이며 실제 100,000~250,000행 snapshot, 시군구 집계,
live golden은 미완료다. `HOME_AI_ENABLED_REFERENCE_CAPABILITIES` allowlist에는
추가하지 않아 운영 capability는 활성화하지 않았다.

## 구현 품질 평가

| 항목 | 점수 | 검증 근거 확인 |
|---|---:|---|
| 범위·최소성 | `1.0/1.0` | NEIS collector·adapter·projection·summary observer만 정적 합성했고 scheduler·동적 plugin을 추가하지 않음 |
| 공개·내부 계약 | `1.0/1.0` | `acaInsTiInfo`, 17개 교육청, `pSize=1000`, additive `academy_registry_summary` capability와 기존 JSON/SSE field 호환 검증 |
| 이용조건·출처 | `0.5/1.0` | 공식 landing/acquisition URL과 A등급 attribution 후보는 고정했으나 이용허락·private raw 저장 fingerprint는 `PENDING` |
| 데이터 정확성·원자성 | `1.5/1.5` | 17개 교육청 coverage, page·total 일치, unique ID, verified raw-first, rejected row 차단, transaction 안 projection 후 pointer 전환 검증 |
| 보안·개인정보 | `1.0/1.0` | key·전체 query·provider 오류 body 비보존, 전화번호·교습비 비투영, runtime base table 접근 거부 검증 |
| 실패·복구·관측 | `1.0/1.0` | 첫 page 실패는 acquisition 없이 safe reason만 기록하고, 중간 실패는 incomplete raw만 보존하며 active publication을 변경하지 않음 |
| 테스트 품질 | `1.5/1.5` | collector·adapter·ingest·PostGIS projection·observer·LLM contract 집중 테스트 `119 passed`; 전체 AI `546 passed`, coverage `90.21%` |
| 문서·운영 가능성 | `1.0/1.0` | source AsciiDoc, generated request/field/failure snippet, generic refresh·status·audit runbook 일치 |
| 성능·자원 제한 | `0.5/0.5` | page 8MiB, bundle 512MiB, office당 300 page, timeout 1..30초, retry 1회, secure file streaming 제한 검증 |
| 리뷰·commit 추적성 | `0.5/0.5` | collector·projection·observer·streaming·composition 책임 commit과 readiness 잔여 위험을 분리 기록 |

구현 점수는 `9.5/10 Pass`다. 감점은 이용조건 승인 증거가 없는 항목에만 적용했고,
계약·데이터 정확성·보안·테스트 필수 항목은 감점하지 않았다. 이 점수는 offline
구현 commit만 허용하며 실제 호출과 capability 활성화를 승인하지 않는다.

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

전체 coverage 근거는 `priority-reference-offline-verification.md`의 `546 passed`,
`90.21%` 결과를 사용한다. 부분 테스트만 coverage와 함께 실행하면 저장소 전역
`fail-under=90` 특성상 동작이 모두 통과해도 종료 코드가 실패하므로 점수 근거로
사용하지 않는다.

security-audit: 지적사항 = none

잔여 위험은 실제 NEIS key·quota·schema·전국 total, raw S3 복구, 운영 AI DB role,
chatbot JSON/SSE golden을 아직 검증하지 않은 readiness 공백이다.

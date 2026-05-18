# 작업 프롬프트 모음

필요할 때 복사해서 쓰는 개인용 프롬프트 모음이다. 실행 규칙은 `AGENTS.md`, `.agents/skills/`, `.codex/`가 우선한다.

## Goal Task

```text
/goal
목표:

사용자/운영 가치:

성공 기준:

범위:

비범위:

영향 영역:

관련 V1 문서:

검증 증거:

중단 조건:

요청:
- AGENTS.md와 관련 docs/*.md를 먼저 읽어라.
- public API URL, response shape, data invariant 변경이 필요하면 구현 전에 멈추고 질문하라.
- 구현 전 짧은 plan을 만들고, 구현 후 verification evidence를 남겨라.
```

## Backend Task

```text
Home Search backend 작업을 수행해라.

작업:

요구사항:
- AGENTS.md, docs/README.md, docs/ARCHITECTURE.md, docs/DATA_STORAGE.md, docs/API_CONTRACT.md, docs/INFRA_AND_ENV.md를 읽어라.
- V1 API URL과 response shape를 변경하지 마라.
- raw ingest record 저장 후 normalized trade 저장 순서를 지켜라.
- duplicate-safe ingest와 failed match queryability를 보존하라.
- operational relation은 complex_id이며 complex_pk, apt_seq, source, source_key는 audit/dedupe용으로 보존하라.
- ranking, favorite, alarm, mail, recommendation, heavy analytics는 V2로 남겨라.
- behavior 변경은 valid RED test에서 시작하라. 테스트 환경이 없으면 그 이유와 필요한 seam을 기록하라.
- 변경 후 가능한 backend 검증 명령과 scripts/check-ko-docs.sh, git diff --check를 실행하라.
```

## Frontend Task

```text
Home Search frontend 작업을 수행해라.

작업:

요구사항:
- AGENTS.md, docs/API_CONTRACT.md, docs/MAP_DISPLAY_FLOW.md, docs/UI_UX_MIGRATION.md를 읽어라.
- /api/v1/map/regions, /api/v1/map/complexes, search, region, detail, trade API compatibility를 유지하라.
- marker adapter는 parcelId, lat, lng, latestDealAmount, unitCntSum을 canonical field로 다루어라.
- source migration 중 id, latitude, longitude variant는 adapter에서만 임시 수용하라.
- map marker API failure 시 map usability를 유지하고 non-blocking error state를 제공하라.
- apps/web/package.json이 있으면 scripts를 확인한 뒤 npm run lint와 npm run build를 실행하라.
- 존재하지 않는 npm test를 임의로 요구하지 마라.
```

## TDD Task

```text
다음 변경을 TDD로 수행해라.

변경:

요구사항:
- 먼저 public seam을 선택하고 첫 failing test를 작성하라.
- RED가 요구사항 또는 버그 재현과 직접 연결되는지 설명하라.
- private implementation detail만 검증하는 테스트는 작성하지 마라.
- 최소 production code로 GREEN을 만들고, 필요한 refactor만 수행하라.
- backend는 controller/service/repository/Flyway ingest behavior를 우선하라.
- frontend는 API adapter, marker transform, component behavior, map failure fallback을 우선하라.
- 최종 응답에 RED/GREEN 검증 명령과 결과를 남겨라.
```

## Review Task

```text
다음 변경을 review해라.

대상:

요구사항:
- Findings first 형식으로 답하라.
- severity는 Critical, High, Medium, Low를 사용하라.
- correctness, V1 API compatibility, data safety, frontend map usability, security/secrets, missing tests, KO sync를 확인하라.
- style-only comment는 documented rule 위반이나 실제 risk가 있을 때만 보고하라.
- findings가 없으면 명확히 말하고 residual risk 또는 test gap을 적어라.
```

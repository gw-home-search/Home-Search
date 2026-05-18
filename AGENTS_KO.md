# 에이전트 규칙

## 미션

이 저장소는 Home Search V1 마이그레이션 대상이다. V1 목표는 주요 API URL을 보존하면서 부동산 아파트 실거래 데이터를 수집하고, 안전하게 저장하고, 지도에 표시하는 것이다.

정확성, 추적 가능성, API 호환성이 빠른 기능 확장보다 중요하다.

## Source of Truth

canonical 문서는 다음 순서로 읽는다.

1. `docs/README.md`
2. `docs/MIGRATION_PLAN.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DATA_STORAGE.md`
5. `docs/API_CONTRACT.md`
6. `docs/MAP_DISPLAY_FLOW.md`
7. `docs/UI_UX_MIGRATION.md`
8. `docs/INFRA_AND_ENV.md`

Source repositories는 read-only reference로만 확인할 수 있다.

- Backend source: `/Users/gwongwangjae/IdeaProjects/home-server`
- Frontend source: `/Users/gwongwangjae/frontend/home-client`

## AI Skill/Hook References

저장된 예시 저장소는 현재 작업에서 사용자가 reference, compare, bring material을 명시적으로 요청하지 않는 한 검사, 검색, 요약, 복사, context 사용을 하지 않는다.

- AI skill/hook reference: `/Users/gwongwangjae/saved-ai-exam`

명시적으로 요청된 경우에도 AI skill, hook, local agent automation을 위한 read-only reference로만 사용한다. 관련 하위 경로를 우선한다.

- `/Users/gwongwangjae/saved-ai-exam/skills`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.agents`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.codex`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.githooks`

작업이 skill, hook, automation과 관련된다는 이유만으로 proactive하게 참조하지 않는다.

`/Users/gwongwangjae/saved-ai-exam`은 application implementation, public APIs, database design, UI, product requirements, general test architecture, ordinary code style에 사용하지 않는다.

코드를 verbatim copy하지 않고, example repository의 agent instructions를 import하지 않고, example repository에 쓰지 않고, scripts를 실행하지 않고, dependency를 자동으로 추가하지 않고, submodule/package link/symlink로 연결하지 않는다.

저장된 예시 저장소에도 동일한 KO-file read/search ban을 적용한다. reference가 이 프로젝트와 충돌하면 현재 canonical docs와 target source code가 항상 우선한다.

이 프로젝트의 모든 write는 `/Users/gwongwangjae/home-search` 아래에만 한다.

## Workflow

- 편집 전 `git status --short`를 확인한다.
- 변경 전 canonical docs와 관련 코드를 탐색한다.
- scope가 모호하면 구현 전 짧은 plan을 쓰거나 질문한다.
- 기존 저장소 구조에 맞춰 작게 수정한다.
- 변경 후 검증한다. 검증할 수 없으면 이유를 말한다.
- 실패한 check를 숨기지 않는다. 고치거나 blocker를 보고한다.

## AI Development Operating Rules

- AI workflow 문서는 `ai-docs/README.md`에서 시작한다.
- `ai-docs/`는 개발 절차만 정의한다. V1 migration source of truth는 위에 나열된 canonical `docs/*.md` 문서다.
- `/goal` 또는 모호한 요청이 구현 전 decision-complete plan을 필요로 하면 `.agents/skills/planning`을 사용한다.
- production behavior 변경 전에 valid RED test를 만들 수 있으면 `.agents/skills/tdd`를 사용한다.
- failing checks, API mismatch, ingest bugs, map marker failures에는 `.agents/skills/systematic-debugging`을 사용한다.
- correctness, V1 API compatibility, data safety, missing tests, KO sync에 대한 findings-first review와 final self-review에는 `.agents/skills/code-review`를 사용한다.
- Subagent는 사용자가 명시적으로 요청했거나 독립 read-only research로 분리 가능한 작업에만 사용한다. Local review 또는 변경 ownership을 우회하기 위해 사용하지 않는다.
- Verification evidence 없이 completion을 주장하지 않는다. Check를 실행할 수 없으면 이유와 residual risk를 보고한다.

## Git Publishing

- Local commits는 요청받았거나 handoff에 유용할 때 completed work 보존용으로 사용할 수 있다.
- `git push`는 기본 completion step이 아니다.
- 현재 작업에서 사용자가 remote publishing을 명시적으로 요청했거나 이전에 합의한 plan에 포함된 경우에만 push한다.
- `push`, `publish`, `open a PR`, `create a PR`, `update remote`는 remote-publishing request로 취급한다.
- 사용자가 remote publishing 언급 없이 implement 또는 commit만 요청하면 push하지 않는다.

## V1 Guardrails

- `docs/API_CONTRACT.md`에 문서화된 V1 API URL을 유지한다.
- 명시적으로 재조정되지 않는 한 V1 map과 trade-data surface만 구현한다.
- rankings, favorites, alarms, mail batches, recommendations, insights, heavy analytics는 V2이며 critical path에서 제외한다.
- UI/UX는 바뀔 수 있지만 frontend calls는 V1 API contract와 호환되어야 한다.

## Data/API Invariants

- Raw ingest records는 normalized trade rows보다 먼저 저장한다.
- Duplicate ingest는 duplicate normalized trades를 만들면 안 된다.
- Failed matches는 explainable하고 queryable해야 한다.
- Operational trade relation은 `complex_id`다.
- `complex_pk`, `apt_seq`, `source`, `source_key`는 audit, matching, dedupe support를 위해 보존한다.
- Map endpoints는 ranking, trend, favorite, mail state를 요구하면 안 된다.

## Repo Hygiene

- 명시 요청 없이는 user changes를 되돌리지 않는다.
- unrelated refactors, renames, formatting churn, dependency changes를 피한다.
- secrets, API keys, local environment values를 commit하지 않는다.
- `apps/api`, `apps/web`, `infra`, `docs` 책임을 분리한다.
- `.gitkeep` 파일은 empty directories 보존용이다. 실제 tracked file이 역할을 대체할 때만 제거한다.

## Human-Only KO Docs

- `*_KO.md` 파일은 human-only Korean reference files다.
- AI agents는 `*_KO.md`, `*_KO.local.md`, `*_ko.md`, `*_ko.local.md`를 read, search, summarize, quote, import, diff, implementation context로 사용하지 않는다.
- Lowercase variants도 동일하게 취급한다.
- KO sync metadata는 KO 파일이 아니라 `.ko-docs.toml`에 둔다.
- AI agents는 KO docs stale 여부를 판단하기 위해 `.ko-docs.toml`과 canonical `.md` 파일을 읽을 수 있다.
- Markdown 검색 시 KO 파일을 제외한다.

```sh
rg --files --hidden -g '*.md' -g '!.git/**' -g '!**/*_KO.md' -g '!**/*_KO.local.md' -g '!**/*_ko.md' -g '!**/*_ko.local.md'
```

- `scripts/check-ko-docs.sh`는 KO body content를 읽거나 출력하면 안 된다. `.ko-docs.toml`의 KO paths 존재 여부만 확인할 수 있다.

## Markdown Update Rule

- 모든 canonical `.md` 파일에는 같은 위치의 `*_KO.md` 파일을 유지한다.
- canonical `.md`가 생성되면 해당 `*_KO.md`도 생성한다.
- canonical `.md` 의미가 바뀌면 기존 KO 파일을 읽지 않고 canonical 기준으로 KO companion을 재생성하고 `.ko-docs.toml`을 업데이트한다.
- canonical `.md`가 rename 또는 delete되면 paired `*_KO.md`도 rename 또는 delete한다.
- KO 업데이트를 위해 existing KO file을 읽지 않는다. Canonical source를 input으로 사용하고 KO file을 overwrite한다.
- Personal notes는 `*_KO.local.md`에 둔다. 해당 파일은 ignored이며 건드리지 않는다.

## Verification

- Markdown 변경 후 `scripts/check-ko-docs.sh`를 실행한다.
- `apps/api`가 생긴 뒤 backend commands를 여기에 추가한다.
- `apps/web`이 생긴 뒤 frontend commands를 여기에 추가한다.
- Docker/PostGIS/Flyway 파일이 생긴 뒤 infra commands를 여기에 추가한다.

## When To Ask

다음 경우에는 behavior 변경 전에 질문한다.

- V1/V2 scope가 불명확하다.
- Public API URL 또는 response shape를 바꿔야 한다.
- Database 변경이 데이터를 잃거나 재해석할 수 있다.
- Source repositories가 target docs와 충돌한다.
- KO file이 canonical source와 다른 요구사항을 포함한 것처럼 보인다.

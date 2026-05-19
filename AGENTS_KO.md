# Agent Rules

## Mission

이 repository는 Home Search V1 migration target이다. V1 목표는 real-estate apartment trade data를 수집하고, 안전하게 저장하고, main API URLs를 보존하면서 map에 표시하는 것이다.

빠른 feature expansion보다 correctness, traceability, API compatibility가 더 중요하다.

## Source of Truth

canonical documents는 다음 순서로 읽는다:

1. `docs/README.md`
2. `docs/MIGRATION_PLAN.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DATA_STORAGE.md`
5. `docs/API_CONTRACT.md`
6. `docs/MAP_DISPLAY_FLOW.md`
7. `docs/UI_UX_MIGRATION.md`
8. `docs/INFRA_AND_ENV.md`

source repositories는 read-only references로만 inspect할 수 있다:

- Backend source: `/Users/gwongwangjae/IdeaProjects/home-server`
- Frontend source: `/Users/gwongwangjae/frontend/home-client`

## AI Skill/Hook References

saved example repository는 현재 task에서 user가 reference, compare, material 가져오기를 명시적으로 요청하지 않는 한 inspect, search, summarize, copy, context로 사용하면 안 된다:

- AI skill/hook reference: `/Users/gwongwangjae/saved-ai-exam`

명시적으로 요청된 경우에도 AI skills, hooks, local agent automation을 위한 read-only reference로만 사용한다. 해당 reference 안에서는 다음 subpaths를 우선한다:

- `/Users/gwongwangjae/saved-ai-exam/skills`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.agents`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.codex`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.githooks`

작업이 skills, hooks, automation과 관련된다는 이유만으로 proactive하게 참조하지 않는다.

application implementation, public APIs, database design, UI, product requirements, general test architecture, ordinary code style에는 `/Users/gwongwangjae/saved-ai-exam`을 사용하지 않는다.

code를 verbatim으로 copy하지 않고, example repository agent instructions를 import하지 않으며, example repository에 write하거나 scripts를 실행하지 않는다. dependencies를 자동으로 추가하지 않고 submodule, package link, symlink로 연결하지 않는다.

saved example repository에도 같은 KO-file read/search ban을 적용한다. reference가 이 project와 충돌하면 현재 canonical docs와 target source code가 항상 우선한다.

이 project의 모든 writes는 `/Users/gwongwangjae/home-search` 아래에 속한다.

## Workflow

- 편집 전에 `git status --short`를 확인한다.
- 변경 전에 canonical docs와 관련 code를 탐색한다.
- scope가 ambiguous하면 implementation 전에 short plan을 쓰거나 질문한다.
- edits는 작게 유지하고 existing repository shape와 맞춘다.
- 변경 후 verify한다. verification을 사용할 수 없으면 이유를 말한다.
- failing checks를 숨기지 않는다. 고치거나 blocker를 보고한다.

## AI Development Operating Rules

- AI workflow docs는 `ai-docs/README.md`에서 시작한다.
- `ai-docs/`는 development procedure만 정의한다. V1 migration source of truth는 위에 나열한 canonical `docs/*.md` documents다.
- `/goal` 또는 ambiguous request가 implementation 전에 decision-complete plan을 필요로 하면 `.agents/skills/planning`을 사용한다.
- valid RED test를 만들 수 있는 production behavior changes 전에는 `.agents/skills/tdd`를 사용한다.
- failing checks, API mismatch, ingest bugs, map marker failures에는 `.agents/skills/systematic-debugging`을 사용한다.
- correctness, V1 API compatibility, data safety, missing tests, KO sync에 대한 findings-first review와 final self-review에는 `.agents/skills/code-review`를 사용한다.
- Subagents는 user가 명시적으로 요청하거나 task를 independent read-only research work로 나눌 수 있을 때만 허용된다. local review 또는 ownership을 우회하기 위해 subagents를 사용하지 않는다.
- verification evidence 없이 completion을 주장하지 않는다. check를 실행할 수 없으면 이유와 residual risk를 보고한다.

## User-Facing Language Policy

- User-facing agent output, review summaries, PR body prose, hook block reasons, report headings는 Korean-first이고 concise해야 한다.
- Internal code, implementation comments, agent operating instructions, harness prompt instructions는 특정 text가 user-facing output label 또는 generated body가 아닌 한 English로 유지한다.
- translation하면 precision이 줄어드는 technical tokens는 canonical form을 유지한다: commands, paths, file names, CLI options, branch names, JSON keys, API fields, `Pass|Partial|Fail` 같은 status values, `pass|fail|not run`, GitHub check names, `contract-reviewer` 또는 `reviewer` 같은 agent ids.
- 새 evidence와 reviews에는 다음 Korean labels를 선호한다: `지적사항`, `검증 근거 확인`, `검증 공백`, `잔여 위험`, `인수 기준`, `TDD 근거`, `최초 RED`, `예상 RED 실패`, `최소 GREEN`, `계약 영향`.
- Severity labels는 Korean-first로 쓰고 필요하면 English term을 유지한다: `치명(Critical)`, `높음(High)`, `중간(Medium)`, `낮음(Low)`.
- Validators와 hooks는 compatibility를 위해 legacy English labels를 허용할 수 있지만, generated templates와 새 user-visible guidance는 Korean-first labels를 사용해야 한다.

## Git Publishing

- 요청되었거나 handoff에 유용할 때 completed work를 보존하기 위해 local commits를 사용할 수 있다.
- `git push`는 default completion step이 아니다.
- 현재 task에서 user가 remote publishing을 명시적으로 요청하거나 이전에 합의한 plan에 포함된 경우에만 push한다.
- `push`, `publish`, `open a PR`, `create a PR`, `update remote`는 remote-publishing requests로 취급한다.
- user가 remote publishing을 언급하지 않고 implement 또는 commit만 요청하면 push하지 않는다.

## V1 Guardrails

- `docs/API_CONTRACT.md`에 문서화된 V1 API URLs를 유지한다.
- 명시적으로 rescope되지 않는 한 V1 map과 trade-data surface만 구현한다.
- V2 work를 critical path 밖에 둔다: rankings, favorites, alarms, mail batches, recommendations, insights, heavy analytics.
- UI/UX는 바뀔 수 있지만 frontend calls는 V1 API contract와 compatible해야 한다.

## Data/API Invariants

- Raw ingest records는 normalized trade rows보다 먼저 저장된다.
- Duplicate ingest는 duplicate normalized trades를 만들면 안 된다.
- Failed matches는 explainable하고 queryable해야 한다.
- operational trade relation은 `complex_id`다.
- audit, matching, dedupe support를 위해 `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.
- Map endpoints는 ranking, trend, favorite, mail state를 요구하면 안 된다.

## Repo Hygiene

- 명시적으로 요청받지 않는 한 user changes를 revert하지 않는다.
- unrelated refactors, renames, formatting churn, dependency changes를 피한다.
- secrets, API keys, local environment values를 commit하지 않는다.
- `apps/api`, `apps/web`, `infra`, `docs` responsibilities를 분리해 유지한다.
- `.gitkeep` files는 empty directories를 보존한다. 실제 tracked file이 그 목적을 대체할 때만 제거한다.

## Human-Only KO Docs

- `*_KO.md` files는 human-only Korean reference files다.
- AI agents는 `*_KO.md` 또는 `*_KO.local.md`를 implementation context로 읽거나, search, summarize, quote, import, diff, use하면 안 된다.
- `*_ko.md` 같은 lowercase variants도 동일하게 취급한다.
- AI agents는 exact KO targets에 대한 task-level confirmation 후에만 `*_KO.md` files를 수정할 수 있다.
- KO files를 쓰기 전에 `KO 수정 요청:` note를 보여주고 `KO 대상`을 나열하며, `KO 생성 기준: canonical source only`를 명시하고, existing KO body를 읽지 않았음을 확인한다.
- KO file change completion evidence에는 `KO 수정 승인: 확인`, `KO 대상: <paths>`, `KO 생성 기준: canonical source only`가 포함되어야 한다.
- KO sync metadata는 KO files 안이 아니라 `.ko-docs.toml`에 둔다.
- AI agents는 KO docs가 stale인지 판단하기 위해 `.ko-docs.toml`과 canonical `.md` files를 읽을 수 있다.
- Markdown을 search할 때는 KO files를 제외한다:

```sh
rg --files --hidden -g '*.md' -g '!.git/**' -g '!**/*_KO.md' -g '!**/*_KO.local.md' -g '!**/*_ko.md' -g '!**/*_ko.local.md'
```

- `scripts/check-ko-docs.sh`는 KO body content를 읽거나 출력하면 안 된다. `.ko-docs.toml`의 KO paths가 존재하는지만 확인할 수 있다.

## Markdown Update Rule

- 모든 canonical `.md` file에는 같은 위치의 `*_KO.md` file을 유지한다.
- canonical `.md`가 생성되면 그 `*_KO.md`를 만든다.
- canonical `.md`가 의미 있게 바뀌면 canonical file만 입력으로 사용해 `*_KO.md`를 재생성하고 `.ko-docs.toml`을 업데이트한다.
- canonical `.md`가 rename 또는 delete되면 paired `*_KO.md`도 rename 또는 delete한다.
- existing KO file을 읽고 업데이트하지 않는다. canonical source를 input으로 사용하고 KO file을 overwrite한다.
- paired KO update가 확인되지 않았다면 canonical Markdown change 전에 중단하거나 KO update가 blocked라고 보고한다.
- personal notes는 `*_KO.local.md`에 둔다. 이 files는 ignored이며 건드리면 안 된다.

## Verification

- Markdown changes 후에는 `scripts/check-ko-docs.sh`를 실행한다.
- `apps/api`가 생기면 backend commands를 여기에 추가한다.
- `apps/web`이 생기면 frontend commands를 여기에 추가한다.
- Docker/PostGIS/Flyway files가 생기면 infra commands를 여기에 추가한다.

## When To Ask

다음 경우 behavior 변경 전에 질문한다:

- V1/V2 scope가 unclear.
- public API URL 또는 response shape가 바뀌어야 함.
- database change가 data를 잃거나 reinterpret할 수 있음.
- source repositories가 target docs와 충돌함.
- KO file이 canonical source와 다른 requirements를 포함하는 것처럼 보임.

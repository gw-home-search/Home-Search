---
name: v1-slice-harness
description: Home Search V1 slice workflow를 harness launcher로 라우팅한다. next/plan/dry-run/run/report, worktree orchestration, integration report, push, draft PR, PR lint evidence에 사용한다. planning, tdd, systematic-debugging, code-review를 대체하지 않고 필요한 mode로 라우팅한다.
---


# V1 Slice Harness

긴 prompt를 붙이지 않고 반복 가능한 Home Search V1 slice 작업을 실행할 때
이 skill을 사용한다. 이 skill은 launcher router와 routing guide이며
`planning`, `tdd`, `systematic-debugging`, `code-review`를 대체하지 않는다.

## Ground Rules

- 작업 전 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO canonical docs 또는
  skills를 확인한다.
- `*_KO.md` body는 읽거나 요약하거나 diff하거나 구현 context로 쓰지 않는다.
- KO companion이 필요하면 기존 KO body를 읽지 않고 canonical `SKILL.md`에서
  재생성한다.
- slice workflow는 짧은 launcher `.codex/harness/v1`을 우선 사용한다.
- 다음 slice를 모르면 `.codex/harness/v1 next`를 먼저 실행한다.
- named slice 실행 전에는 `.codex/harness/v1 plan <slice-id>`와
  `.codex/harness/v1 dry <slice-id>`를 실행한다. 단, 사용자가 planning만
  명시한 경우는 제외한다.
- `run`은 plan과 dry-run evidence가 명확할 때만 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`에서는 기본적으로
  파일을 수정하지 않는다.
- hooks는 gate일 뿐이며 자동 fix, retry, commit, merge, push, 전체 test와
  build 실행을 주장하지 않는다.
- 사용자-facing review는 Korean-first로 짧게 작성한다. command, path,
  status token, agent id는 원문을 유지한다.

## Slice Identity

- 이 `SKILL.md`는 backlog, preset, slice registry가 아니다.
- 실제 slice id는 backlog, preset resolution, 최근 gate evidence,
  `.codex/harness/v1 next` 결과에서 온다.
- `v1-slice-harness`는 launcher operation `next`, `plan`, `dry`, `run`,
  `report`를 라우팅한다. `gate`와 `recover`는 skill routing mode다.
- durable routing policy는 `.codex/harness/skill_routing.py`와 launcher,
  prompt 파일에 둔다.

## Short Prompt Grammar

- `$v1-slice-harness 다음 slice 골라줘`
- `$v1-slice-harness <slice-id> plan만 세워줘`
- `$v1-slice-harness <slice-id> dry-run 해줘`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`
- `$v1-slice-harness <slice-id> 진행해줘`
- `$v1-slice-harness <slice-id> PR까지 만들어줘`
- `$v1-slice-harness <slice-id> merge push PR까지 해줘`
- `$v1-slice-harness <slice-id> push만 해줘`
- `$v1-slice-harness <slice-id> report 다시 보내줘`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`

## Launcher Mapping

- next: `.codex/harness/v1 next`
- plan: `.codex/harness/v1 plan <slice-id>`
- dry: `.codex/harness/v1 dry <slice-id>`
- target dry: `.codex/harness/v1 dry <slice-id> --targets <target>`
- run: `.codex/harness/v1 run <slice-id>`
- PR 또는 draft PR: `.codex/harness/v1 run <slice-id> --pr`
- merge + push + PR: `.codex/harness/v1 run <slice-id> --pr`
- push: `.codex/harness/v1 run <slice-id> --push`
- report: `.codex/harness/v1 report <slice-id>`
- stale backlog sync: `.codex/harness/v1 sync-backlog --merged`
- write 없는 stale backlog 확인: `.codex/harness/v1 sync-backlog --merged --dry-run`
- prompt에 `notion` 또는 `slack`이 있으면 해당 flag를 추가한다.
- target이 명시되면 `--targets backend|frontend|both|planning-only`를 추가한다.

## Launcher Defaults

- slice id만 주면 preset, target, branch, worktree, report 경로를 자동으로
  결정한다.
- 기본 `run`은 target commit, integration branch 생성, local report를 수행한다.
- non-planning integration이 성공하면 integration branch에서 현재 backlog slice를
  `done`으로 표시하고 metadata commit을 만든다. 이 `done` 상태는 GitHub PR이
  merge될 때만 `main`에 반영된다.
- `backend`는 backend worktree만, `frontend`는 frontend worktree만 만든다.
- `both`는 backend/frontend worktree를 만들며 `--parallel`은 `both`에서만 유효하다.
- `planning-only`는 구현 worktree, verification, gate, commit, integration,
  push, PR automation을 실행하지 않는다.
- short prompt의 `merge`는 target branch를 generated `feat/*-integration`
  branch로 merge한다는 뜻이다. `main` merge나 GitHub PR merge가 아니다.
- main merge, main push, PR merge, approve, live Open API call, DB migration
  실행은 자동화하지 않는다.
- `sync-backlog`는 merged GitHub PR 상태를 읽어 stale local backlog metadata만
  갱신한다. PR merge, push, app code 변경은 하지 않는다.
- remote push와 draft PR은 현재 prompt에서 명시 요청이 있을 때만 수행한다.
- `--push`는 integration branch만 push한다. `--pr`은 integration branch push 후
  draft PR을 만든다.
- PR flow는 strict PR lint를 통과해야 한다.
- Notion/Slack은 best-effort이며 critical path를 깨지 않는다.

## Launcher Skill Routing

- `.codex/harness/v1 plan`과 `next`는 `.codex/harness/skill_routing.py`에서
  explicit `사용 skill:` evidence를 렌더링한다.
- `.codex/harness/v1 run`은 Codex execute/gate prompt에 skill contract를 주입한다.
- launcher는 workflow를 선택하고 specialized skill이 planning, RED validity,
  backend/frontend domain rule, contract check, failure recovery, final review를
  맡도록 라우팅한다.
- `planning-only`는 planning evidence까지만 라우팅한다.
- failure 또는 hook block은 `systematic-debugging`으로, behavior root cause는
  `tdd`로, recovery 완료 후에는 `code-review`로 라우팅한다.

## Lint/Test/Build Handling

- frontend verification은 `apps/web/package.json`에 존재하는 script만 사용한다.
  현재는 `cd apps/web && npm run test`, `cd apps/web && npm run build`를 사용한다.
- frontend lint가 도입되면 harness flow, preset, hooks, PR lint, report, CI를
  함께 업데이트한다.
- backend canonical PR/CI gate는 `cd apps/api && ./gradlew backendQualityCheck`다.
- changed-file PR evidence:
  - `apps/api/**`: `backendQualityCheck`, `Coverage: >=90%`, `Docs/OpenAPI`
  - `apps/web/**`: `npm run test`, `npm run build`
  - harness/hooks/workflow/Markdown/KO: 해당 self-test와 KO sync evidence
- verification 실패나 hook block은 `systematic-debugging`으로 라우팅한다.

## Mode Selection

- `mode=plan`: 다음 slice plan, 이전 gate 기준 plan, 리뷰 기준 plan.
- `mode=execute`: slice 실행, backend/web 실행, 구현.
- `mode=gate`: 현재 slice gate, 짧은 gate review, 완료 전 리뷰.
- `mode=next`: 다음 slice 추천.
- `mode=recover`: hook evidence 복구, 실패 복구, 검증 실패 복구.
- mode가 불명확하면 파일을 바꾸기 전에 짧게 질문한다.

## Required Routing

- `mode=plan`:
  - ambiguous goal에는 `planning`을 사용한다.
  - target이 `backend`, `frontend`, `both`이면 `vertical-slice-implementation`을
    사용해 plan을 얇고 독립 검증 가능한 V1 slice로 유지한다.
  - First RED가 계획 가능하면 `tdd`를 사용한다.
  - focused read-only research에는 code-mapper/backend-planner/frontend-planner만
    사용한다.
- `mode=execute`:
  - 모든 behavior change는 `tdd`를 사용한다.
  - V1 API request/response/unit/error 영향은 `contract-reviewer`를 사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - 완료 전에는 `reviewer` 또는 `code-review`를 사용한다.
- `mode=gate`:
  - implementation change는 `reviewer`가 검토한다.
  - contract-adjacent change는 `contract-reviewer`가 검토한다.
  - First RED validity는 `tdd-guide`가 확인한다.
- `mode=next`:
  - 기본적으로 subagent를 만들지 않는다.
  - 현재 gate evidence, missing tests, risks를 1~3개 next slice 후보로 바꾼다.
- `mode=recover`:
  - reproducible failure에는 `systematic-debugging`을 사용한다.
  - 사용자가 execute mode로 전환하지 않으면 파일을 수정하지 않는다.

## Mode Rules

### mode=plan

- 파일 수정 금지.
- previous gate `missing tests`, `findings`, `주요 위험`을 next-slice
  acceptance criteria로 변환한다.
- output labels: `상태`, `목표`, `인수 기준`, `최초 RED`, `예상 RED 실패`,
  `최소 GREEN`, `검증`, `다음 행동`.

### mode=execute

- 파일 수정 가능.
- plan과 dry-run evidence 후 실행한다.
- target scope를 지키고 out-of-scope 변경을 만들지 않는다.
- 완료 전 verification, gate review, commit/integration evidence를 남긴다.

### mode=gate

- 기본 read-only review다.
- findings-first로 correctness, V1 API compatibility, data safety, missing tests,
  KO sync를 확인한다.

### mode=next

- backlog와 recent report evidence에서 unfinished candidate를 추천한다.
- merged PR인데 backlog status가 stale이면 `sync-backlog --merged --dry-run`
  확인을 먼저 제안한다.

### mode=recover

- failing command, hook block, evidence gap을 재현 가능한 순서로 정리한다.
- behavior bug면 regression evidence를 `tdd`로 연결한다.

## Final Evidence

사용자-facing 완료 요약은 Korean-first로 작성하고 아래 근거를 포함한다.

- `상태`
- `검증`
- `계약 영향`
- `KO 수정 승인`이 필요한 경우의 KO evidence
- `다음 행동`

---
name: v1-slice-harness
description: Home Search V1 slice workflow를 harness launcher로 routing한다. next/plan/dry-run/run/report, worktree orchestration, integration report, push, draft PR, PR lint evidence를 다룬다. "V1 slice", "harness", "next slice", "dry-run", "gate review", "PR", "worktree", "다음 slice", "plan만", "진행해줘", "PR까지", "짧은 gate review", "hook evidence 복구"에 사용한다. planning, tdd, systematic-debugging, code-review를 대체하지 않으며 필요한 gate/recover 요청은 해당 mode로 route한다.
---


# V1 Slice Harness

사용자가 긴 prompt를 붙여 넣지 않고 반복 가능한 Home Search V1 slice 작업을 원할 때 이 skill을 사용한다. 이 skill은 launcher router와 routing guide이며, `planning`, `tdd`, `systematic-debugging`, `code-review`를 대체하지 않는다.

## Ground Rules

- 동작을 바꾸기 전에 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO canonical docs 또는 skills를 읽는다.
- `*_KO.md` body를 읽거나 요약하거나 diff하거나 재사용하지 않는다.
- KO companion을 업데이트해야 하면 기존 KO body를 읽지 않고 canonical `SKILL.md`에서 다시 생성한다.
- slice workflow 실행은 짧은 launcher `.codex/harness/v1`을 우선 사용한다. launcher가 필요한 동작을 표현하지 못하거나 debugging이 필요할 때만 lower-level script를 사용한다.
- 사용자가 다음 slice를 모르면 `.codex/harness/v1 next`를 실행하거나 `$v1-slice-harness 다음 slice 골라줘`를 사용한 뒤 작업을 제안한다.
- named slice를 실행하기 전에는 사용자가 planning only를 명시하지 않은 한 `.codex/harness/v1 plan <slice-id>` 후 `.codex/harness/v1 dry <slice-id>`를 실행한다.
- plan과 dry-run evidence가 명확한 뒤에만 `run`을 사용한다.
- 구현 또는 파일 mutation이 요청된 경우에만 `mode=execute`를 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`에서는 기본적으로 파일을 수정하지 않는다.
- `planning-only` 결과는 planning evidence로만 취급하고 자동 구현 허가로 보지 않는다.
- Hook은 gate일 뿐이다. hook에서 자동 fix, retry, commit, merge, push, full test/build 실행을 설계하거나 주장하지 않는다.
- 최종 user-facing review는 짧고 Korean-first로 유지한다. command, path, status token, agent id는 그대로 둔다.

## Slice Identity

- 이 `SKILL.md`는 backlog, preset, slice registry가 아니다.
- 이 `SKILL.md`는 slice id를 정의하지 않으며 slice registry로 취급하면 안 된다.
- `v1-slice-harness`는 launcher operation `next`, `plan`, `dry`, `run`, `report`를 위한 slice workflow router다. `gate`와 `recover`는 skill routing mode이지 launcher subcommand가 아니다.
- 지속적인 routing policy는 `.codex/harness/skill_routing.py`와 launcher/prompt 파일에 있다. 이 skill은 harness가 나중에 다른 entrypoint로 노출되어도 같은 skill routing 동작을 잃지 않도록 얇게 유지한다.
- 실제 slice id는 backlog, preset resolution, recent gate evidence, `.codex/harness/v1 next` 결과에서 온다.
- `<slice-id>`와 `<target>`은 이 문서의 placeholder이며 launcher에 literal value로 전달하지 않는다.

## Short Prompt Grammar

Short prompt는 사용자 입력 예시일 뿐이다. slice id, preset id, literal command가 아니다.

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

짧은 사용자 prompt는 먼저 launcher로 mapping한다.

- next:
  `.codex/harness/v1 next`
- plan:
  `.codex/harness/v1 plan <slice-id>`
- dry:
  `.codex/harness/v1 dry <slice-id>`
- target dry:
  `.codex/harness/v1 dry <slice-id> --targets <target>`
- run:
  `.codex/harness/v1 run <slice-id>`
- PR 또는 draft PR:
  `.codex/harness/v1 run <slice-id> --pr`
- merge + push + PR:
  `.codex/harness/v1 run <slice-id> --pr`
- push:
  `.codex/harness/v1 run <slice-id> --push`
- report:
  `.codex/harness/v1 report <slice-id>`
- prompt에 `notion`이 있으면 `--notion`을 붙인다.
- prompt에 `slack`이 있으면 `--slack`을 붙인다.
- target이 명시되면 `--targets backend`, `--targets frontend`,
  `--targets both`, `--targets planning-only` 중 하나를 붙인다.

Launcher default:

- 일반 경로에서 사용자는 slice id만 제공한다.
- Preset과 target은 slice id, backlog, explicit option에서 resolve된다.
- Branch와 worktree 이름은 자동 생성된다.
- 기본 `run`은 commit, integration branch 생성, local report를 수행한다.
- `backend`는 backend worktree만 만들고 실행한다.
- `frontend`는 frontend worktree만 만들고 실행한다.
- `both`는 backend와 frontend worktree를 만들고 실행한다. `--parallel`은 이 target에서만 유효하다.
- `planning-only`는 implementation worktree를 만들지 않으며 Codex execute, verification, gate, commit, integration, push, PR automation을 실행하면 안 된다.
- short prompt에서 `merge`는 준비된 target branch를 생성된 `feat/*-integration` branch로 merge한다는 뜻이다. `main`으로 merge하거나 GitHub PR을 merge한다는 뜻이 아니다.
- Main merge, main push, PR merge, approve, live Open API call, DB migration execution은 절대 자동 실행하지 않는다.
- Remote push와 draft PR 생성은 현재 prompt에서 사용자가 PR/push를 명시적으로 요청했을 때만 수행한다.
- `--push`는 integration 성공 후 생성된 `feat/*-integration` branch만 push한다. `--pr`은 같은 integration branch push를 수행한 뒤 draft PR을 만든다.
- PR 요청은 항상 생성된 `feat/*-integration` branch를 대상으로 하며 기본적으로 draft PR을 만든다. `--pr` flow는 draft PR 생성 전에 strict PR lint를 통과해야 한다.
- `dry --pr`은 command expansion과 safety preflight일 뿐이다. Changed-file PR lint는 실제 integration branch가 생긴 뒤 완전히 강제된다.
- Notion과 Slack은 optional best-effort notification이며 critical path를 깨면 안 된다. PR 생성 요청 시 PR URL notification은 PR URL을 알게 된 뒤에만 수행한다.
- 사용자는 짧고 자연스러운 prompt를 유지한다. launcher가 slice, preset, target, branch, worktree, report 세부사항을 확장한다.

## Launcher Skill Routing

- `.codex/harness/v1 plan`과 `.codex/harness/v1 next`는 `.codex/harness/skill_routing.py`에서 explicit `사용 skill:` evidence를 render한다.
- `.codex/harness/v1 run`은 Codex execute와 gate prompt에 `Skill routing:`을 주입하여 target agent가 관련 `$tdd`, `$backend-api`, `$frontend-web`, `$api-contract`, `$systematic-debugging`, `$code-review` trigger를 받도록 한다.
- Launcher는 이 workflow를 소유한다고 가장하지 않는다. mode와 target context를 선택하고, planning, RED validity, backend/frontend domain rules, contract check, failure recovery, final review를 소유한 specialized skill로 route한다.
- `planning-only`는 planning evidence로만 route하며 implementation, verification, gate, commit, integration, push, PR automation으로 이어지면 안 된다.
- Failure 또는 hook-block evidence는 `systematic-debugging`으로 route한다. behavior root cause는 그 뒤 `tdd`로 route하고, 완료된 recovery는 `code-review`로 route한다.

## Lint/Test/Build Handling

- Frontend verification command는 `apps/web/package.json`에 있는 script에서 와야 한다. 현재는 `cd apps/web && npm run test`와 `cd apps/web && npm run build`를 사용한다. script가 생기기 전에는 `npm run lint`를 추가하지 않는다.
- frontend lint가 도입되면 관련 enforcement point를 함께 업데이트한다: `.codex/harness/v1_flow.py` `KNOWN_VERIFICATION_COMMANDS`, `.codex/harness/presets/*.toml`, `.codex/hooks/post_tool_use_review.py`, `.codex/hooks/stop_verification_gate.py`, `.codex/harness/pr_lint.py`, `.codex/harness/v1_report.py`, `.github/workflows/ci.yml`.
- Backend verification은 Gradle lint 또는 check task를 추측하면 안 된다. canonical backend PR/CI quality gate는 `cd apps/api && ./gradlew backendQualityCheck`를 사용한다. `test`는 ad-hoc debugging 또는 explicit plan에서만 사용하고, `apps/api/**` 변경의 canonical PR evidence로 쓰지 않는다.
- PR evidence는 changed-file 기반이다: `apps/api/**`는 `backendQualityCheck`, `Coverage: >=90%`, `Docs/OpenAPI: generated + verified`가 필요하다. `apps/web/**`는 `npm run test`와 `npm run build`가 필요하다. harness, hook, GitHub workflow, Markdown, KO 변경은 해당 self-test와 KO sync evidence가 필요하다.
- Verification command failure 또는 hook block은 `mode=recover`와 `systematic-debugging`으로 route한다. root cause가 behavior bug라면 regression evidence를 `tdd` 또는 `tdd-guide`로 route한다. fix 후에는 정확한 verification line을 기록하고 완료 전에 `code-review` 또는 `reviewer`를 사용한다.

## Mode Selection

사용자의 짧은 prompt에서 mode를 고른다.

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

mode가 불명확하면 파일을 바꾸기 전에 짧은 질문 하나를 한다.

## Required Routing

Routing은 필요한 review capability를 이름 붙인다. active operating rules가 허용할 때만 subagent를 spawn한다. 그렇지 않으면 같은 local skill review를 수행하고 final evidence에 제한을 기록한다.

- `mode=plan`:
  - ambiguous goal에는 `planning`을 사용한다.
  - First RED를 계획할 수 있으면 `tdd`를 사용한다.
  - focused read-only research에만 `code-mapper`, `backend-planner`, `frontend-planner`를 사용한다.

- `mode=execute`:
  - 모든 behavior change에는 `tdd`를 사용한다.
  - V1 API request, response, unit, error 영향에는 `contract-reviewer`를 사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - 완료 전에는 `reviewer` 또는 `code-review`를 사용한다.

- `mode=gate`:
  - implementation change에는 `reviewer`를 사용한다.
  - contract-adjacent change에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawning이 허용되면 맞는 read-only agent를 spawn한다. 그렇지 않으면 local review path를 수행하고 제한을 명시한다.

- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - 현재 gate evidence, missing tests, risks를 1~3개의 next slice candidate로 변환한다.

- `mode=recover`:
  - 재현 가능한 failure에는 `systematic-debugging`을 사용한다.
  - 사용자가 `mode=execute`로 전환하지 않는 한 파일을 mutate하지 않는다.

## Mode Rules

### mode=plan

- File mutation: forbidden.
- 이전 gate의 `missing tests`, `findings`, `주요 위험`을 next-slice acceptance criteria로 변환한다.
- User-facing output labels:

```text
상태:
목표:
인수 기준:
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
다음 행동:
```

### mode=execute

- File mutation: allowed.
- 실용적이면 valid First RED로 시작한다. 그렇지 않으면 user-facing evidence에 `최초 RED: blocked/no test environment`를 명시한다.
- slice에는 minimum GREEN implementation을 사용한다.
- Output labels:

```text
상태:
변경:
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
주요 위험:
다음 행동:
```

### mode=gate

- File mutation: forbidden.
- Stop hook evidence가 인식할 수 있는 짧은 Korean-first gate review를 만든다.
- Output labels:

```text
상태: Pass|Partial|Fail
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
리뷰:
주요 위험:
다음 행동:
```

### mode=next

- File mutation: forbidden.
- 현재 gate evidence에서 1~3개의 next slice를 추천한다.
- 새 feature expansion보다 unfinished risks, missing tests, contract/data safety gaps를 우선한다.
- backlog recommendation이 필요하면 `.codex/harness/v1 next`를 사용한다.
- Output labels:

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

### mode=recover

- File mutation: 기본적으로 forbidden.
- 자동으로 fix하지 않는다. hook block 또는 verification failure를 recovery plan과 계속 진행하기 위한 최소 evidence로 변환한다.
- Output labels:

```text
상태:
차단 사유:
누락 evidence:
복구 순서:
검증:
다음 행동:
```

## Standard Evidence

slice를 닫을 때 Stop hook과 사람이 같은 evidence를 읽을 수 있도록 다음 label을 정확히 사용한다.

```text
상태: Pass|Partial|Fail
최초 RED: 있음|없음|blocked/no test environment
예상 RED 실패: 확인|미확인|해당 없음
최소 GREEN: 확인|미확인|해당 없음
검증: <command> = pass|fail|not run (<사유>)
tdd-guide: RED validity = Pass|Partial|Fail|not used
contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
reviewer: 지적사항 = none|listed|not run
주요 위험: 없음|<short risk>
다음 행동: <one short action>
```

## Examples Only

이 예시는 prompt 사용법을 설명한다. slice registry가 아니며 valid slice id 집합을 정의하지 않는다.

- `.codex/harness/v1 next`가 `kakao-map-marker-refresh-flow`를 반환했다고 가정한다.
- `$v1-slice-harness kakao-map-marker-refresh-flow plan만 세워줘`
- target dry-run에서는 `<slice-id>`를 반환된 같은 id로 바꾼다:
  `$v1-slice-harness <slice-id> frontend target으로 dry-run 해줘`

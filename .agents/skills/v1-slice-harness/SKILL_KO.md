---
name: v1-slice-harness
description: Home Search V1 slice workflow를 harness launcher로 routing한다. next/plan/dry-run/run/report, worktree orchestration, integration report, push, draft PR, PR lint evidence를 다룬다. "V1 slice", "harness", "next slice", "dry-run", "gate review", "PR", "worktree", "다음 slice", "plan만", "진행해줘", "PR까지", "짧은 gate review", "hook evidence 복구"에 사용한다. planning, tdd, systematic-debugging, code-review를 대체하지 않고 필요 시 해당 mode로 route한다.
---


# V1 Slice Harness

반복 가능한 Home Search V1 slice 운영을 위해 사용하는 launcher router이자 routing guide이다. 긴 prompt를 매번 붙여 넣는 대신 `.codex/harness/v1`을 우선 사용한다.

이 skill은 `planning`, `tdd`, `systematic-debugging`, `code-review`를 대체하지 않는다. launcher가 mode와 target context를 정하고, 실제 planning, RED/GREEN, failure recovery, review 판단은 전문 skill로 넘긴다.

## 기본 원칙

- 동작 변경 전 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO canonical docs 또는 skill을 읽는다.
- `*_KO.md`, `*_KO.local.md` 본문을 읽거나 요약하거나 diff하거나 재사용하지 않는다.
- KO companion이 필요하면 기존 KO 본문을 읽지 않고 canonical `SKILL.md`만 기준으로 재생성한다.
- slice workflow 실행은 짧은 launcher `.codex/harness/v1`을 우선 사용한다.
- low-level script는 debugging 또는 launcher가 필요한 작업을 표현하지 못할 때만 사용한다.
- 사용자가 다음 slice를 모르면 `.codex/harness/v1 next`를 실행하거나 `$v1-slice-harness 다음 slice 골라줘` 흐름을 사용한다.
- named slice 실행 전 `.codex/harness/v1 plan <slice-id>`와 `.codex/harness/v1 dry <slice-id>`를 먼저 실행한다. 사용자가 planning만 명시하면 구현하지 않는다.
- `run`은 plan과 dry-run evidence가 명확할 때만 사용한다.
- `mode=execute`는 implementation 또는 file mutation이 요청될 때만 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`는 기본적으로 파일을 수정하지 않는다.
- `planning-only` 결과는 planning evidence이며 자동 구현 권한이 아니다.
- Hook은 gate일 뿐이다. hook에서 자동 fix, retry, commit, merge, push, full test/build 실행을 설계하거나 주장하지 않는다.
- 최종 사용자-facing review는 짧고 Korean-first로 작성한다. command, path, status token, agent id는 canonical form을 유지한다.

## Slice Identity

- 이 `SKILL.md`는 backlog, preset, slice registry가 아니다.
- 이 `SKILL.md`는 slice id를 정의하지 않으며 slice registry로 취급하면 안 된다.
- `v1-slice-harness`는 `next`, `plan`, `dry`, `run`, `report` launcher operation을 위한 slice workflow router이다. `gate`와 `recover`는 launcher subcommand가 아니라 skill routing mode이다.
- 지속되는 routing policy는 `.codex/harness/skill_routing.py`와 launcher/prompt 파일에 둔다. 이 skill은 thin router로 유지해서 나중에 다른 entrypoint로 harness를 노출하더라도 같은 skill routing behavior를 잃지 않게 한다.
- 실제 slice id는 backlog, preset resolution, recent gate evidence, `.codex/harness/v1 next` 결과에서 온다.
- `<slice-id>`, `<target>`은 이 문서의 placeholder이며 launcher에 literal value로 넘기지 않는다.

## Short Prompt Grammar

짧은 prompt는 사용자 입력 예시일 뿐이다. slice id, preset id, literal command가 아니다.

- `$v1-slice-harness 다음 slice 골라줘`
- `$v1-slice-harness <slice-id> plan만 세워줘`
- `$v1-slice-harness <slice-id> dry-run 해줘`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`
- `$v1-slice-harness <slice-id> 진행해줘`
- `$v1-slice-harness <slice-id> PR까지 만들어줘`
- `$v1-slice-harness <slice-id> push만 해줘`
- `$v1-slice-harness <slice-id> report 다시 보내줘`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`

## Launcher Mapping

짧은 사용자 prompt는 먼저 launcher로 mapping한다.

- next: `.codex/harness/v1 next`
- plan: `.codex/harness/v1 plan <slice-id>`
- dry: `.codex/harness/v1 dry <slice-id>`
- target dry: `.codex/harness/v1 dry <slice-id> --targets <target>`
- run: `.codex/harness/v1 run <slice-id>`
- PR 또는 draft PR: `.codex/harness/v1 run <slice-id> --pr`
- push: `.codex/harness/v1 run <slice-id> --push`
- report: `.codex/harness/v1 report <slice-id>`
- prompt가 `notion`을 언급하면 `--notion`을 붙인다.
- prompt가 `slack`을 언급하면 `--slack`을 붙인다.
- target이 명시되면 `--targets backend`, `--targets frontend`, `--targets both`, `--targets planning-only` 중 하나를 붙인다.

Launcher default:

- 일반 경로에서 사용자는 slice id만 제공한다.
- Preset과 target은 slice id, backlog, explicit option으로 결정된다.
- Branch와 worktree 이름은 자동 생성된다.
- 기본 `run`은 commit, integration branch creation, local report를 수행한다.
- `backend`는 backend worktree만 생성/실행한다.
- `frontend`는 frontend worktree만 생성/실행한다.
- `both`는 backend와 frontend worktree를 생성/실행한다. `--parallel`은 이 target에서만 valid하다.
- `planning-only`는 implementation worktree를 만들지 않고 Codex execute, verification, gate, commit, integration, push, PR automation을 실행하지 않는다.
- Main merge, main push, PR merge, approve, live Open API call, DB migration execution은 자동화하지 않는다.
- Remote push와 draft PR creation은 현재 prompt에서 사용자가 PR/push를 명시했을 때만 수행한다.
- PR 요청은 generated `feat/*-integration` branch를 target으로 하고 기본 draft PR을 만든다. `--pr` flow는 draft PR 생성 전 strict PR lint를 통과해야 한다.
- `dry --pr`은 command expansion과 safety preflight만 수행한다. changed-file PR lint는 실제 integration branch가 존재한 뒤 완전히 enforce된다.
- Notion과 Slack은 optional best-effort notification이며 critical path를 깨면 안 된다. PR creation이 요청된 경우 PR URL notification은 PR URL이 확인된 뒤에만 수행한다.
- 사용자는 prompt를 짧고 자연스럽게 유지하고, launcher가 slice, preset, target, branch, worktree, report detail을 확장한다.

## Launcher Skill Routing

- `.codex/harness/v1 plan`과 `.codex/harness/v1 next`는 `.codex/harness/skill_routing.py`에서 나온 명시적 `사용 skill:` evidence를 렌더링한다.
- `.codex/harness/v1 run`은 Codex execute와 gate prompt에 `Skill routing:`을 주입해서 target agent가 관련 `$tdd`, `$backend-api`, `$frontend-web`, `$api-contract`, `$systematic-debugging`, `$code-review` trigger를 받게 한다.
- Launcher는 이 workflow를 직접 소유한다고 주장하지 않는다. mode와 target context를 선택하고 planning, RED validity, backend/frontend domain rules, contract checks, failure recovery, final review를 소유하는 전문 skill로 route한다.
- `planning-only`는 planning evidence로만 route하며 implementation, verification, gate, commit, integration, push, PR automation으로 이어지면 안 된다.
- Failure 또는 hook-block evidence는 `systematic-debugging`으로 route한다. root cause가 behavior 문제이면 `tdd`로 route하고, recovery가 완료되면 `code-review`로 route한다.

## Lint/Test/Build Handling

- Frontend verification command는 `apps/web/package.json`에 존재하는 script에서만 온다. 현재는 `cd apps/web && npm run test`, `cd apps/web && npm run build`를 사용한다. script가 생기기 전에는 `npm run lint`를 추가하지 않는다.
- Frontend lint가 도입되면 `.codex/harness/v1_flow.py` `KNOWN_VERIFICATION_COMMANDS`, `.codex/harness/presets/*.toml`, `.codex/hooks/post_tool_use_review.py`, `.codex/hooks/stop_verification_gate.py`, `.codex/harness/pr_lint.py`, `.codex/harness/v1_report.py`, `.github/workflows/ci.yml`를 함께 갱신한다.
- Backend verification은 Gradle lint/check task를 추측하지 않는다. canonical backend PR/CI quality gate는 `cd apps/api && ./gradlew backendQualityCheck`이다. `test`는 ad-hoc debugging 또는 explicit plan에만 사용하고 `apps/api/**` 변경의 canonical PR evidence로 쓰지 않는다.
- PR evidence는 changed-file 기반이다. `apps/api/**`는 `backendQualityCheck`, `Coverage: >=90%`, `Docs/OpenAPI: generated + verified`가 필요하다. `apps/web/**`는 `npm run test`, `npm run build`가 필요하다. harness, hook, GitHub workflow, Markdown, KO 변경은 해당 self-test와 KO sync evidence가 필요하다.
- Verification command failure 또는 hook block은 `mode=recover`와 `systematic-debugging`으로 route한다. root cause가 behavior bug이면 regression evidence를 `tdd` 또는 `tdd-guide`로 route한다. fix 후 exact verification line을 기록하고 completion 전 `code-review` 또는 `reviewer`를 사용한다.

## Mode Selection

사용자의 짧은 prompt에서 mode를 선택한다.

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

Mode가 불명확하면 파일을 변경하기 전에 짧은 질문 하나를 한다.

## Required Routing

Routing은 필요한 review capability를 명명한다. Subagent spawn은 현재 operating rules가 허용할 때만 사용한다. 허용되지 않으면 equivalent local skill review를 수행하고 final evidence에 제한을 기록한다.

- `mode=plan`:
  - ambiguous goal에는 `planning`을 사용한다.
  - First RED를 계획할 수 있으면 `tdd`를 사용한다.
  - `code-mapper`, `backend-planner`, `frontend-planner`는 focused read-only research에만 사용한다.
- `mode=execute`:
  - 모든 behavior change에 `tdd`를 사용한다.
  - V1 API request, response, unit, error impact에는 `contract-reviewer`를 사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - completion 전 `reviewer` 또는 `code-review`를 사용한다.
- `mode=gate`:
  - implementation change에는 `reviewer`를 사용한다.
  - contract-adjacent change에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawning이 허용되면 matching read-only agent를 spawn하고, 아니면 local review path를 실행한 뒤 제한을 명시한다.
- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - current gate evidence, missing tests, risks를 1-3개의 next slice candidate로 변환한다.
- `mode=recover`:
  - reproducible failure에는 `systematic-debugging`을 사용한다.
  - 사용자가 `mode=execute`로 전환하지 않으면 파일을 수정하지 않는다.

## Mode Rules

### mode=plan

- File mutation: forbidden.
- 이전 gate `missing tests`, `findings`, `주요 위험`을 next-slice acceptance criteria로 변환한다.
- 사용자 출력 label:

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
- 가능하면 valid First RED로 시작한다. 불가능하면 user-facing evidence에 `최초 RED: blocked/no test environment`를 명시한다.
- slice에 필요한 최소 GREEN implementation만 수행한다.
- 사용자 출력 label:

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
- 사용자 출력 label:

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
- current gate evidence에서 1-3개의 next slice를 추천한다.
- 새 feature expansion보다 unfinished risks, missing tests, contract/data safety gaps를 우선한다.
- backlog recommendation이 필요하면 `.codex/harness/v1 next`를 사용한다.
- 사용자 출력 label:

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

### mode=recover

- File mutation: 기본 forbidden.
- 실패 command, hook block reason, recent report evidence를 먼저 수집한다.
- Root cause를 재현하고 최소 fix path를 결정한다.
- 사용자 출력 label:

```text
상태:
실패 명령:
원인:
복구 계획:
검증:
잔여 위험:
다음 행동:
```

## Completion Evidence

완료 시 다음 evidence를 포함한다.

```text
상태: Pass|Partial|Fail
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
reviewer: 지적사항 = none|listed|not run
주요 위험:
다음 행동:
```

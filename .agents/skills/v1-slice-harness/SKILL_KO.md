---
name: v1-slice-harness
description: 짧은 Korean prompts로 Home Search V1 slice workflow를 조율한다. mode=plan, mode=execute, mode=gate, mode=next, mode=recover를 지원한다. "$v1-slice-harness", "다음 slice 플랜", "현재 slice gate", "짧은 gate review", "hook evidence 복구", "backend/web worktree slice 실행"에 사용한다.
---

# V1 Slice Harness

사용자가 긴 prompt를 붙여넣지 않고 반복 가능한 Home Search V1 slice operation을 원할 때 이 skill을 사용한다. 이 skill은 기존 Home Search skills를 조율하며 `planning`, `tdd`, `systematic-debugging`, `code-review`를 대체하지 않는다.

## Ground Rules

- behavior를 변경하기 전에 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO canonical docs 또는 skills를 읽는다.
- `*_KO.md` bodies를 읽거나, 요약하거나, diff하거나, 재사용하지 않는다.
- KO companion을 업데이트해야 하면 기존 KO body를 읽지 않고 canonical `SKILL.md`에서 재생성한다.
- slice workflow 실행에는 짧은 launcher `.codex/harness/v1`을 우선한다. lower-level scripts는 debugging 또는 launcher가 필요한 operation을 표현할 수 없을 때만 사용한다.
- 사용자가 다음 slice를 모르면 `.codex/harness/v1 next`를 실행하거나 `$v1-slice-harness 다음 slice 골라줘`를 사용한 뒤 작업을 제안한다.
- named slice를 실행하기 전에는 사용자가 planning only를 명시적으로 요청하지 않는 한 `.codex/harness/v1 plan <slice-id>`와 `.codex/harness/v1 dry <slice-id>`를 실행한다.
- plan과 dry-run evidence가 명확해진 뒤에만 `run`을 사용한다.
- implementation 또는 file mutation이 요청될 때만 `mode=execute`를 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`에서는 기본적으로 파일을 편집하지 않는다.
- `planning-only` results는 planning evidence로 다루며 자동 implementation 허가로 보지 않는다.
- Hooks는 gates일 뿐이다. hooks가 automatic fix, retry, commit, merge, push, full test/build execution을 수행하거나 보장한다고 설계하거나 주장하지 않는다.
- 최종 user-facing review는 짧고 Korean-first로 유지한다. commands, paths, status tokens, agent ids는 그대로 유지한다.

## Slice Identity

- 이 `SKILL.md`는 slice ids를 정의하지 않으며 slice registry로 취급하면 안 된다.
- `v1-slice-harness`는 plan, dry-run, run, gate, next, recover operations를 위한 slice workflow router다.
- 실제 slice ids는 backlog, preset resolution, recent gate evidence, `.codex/harness/v1 next` results에서 온다.
- `<slice-id>`와 `<target>`은 이 문서의 placeholders이며 launcher에 그대로 전달하는 literal values가 아니다.

## Launcher UX

짧은 user prompts를 먼저 launcher에 매핑한다:

- `$v1-slice-harness 다음 slice 골라줘` 또는 `다음 작업 추천`:
  `.codex/harness/v1 next`
- `$v1-slice-harness <slice-id> plan만 세워줘`:
  `.codex/harness/v1 plan <slice-id>`
- `$v1-slice-harness <slice-id> dry-run 해줘`:
  `.codex/harness/v1 dry <slice-id>`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`:
  `.codex/harness/v1 dry <slice-id> --targets <target>`
- `$v1-slice-harness <slice-id> 진행해줘`:
  `.codex/harness/v1 run <slice-id>`
- `$v1-slice-harness <slice-id> PR까지 만들어줘` 또는 `draft PR 생성해줘`:
  `.codex/harness/v1 run <slice-id> --pr`
- `$v1-slice-harness <slice-id> push만 해줘`:
  `.codex/harness/v1 run <slice-id> --push`
- prompt가 `notion`을 언급하면 `--notion`을 붙인다.
- prompt가 `slack`을 언급하면 `--slack`을 붙인다.
- report resend를 요청하면 `.codex/harness/v1 report <slice-id>`를 사용한다.
- prompt가 target을 명명하면 `--targets backend`, `--targets frontend`, `--targets both`, `--targets planning-only` 중 하나를 붙인다.

Launcher defaults:

- common path에서는 사용자가 slice id만 제공한다.
- Preset과 target은 slice id, backlog, explicit options에서 resolve된다.
- Branch와 worktree names는 자동 생성된다.
- 기본 `run`은 commit, integration branch creation, local report를 수행한다.
- `backend`는 backend worktree만 생성/실행한다.
- `frontend`는 frontend worktree만 생성/실행한다.
- `both`는 backend와 frontend worktrees를 생성/실행한다. `--parallel`은 이 target에서만 valid하다.
- `planning-only`는 implementation worktree를 만들지 않으며 Codex execute, verification, gate, commit, integration, push, PR automation을 실행하면 안 된다.
- Main merge, main push, PR merge, approve, live Open API calls, DB migration execution은 절대 automatic이 아니다.
- Remote push와 draft PR creation은 사용자가 현재 prompt에서 PR/push를 명시적으로 요청할 때만 발생한다.
- PR requests는 항상 생성된 `feat/*-integration` branch를 target으로 하고 기본적으로 draft PR을 만든다.
- Notion과 Slack은 optional best-effort notifications이며 critical path를 깨면 안 된다. PR creation 요청 시 PR URL notification은 PR URL을 알게 된 뒤에만 발생한다.
- 사용자는 짧고 자연스러운 prompts를 유지하면 된다. launcher가 slice, preset, target, branch, worktree, report details를 확장한다.

## Mode Selection

사용자의 짧은 prompt에서 mode를 선택한다:

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

mode가 unclear하면 파일 변경 전에 concise question 하나를 묻는다.

## Required Routing

Routing은 필요한 review capability를 명명한다. active operating rules가 허용할 때만 subagents를 spawn한다. 그렇지 않으면 equivalent local skill review를 수행하고 final evidence에 limitation을 기록한다.

- `mode=plan`:
  - ambiguous goals에는 `planning`을 사용한다.
  - First RED를 plan할 수 있으면 `tdd`를 사용한다.
  - focused read-only research에만 `code-mapper`, `backend-planner`, `frontend-planner`를 사용한다.

- `mode=execute`:
  - 모든 behavior changes에 `tdd`를 사용한다.
  - V1 API request, response, unit, error impact에는 `contract-reviewer`를 사용한다.
  - RED validity가 uncertain하면 `tdd-guide`를 사용한다.
  - completion 전에 `reviewer` 또는 `code-review`를 사용한다.

- `mode=gate`:
  - implementation changes에는 `reviewer`를 사용한다.
  - contract-adjacent changes에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawning이 허용되면 matching read-only agent를 spawn한다. 그렇지 않으면 local review path를 실행하고 limitation을 말한다.

- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - current gate evidence, missing tests, risks를 하나에서 세 개의 next slice candidates로 변환한다.

- `mode=recover`:
  - reproducible failures에는 `systematic-debugging`을 사용한다.
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
- practical하면 valid First RED로 시작한다. 그렇지 않으면 user-facing evidence에 `최초 RED: blocked/no test environment`를 명시한다.
- slice에 필요한 최소 GREEN implementation을 사용한다.
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
- Stop hook evidence가 인식할 수 있는 짧은 Korean-first gate review를 작성한다.
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
- current gate evidence에서 하나에서 세 개의 next slices를 추천한다.
- new feature expansion보다 unfinished risks, missing tests, contract/data safety gaps를 우선한다.
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
- 자동으로 fix하지 않는다. hook blocks 또는 verification failures를 recovery plan과 계속 진행하는 데 필요한 minimum evidence로 바꾼다.
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

slice를 닫을 때 Stop hook과 사람이 같은 evidence를 읽을 수 있도록 다음 exact labels를 사용한다:

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

## Short Prompt Grammar

- `$v1-slice-harness 다음 slice 골라줘`
- `$v1-slice-harness <slice-id> plan만 세워줘`
- `$v1-slice-harness <slice-id> dry-run 해줘`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`
- `$v1-slice-harness <slice-id> 진행해줘`
- `$v1-slice-harness <slice-id> PR까지 만들어줘`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`

## Examples Only

이 examples는 prompt usage를 설명한다. slice registry가 아니며 valid slice ids 집합을 정의하지 않는다.

- `.codex/harness/v1 next`가 `kakao-map-marker-refresh-flow`를 반환했다고 가정한다.
- `$v1-slice-harness kakao-map-marker-refresh-flow plan만 세워줘`
- target dry-run의 경우 `<slice-id>`를 같은 returned id로 바꾼다:
  `$v1-slice-harness <slice-id> frontend target으로 dry-run 해줘`

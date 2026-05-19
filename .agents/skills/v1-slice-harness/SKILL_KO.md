---
name: v1-slice-harness
description: 짧은 한국어 프롬프트로 Home Search V1 slice workflow를 오케스트레이션한다. mode=plan, mode=execute, mode=gate, mode=next, mode=recover를 지원한다. "$v1-slice-harness", "다음 slice 플랜", "현재 slice gate", "짧은 gate review", "hook evidence 복구", "backend/web worktree slice 실행"에 사용한다.
---

# V1 Slice Harness

사용자가 긴 프롬프트를 붙여 넣지 않고 반복 가능한 Home Search V1 slice
작업을 원할 때 이 skill을 사용한다. 이 skill은 기존 Home Search skill을
오케스트레이션한다. `planning`, `tdd`, `systematic-debugging`, 또는
`code-review`를 대체하지 않는다.

## Ground Rules

- 동작을 바꾸기 전에 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO 공식
  문서 또는 skill을 읽는다.
- `*_KO.md` 본문을 읽거나, 요약하거나, diff하거나, 재사용하지 않는다.
- KO companion을 업데이트해야 하면 기존 KO 본문을 읽지 않고 canonical
  `SKILL.md`에서 다시 생성한다.
- slice workflow 실행에는 짧은 launcher `.codex/harness/v1`을 우선한다.
  launcher가 필요한 작업을 표현할 수 없거나 debugging이 필요할 때만
  lower-level script를 사용한다.
- 사용자가 다음 slice를 모르면 작업을 제안하기 전에 `.codex/harness/v1 next`
  를 실행하거나 `$v1-slice-harness 다음 slice 골라줘`를 사용한다.
- 이름이 있는 slice를 실행하기 전에는 사용자가 planning only를 명시적으로
  요청하지 않은 한 `.codex/harness/v1 plan <slice-id>`를 실행하고 이어서
  `.codex/harness/v1 dry <slice-id>`를 실행한다.
- `run`은 plan과 dry-run evidence가 명확한 뒤에만 사용한다.
- 구현 또는 파일 변경이 요청된 경우에만 `mode=execute`를 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`에서는 기본적으로
  파일을 수정하지 않는다.
- `planning-only` 결과는 planning evidence로 다루며, 자동 구현 권한으로
  보지 않는다.
- Hook은 gate일 뿐이다. hook에서 자동 fix, retry, commit, merge, push, 또는
  전체 test/build 실행을 설계하거나 주장하지 않는다.
- 최종 사용자-facing review는 짧은 한국어로 유지한다.

## Slice Identity

- 이 `SKILL.md`는 slice id를 정의하지 않으며 slice registry로 취급하면 안 된다.
- `v1-slice-harness`는 plan, dry-run, run, gate, next, recover 작업을 위한
  slice workflow router다.
- 실제 slice id는 backlog, preset resolution, recent gate evidence, 또는
  `.codex/harness/v1 next` 결과에서 온다.
- `<slice-id>`와 `<target>`은 이 문서의 placeholder이며, launcher에 넘기는
  literal value가 아니다.

## Launcher UX

짧은 사용자 프롬프트를 먼저 launcher에 매핑한다.

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
- prompt에 `notion`이 있으면 `--notion`을 추가한다.
- prompt에 `slack`이 있으면 `--slack`을 추가한다.
- prompt가 report resend를 요청하면
  `.codex/harness/v1 report <slice-id>`를 사용한다.
- prompt가 target을 이름으로 지정하면 `--targets backend`,
  `--targets frontend`, `--targets both`, 또는 `--targets planning-only`를
  추가한다.

Launcher defaults:

- 일반 경로에서는 사용자가 slice id만 제공한다.
- Preset과 target은 slice id, backlog, 또는 explicit option에서 해석된다.
- Branch와 worktree 이름은 자동 생성된다.
- 기본 `run`은 commit, integration branch creation, local report를 수행한다.
- `backend`는 backend worktree만 생성하고 실행한다.
- `frontend`는 frontend worktree만 생성하고 실행한다.
- `both`는 backend와 frontend worktree를 생성하고 실행한다. `--parallel`은
  이 target에서만 유효하다.
- `planning-only`는 implementation worktree를 만들지 않으며 Codex execute,
  verification, gate, commit, integration, push, PR automation을 실행하면 안 된다.
- Main merge, main push, PR merge, approve, live Open API call, DB migration
  execution은 절대 자동으로 수행하지 않는다.
- Remote push와 draft PR creation은 사용자가 현재 prompt에서 PR/push를
  명시적으로 요청한 경우에만 수행한다.
- PR 요청은 항상 생성된 `feat/*-integration` branch를 대상으로 하며 기본적으로
  draft PR을 만든다.
- Notion과 Slack은 optional best-effort notification이며 critical path를
  깨면 안 된다. PR creation이 요청된 경우 PR URL notification은 PR URL을 알게 된
  뒤에만 수행한다.
- 사용자는 prompt를 짧고 자연스럽게 유지한다. launcher가 slice, preset,
  target, branch, worktree, report 세부사항을 확장한다.

## Mode Selection

사용자의 짧은 prompt에서 mode를 고른다.

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

mode가 불명확하면 파일을 바꾸기 전에 간결한 질문 하나를 한다.

## Required Routing

Routing은 필요한 review capability를 이름으로 지정한다. Subagent는 active
operating rule이 허용할 때만 spawn한다. 그렇지 않으면 동등한 local skill
review를 수행하고 final evidence에 제한 사항을 기록한다.

- `mode=plan`:
  - 모호한 goal에는 `planning`을 사용한다.
  - First RED를 계획할 수 있으면 `tdd`를 사용한다.
  - 집중적인 read-only research에만 `code-mapper`, `backend-planner`, 또는
    `frontend-planner`를 사용한다.

- `mode=execute`:
  - 모든 behavior change에는 `tdd`를 사용한다.
  - V1 API request, response, unit, error 영향에는 `contract-reviewer`를 사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - 완료 전에 `reviewer` 또는 `code-review`를 사용한다.

- `mode=gate`:
  - implementation change에는 `reviewer`를 사용한다.
  - contract-adjacent change에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawning이 허용되면 일치하는 read-only agent를 spawn한다.
    그렇지 않으면 local review path를 실행하고 제한 사항을 명시한다.

- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - 현재 gate evidence, missing test, risk를 1~3개의 next slice 후보로 변환한다.

- `mode=recover`:
  - 재현 가능한 failure에는 `systematic-debugging`을 사용한다.
  - 사용자가 `mode=execute`로 전환하지 않으면 파일을 변경하지 않는다.

## Mode Rules

### mode=plan

- File mutation: 금지.
- 이전 gate의 `missing tests`, `findings`, `주요 위험`을 next-slice acceptance
  criteria로 변환한다.
- Output labels:

```text
상태:
목표:
Acceptance Criteria:
First RED:
Expected RED failure:
Minimum GREEN:
검증:
다음 행동:
```

### mode=execute

- File mutation: 허용.
- 가능하면 valid First RED로 시작한다. 그렇지 않으면
  `First RED: blocked/no test environment`라고 명시한다.
- slice를 위한 minimum GREEN implementation을 사용한다.
- Output labels:

```text
상태:
변경:
First RED:
Expected RED failure:
Minimum GREEN:
검증:
주요 위험:
다음 행동:
```

### mode=gate

- File mutation: 금지.
- Stop hook evidence가 인식할 수 있는 짧은 한국어 gate review를 만든다.
- Output labels:

```text
상태: Pass|Partial|Fail
First RED:
Expected RED failure:
Minimum GREEN:
검증:
리뷰:
주요 위험:
다음 행동:
```

### mode=next

- File mutation: 금지.
- 현재 gate evidence에서 1~3개의 next slice를 추천한다.
- 새 feature expansion보다 unfinished risk, missing test, contract/data safety
  gap을 우선한다.
- backlog recommendation이 필요하면 `.codex/harness/v1 next`를 사용한다.
- Output labels:

```text
상태:
현재:
다음 Slice:
Acceptance Criteria:
검증:
다음 행동:
```

### mode=recover

- File mutation: 기본적으로 금지.
- 자동으로 고치지 않는다. Hook block 또는 verification failure를 recovery plan과
  계속 진행하는 데 필요한 minimum evidence로 바꾼다.
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

slice를 닫을 때 Stop hook과 사람이 같은 evidence를 읽을 수 있도록 아래 정확한
label을 사용한다.

```text
상태: Pass|Partial|Fail
First RED: 있음|없음|blocked/no test environment
Expected RED failure: 확인|미확인|해당 없음
Minimum GREEN: 확인|미확인|해당 없음
검증: <command> = pass|fail|not run (<reason>)
tdd-guide: RED validity = Pass|Partial|Fail|not used
contract-reviewer: Gate decision = Pass|Partial|Fail|not needed
reviewer: Findings = none|listed|not run
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

이 예시는 prompt 사용법을 설명할 뿐이다. slice registry가 아니며 valid slice id
set을 정의하지 않는다.

- `.codex/harness/v1 next`가 `kakao-map-marker-refresh-flow`를 반환했다고 가정한다.
- `$v1-slice-harness kakao-map-marker-refresh-flow plan만 세워줘`
- Target dry-run에서는 같은 반환 id로 `<slice-id>`를 대체한다:
  `$v1-slice-harness <slice-id> frontend target으로 dry-run 해줘`

---
name: v1-slice-harness
description: 짧은 한국어 프롬프트로 Home Search V1 slice workflow를 오케스트레이션한다. mode=plan, mode=execute, mode=gate, mode=next, mode=recover를 지원한다. "$v1-slice-harness", "다음 slice 플랜", "현재 slice gate", "짧은 gate review", "hook evidence 복구", "backend/web worktree slice 실행"에 사용한다.
---

# V1 Slice Harness

사용자가 긴 프롬프트를 붙여 넣지 않고 반복 가능한 Home Search V1 slice
운영을 원할 때 이 skill을 사용한다. 이 skill은 기존 Home Search skill을
오케스트레이션하며 `planning`, `tdd`, `systematic-debugging`,
`code-review`를 대체하지 않는다.

## 기본 규칙

- 동작을 변경하기 전에 `AGENTS.md`, `ai-docs/README.md`, 관련 non-KO
  canonical 문서 또는 skill을 읽는다.
- `*_KO.md` 본문은 읽거나, 요약하거나, diff하거나, 재사용하지 않는다.
- slice workflow 실행에는 짧은 launcher `.codex/harness/v1`을 우선한다.
  launcher로 표현할 수 없거나 디버깅이 필요한 경우에만 하위 script를
  직접 사용한다.
- 구현 또는 파일 변경이 요청된 경우에만 `mode=execute`를 사용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`에서는 기본적으로
  파일을 수정하지 않는다.
- Hook은 gate일 뿐이다. hook에서 자동 fix, retry, commit, merge, push,
  전체 test/build 실행을 설계하거나 주장하지 않는다.
- 최종 사용자-facing 리뷰는 짧은 한국어로 유지한다.

## Launcher UX

짧은 사용자 프롬프트는 먼저 launcher로 매핑한다.

- `$v1-slice-harness <slice> dry-run 해줘`:
  `.codex/harness/v1 dry <slice>`
- `$v1-slice-harness <slice> 진행해줘`:
  `.codex/harness/v1 run <slice>`
- 프롬프트에 `notion`이 있으면 `--notion`을 붙인다.
- 프롬프트에 `slack`이 있으면 `--slack`을 붙인다.
- report 재전송을 요청하면 `.codex/harness/v1 report <slice>`를 사용한다.

Launcher 기본값:

- 일반 경로에서 사용자는 slice 이름만 제공한다.
- preset은 slice 이름 또는 `--preset`으로 결정한다.
- branch와 worktree 이름은 자동 생성한다.
- 기본 `run`은 commit, integration branch 생성, local report를 수행한다.
- main merge, push, live Open API 호출, DB migration 실행은 자동으로 하지
  않는다.
- Notion과 Slack은 optional best-effort 알림이며 critical path를 깨지
  않아야 한다.

## Mode 선택

사용자의 짧은 프롬프트에서 mode를 선택한다.

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

mode가 불명확하면 파일을 변경하기 전에 짧은 질문 하나를 한다.

## 필수 라우팅

라우팅은 필요한 review capability를 지정한다. active operating rule이
허용할 때만 subagent를 spawn한다. 그렇지 않으면 동등한 local skill review를
수행하고 최종 evidence에 제한 사항을 기록한다.

- `mode=plan`:
  - 모호한 목표에는 `planning`을 사용한다.
  - First RED를 계획할 수 있으면 `tdd`를 사용한다.
  - `code-mapper`, `backend-planner`, `frontend-planner`는 focused read-only
    research에만 사용한다.

- `mode=execute`:
  - 모든 behavior change에 `tdd`를 사용한다.
  - V1 API request, response, unit, error 영향에는 `contract-reviewer`를
    사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - 완료 전 `reviewer` 또는 `code-review`를 사용한다.

- `mode=gate`:
  - 구현 변경에는 `reviewer`를 사용한다.
  - contract 인접 변경에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawn이 허용되면 맞는 read-only agent를 spawn한다. 아니면
    local review path를 실행하고 제한 사항을 명시한다.

- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - 현재 gate evidence, missing tests, risks를 하나에서 세 개의 다음 slice
    후보로 변환한다.

- `mode=recover`:
  - 재현 가능한 실패에는 `systematic-debugging`을 사용한다.
  - 사용자가 `mode=execute`로 전환하지 않는 한 파일을 수정하지 않는다.

## Mode 규칙

### mode=plan

- 파일 변경: 금지.
- 이전 gate의 `missing tests`, `findings`, `주요 위험`을 다음 slice
  acceptance criteria로 변환한다.
- 출력 label:

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

- 파일 변경: 허용.
- 실용적이면 valid First RED로 시작한다. 아니면
  `First RED: blocked/no test environment`라고 명시한다.
- slice에 필요한 최소 GREEN 구현을 사용한다.
- 출력 label:

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

- 파일 변경: 금지.
- Stop hook evidence가 인식할 수 있는 짧은 한국어 gate review를 만든다.
- 출력 label:

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

- 파일 변경: 금지.
- 현재 gate evidence에서 다음 slice를 하나에서 세 개 추천한다.
- 새 feature 확장보다 unfinished risks, missing tests, contract/data safety
  gap을 우선한다.
- 출력 label:

```text
상태:
현재:
다음 Slice:
Acceptance Criteria:
검증:
다음 행동:
```

### mode=recover

- 파일 변경: 기본적으로 금지.
- 자동으로 수정하지 않는다. hook block 또는 verification failure를 recovery
  plan과 계속 진행하기 위한 minimum evidence로 변환한다.
- 출력 label:

```text
상태:
차단 사유:
누락 evidence:
복구 순서:
검증:
다음 행동:
```

## 표준 Evidence

slice를 닫을 때 Stop hook과 사람이 같은 evidence를 읽을 수 있도록 정확히
아래 label을 사용한다.

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

## Short Prompts

- `$v1-slice-harness map-contract-hardening dry-run 해줘`
- `$v1-slice-harness map-contract-hardening 진행해줘`
- `$v1-slice-harness map-contract-hardening 진행해줘. notion/slack까지 알림 보내줘`
- `$v1-slice-harness map-contract-hardening report 다시 보내줘`
- `$v1-slice-harness 이전 gate 기준 다음 slice 플랜`
- `$v1-slice-harness 이 worktree에서 backend slice 실행`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`
- `$v1-slice-harness 이전 리뷰 기준으로 plan 다시 세워줘`

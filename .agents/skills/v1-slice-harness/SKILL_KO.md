---
name: v1-slice-harness
description: 짧은 한국어 프롬프트로 Home Search V1 slice 반복 workflow를 운영한다. mode=plan, mode=execute, mode=gate, mode=next, mode=recover를 지원한다.
---

# V1 Slice Harness

긴 프롬프트를 매번 붙여넣지 않고 Home Search V1 slice를 반복 운영할 때 이 skill을 사용한다. 이 skill은 기존 `planning`, `tdd`, `systematic-debugging`, `code-review` skill을 대체하지 않고 호출 순서와 출력 형식을 조율한다.

## 기본 규칙

- 동작 변경 전 `AGENTS.md`, `ai-docs/README.md`, 관련 canonical 문서와 기존 skill을 확인한다.
- `*_KO.md` 본문은 읽거나 요약하거나 diff하거나 재사용하지 않는다.
- 파일 수정은 `mode=execute`에서만 허용한다.
- `mode=plan`, `mode=gate`, `mode=next`, `mode=recover`는 기본적으로 파일을 수정하지 않는다.
- hook은 gate 역할만 한다. 자동 수정, 자동 retry, commit, merge, push, 전체 test/build 실행을 맡기지 않는다.
- 사용자에게 남기는 리뷰는 짧은 한국어 형식으로 작성한다.

## Mode 선택

사용자의 짧은 프롬프트에서 mode를 고른다.

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

mode가 불명확하면 파일 수정 전에 짧게 한 번만 질문한다.

## Required Routing

Routing은 반드시 적용해야 하는 검토 능력을 의미한다. subagent spawn은 현재 활성 운영 규칙이 허용할 때만 수행한다. 허용되지 않으면 같은 목적의 local skill review를 수행하고 최종 evidence에 제한 사항을 남긴다.

- `mode=plan`:
  - 목표가 모호하면 `planning`을 사용한다.
  - First RED를 계획할 수 있으면 `tdd`를 사용한다.
  - `code-mapper`, `backend-planner`, `frontend-planner`는 집중된 읽기 전용 research에만 사용한다.

- `mode=execute`:
  - 모든 behavior change에는 `tdd`를 사용한다.
  - V1 API request, response, unit, error 영향에는 `contract-reviewer`를 사용한다.
  - RED validity가 불확실하면 `tdd-guide`를 사용한다.
  - 완료 전 `reviewer` 또는 `code-review`를 사용한다.

- `mode=gate`:
  - 구현 변경에는 `reviewer`를 사용한다.
  - contract-adjacent 변경에는 `contract-reviewer`를 사용한다.
  - First RED validity에는 `tdd-guide`를 사용한다.
  - subagent spawn이 허용되면 해당 read-only agent를 spawn하고, 아니면 local review path를 실행한 뒤 제한 사항을 남긴다.

- `mode=next`:
  - 기본적으로 spawn하지 않는다.
  - 현재 gate evidence, missing tests, risks를 다음 slice 후보 1~3개로 변환한다.

- `mode=recover`:
  - 재현 가능한 실패에는 `systematic-debugging`을 사용한다.
  - 사용자가 `mode=execute`로 전환하기 전에는 파일을 수정하지 않는다.

## Mode 규칙

### mode=plan

- 파일 수정: 금지.
- 이전 gate의 missing tests, findings, 주요 위험을 다음 slice acceptance criteria로 바꾼다.
- 출력 형식:

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

- 파일 수정: 허용.
- 가능한 경우 First RED부터 시작한다. 불가능하면 `First RED: blocked/no test environment`를 남긴다.
- slice를 통과시키는 Minimum GREEN만 구현한다.
- 출력 형식:

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

- 파일 수정: 금지.
- Stop hook과 사람이 함께 읽을 수 있는 짧은 한국어 gate review를 만든다.
- 출력 형식:

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

- 파일 수정: 금지.
- 현재 gate evidence를 기준으로 다음 slice를 1~3개만 제안한다.
- 새 기능 확장보다 미완료 위험, missing tests, contract/data safety gap을 먼저 다룬다.
- 출력 형식:

```text
상태:
현재:
다음 Slice:
Acceptance Criteria:
검증:
다음 행동:
```

### mode=recover

- 파일 수정: 기본 금지.
- 자동 복구하지 않는다. hook block 또는 검증 실패를 복구 계획과 필요한 최소 evidence로 바꾼다.
- 출력 형식:

```text
상태:
차단 사유:
누락 evidence:
복구 순서:
검증:
다음 행동:
```

## 표준 Evidence

slice 종료 시 Stop hook과 사람이 같은 evidence를 읽을 수 있도록 다음 label을 사용한다.

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

## 짧은 호출 예시

- `$v1-slice-harness 이전 gate 기준 다음 slice 플랜`
- `$v1-slice-harness 이 worktree에서 backend slice 실행`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`
- `$v1-slice-harness 이전 리뷰 기준으로 plan 다시 세워줘`

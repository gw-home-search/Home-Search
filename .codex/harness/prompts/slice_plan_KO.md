# Work Plan Prompt KO

> KO 생성 기준: canonical source only
> Source: `.codex/harness/prompts/slice_plan.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.codex/harness/prompts/slice_plan.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# Work Plan Prompt


home-search-harness mode=plan

Use this prompt when the user asks for an execution brief for one Home Search
work item before dry-run or run.

Inputs:
- Work id from `.codex/harness/worklog.toml`
- Optional recent report evidence from `.codex/harness/reports/*.json` or
  `--from-report`
- Optional target filter from `--targets`

Skill routing:
- $planning: produce the decision-complete work item plan and acceptance criteria.
- $vertical-slice-implementation: confirm the plan is a thin independently
  verifiable work item before implementation starts.
- $tdd: define First RED, Expected RED failure, and Minimum GREEN before execution.
- $api-contract: check public API URL, request, response, unit, and error compatibility when backend or frontend behavior can be affected.

Instructions:
- Render the work item goal, acceptance criteria, First RED candidates, expected RED
  failure, minimum GREEN, verification, stop conditions, and next command.
- Convert recent gate risks, missing tests, contract gaps, and data-safety gaps
  into additional acceptance criteria and First RED candidates.
- Do not mutate files, create branches, create worktrees, commit, integrate,
  push, or open PRs.
- If the worklog target is `planning-only`, do not suggest automatic
  implementation.
- Keep public API URL and response compatibility explicit when the work item touches
  backend or frontend behavior.

User-facing output labels:

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

# Slice Plan Prompt


$v1-slice-harness mode=plan

Use this prompt when the user asks for an execution brief for one Home Search V1
slice before dry-run or run.

Inputs:
- Slice id from `.codex/harness/slices/backlog.toml`
- Optional recent report evidence from `.codex/harness/reports/*.json` or
  `--from-report`
- Optional target filter from `--targets`

Skill routing:
- $planning: produce the decision-complete slice plan and acceptance criteria.
- $vertical-slice-implementation: confirm the plan is a thin independently
  verifiable V1 slice before implementation starts.
- $tdd: define First RED, Expected RED failure, and Minimum GREEN before execution.
- $api-contract: check V1 API URL, request, response, unit, and error compatibility when backend or frontend behavior can be affected.

Instructions:
- Render the slice goal, acceptance criteria, First RED candidates, expected RED
  failure, minimum GREEN, verification, stop conditions, and next command.
- Convert recent gate risks, missing tests, contract gaps, and data-safety gaps
  into additional acceptance criteria and First RED candidates.
- Do not mutate files, create branches, create worktrees, commit, integrate,
  push, or open PRs.
- If the backlog target is `planning-only`, do not suggest automatic
  implementation.
- Keep V1 API URL and response compatibility explicit when the slice touches
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

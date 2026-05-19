# Slice Plan Prompt

$v1-slice-harness mode=plan

Use this prompt when the user asks for an execution brief for one Home Search V1
slice before dry-run or run.

Inputs:
- Slice id from `.codex/harness/slices/backlog.toml`
- Optional recent report evidence from `.codex/harness/reports/*.json` or
  `--from-report`
- Optional target filter from `--targets`

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

Output labels:

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

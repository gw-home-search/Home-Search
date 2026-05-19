# Next Slice Planning Prompt

$v1-slice-harness mode=next

Use this prompt when the user asks for the next Home Search V1 slice without
knowing the slice id.

Inputs:
- Backlog: `.codex/harness/slices/backlog.toml`
- Recent report evidence: `.codex/harness/reports/*.json` or `--from-report`
- Optional git context: `--from-git`

Instructions:
- Recommend one to three unfinished slices.
- Mark exactly one slice as recommended.
- Prefer unfinished gate risks, missing tests, contract gaps, and data-safety
  gaps before new feature expansion.
- Do not mutate files, create branches, create worktrees, commit, push, or open
  PRs.
- Keep each candidate target explicit: `backend`, `frontend`, `both`, or
  `planning-only`.
- For `planning-only`, do not continue into implementation automatically.

Output labels:

```text
상태:
현재:
다음 Slice:
Acceptance Criteria:
검증:
다음 행동:
```

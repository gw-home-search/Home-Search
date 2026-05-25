# Next Work Planning Prompt


home-search-harness mode=next

Use this prompt when the user asks for the next Home Search work item without
knowing the work id.

Inputs:
- Worklog: `.codex/harness/worklog.toml`
- Recent report evidence: `.codex/harness/reports/*.json` or `--from-report`
- Optional git context: `--from-git`

Skill routing:
- $planning: turn worklog, recent report evidence, risks, and acceptance criteria into next-work candidates.
- $code-review: use only when recent gate findings need findings-first interpretation before choosing the next work item.

Instructions:
- Recommend one to three unfinished work items.
- Mark exactly one work item as recommended.
- If a merged PR appears to be stale in worklog status, check
  `.codex/harness/home sync-worklog --merged --dry-run` before recommending it
  as unfinished work.
- Prefer unfinished gate risks, missing tests, contract gaps, and data-safety
  gaps before new feature expansion.
- Do not mutate files, create branches, create worktrees, commit, push, or open
  PRs.
- Keep each candidate target explicit: `backend`, `frontend`, `both`, or
  `planning-only`.
- For `planning-only`, do not continue into implementation automatically.

User-facing output labels:

```text
상태:
현재:
다음 작업 후보:
인수 기준:
검증:
다음 행동:
```

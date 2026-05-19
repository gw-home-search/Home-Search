# Next Slice Planning Prompt

$v1-slice-harness mode=next

사용자가 slice id를 모르는 상태에서 다음 Home Search V1 slice를 요청할 때 이 prompt를 사용한다.

Inputs:
- Backlog: `.codex/harness/slices/backlog.toml`
- Recent report evidence: `.codex/harness/reports/*.json` 또는 `--from-report`
- Optional git context: `--from-git`

지시:
- 완료되지 않은 slice를 하나에서 세 개 추천한다.
- 정확히 하나의 slice만 recommended로 표시한다.
- 새 feature expansion보다 미완료 gate risks, missing tests, contract gaps, data-safety gaps를 우선한다.
- 파일 mutation, branch 생성, worktree 생성, commit, push, PR 생성을 하지 않는다.
- 각 candidate target을 `backend`, `frontend`, `both`, `planning-only` 중 하나로 명확히 표시한다.
- `planning-only`는 자동으로 implementation으로 이어가지 않는다.

User-facing output labels:

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

# Next Slice Planning Prompt


$v1-slice-harness mode=next

사용자가 slice id를 모르는 상태에서 다음 Home Search V1 slice를 요청할 때
이 prompt를 사용한다.

입력:
- Backlog: `.codex/harness/slices/backlog.toml`
- Recent report evidence: `.codex/harness/reports/*.json` 또는 `--from-report`
- Optional git context: `--from-git`

Skill routing:
- $planning: backlog, recent report evidence, risks, acceptance criteria를 다음 slice 후보로 바꾼다.
- $code-review: 최근 gate findings가 다음 slice 선택 전에 findings-first 해석이 필요할 때만 사용한다.

지시:
- unfinished slice를 1개에서 3개까지 추천한다.
- 추천 slice는 정확히 하나만 표시한다.
- merged PR이 있는데 backlog status가 stale로 보이면 unfinished work로 추천하기 전에
  `.codex/harness/v1 sync-backlog --merged --dry-run`으로 확인한다.
- 새 기능 확장보다 unfinished gate risks, missing tests, contract gaps,
  data-safety gaps를 우선한다.
- 파일 수정, branch/worktree 생성, commit, push, PR 생성을 하지 않는다.
- 각 candidate target을 `backend`, `frontend`, `both`, `planning-only`로 명시한다.
- `planning-only`는 자동 구현으로 이어가지 않는다.

사용자 노출 output labels:

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

# 다음 Slice 계획 프롬프트

$v1-slice-harness mode=next

사용자가 slice id를 모르는 상태에서 다음 Home Search V1 slice를 요청할 때 이 프롬프트를 사용한다.

입력:
- Backlog: `.codex/harness/slices/backlog.toml`
- 최근 report evidence: `.codex/harness/reports/*.json` 또는 `--from-report`
- 선택 git context: `--from-git`

지시:
- 완료되지 않은 slice를 1~3개 추천한다.
- 추천 slice는 정확히 1개만 표시한다.
- 새 기능 확장보다 미해결 gate risk, 누락 test, contract gap, data-safety gap을 우선한다.
- 파일 수정, branch 생성, worktree 생성, commit, push, PR 생성을 하지 않는다.
- 각 후보의 target을 `backend`, `frontend`, `both`, `planning-only` 중 하나로 명시한다.
- `planning-only`는 자동 구현으로 이어가지 않는다.

출력 label:

```text
상태:
현재:
다음 Slice:
Acceptance Criteria:
검증:
다음 행동:
```

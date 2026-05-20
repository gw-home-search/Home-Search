# Next Slice Planning Prompt


`$v1-slice-harness mode=next`

사용자가 slice id를 모르는 상태로 다음 Home Search V1 slice를 요청할 때 사용한다.

## 입력

- Backlog: `.codex/harness/slices/backlog.toml`
- Recent report evidence: `.codex/harness/reports/*.json` 또는 `--from-report`
- Optional git context: `--from-git`

## Skill routing

- `$planning`: backlog, recent report evidence, risks, acceptance criteria를 next-slice 후보로 변환한다.
- `$code-review`: 최근 gate finding을 다음 slice 선택 전에 findings-first로 해석해야 할 때만 사용한다.

## 지시사항

- 완료되지 않은 slice를 1-3개 추천한다.
- 정확히 하나의 slice를 recommended로 표시한다.
- 새 feature 확장보다 unfinished gate risks, missing tests, contract gaps, data-safety gaps를 우선한다.
- 파일 수정, branch 생성, worktree 생성, commit, push, PR open을 하지 않는다.
- 각 candidate target을 `backend`, `frontend`, `both`, `planning-only` 중 하나로 명시한다.
- `planning-only`는 implementation으로 자동 진행하지 않는다.

## 사용자 출력 label

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

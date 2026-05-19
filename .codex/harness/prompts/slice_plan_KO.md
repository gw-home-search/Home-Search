# Slice 계획 프롬프트

$v1-slice-harness mode=plan

dry-run 또는 run 전에 단일 Home Search V1 slice의 실행 brief를 요청할 때 이 프롬프트를 사용한다.

입력:
- `.codex/harness/slices/backlog.toml`의 slice id
- `.codex/harness/reports/*.json` 또는 `--from-report`의 선택 최근 report evidence
- `--targets`의 선택 target filter

지시:
- slice 목표, acceptance criteria, First RED 후보, expected RED failure, minimum GREEN, verification, stop conditions, next command를 렌더링한다.
- 최근 gate risk, 누락 test, contract gap, data-safety gap을 추가 acceptance criteria와 First RED 후보로 변환한다.
- 파일 수정, branch 생성, worktree 생성, commit, integration, push, PR 생성을 하지 않는다.
- backlog target이 `planning-only`이면 자동 구현을 제안하지 않는다.
- slice가 backend 또는 frontend behavior를 건드리면 V1 API URL과 response compatibility를 명시한다.

출력 label:

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

# Slice Plan Prompt


$v1-slice-harness mode=plan

Home Search V1 slice를 dry-run 또는 run 전에 실행 계획으로 정리할 때 이
prompt를 사용한다.

입력:
- `.codex/harness/slices/backlog.toml`의 slice id
- `.codex/harness/reports/*.json` 또는 `--from-report`의 최근 report evidence
- `--targets`의 target filter

Skill routing:
- $planning: decision-complete slice plan과 acceptance criteria를 만든다.
- $vertical-slice-implementation: 구현 시작 전 계획이 얇고 독립 검증 가능한 V1 slice인지 확인한다.
- $tdd: 실행 전 First RED, Expected RED failure, Minimum GREEN을 정의한다.
- $api-contract: backend/frontend behavior 영향이 있을 때 V1 API URL, request, response, unit, error compatibility를 확인한다.

지시:
- slice goal, acceptance criteria, First RED candidates, expected RED failure,
  minimum GREEN, verification, stop conditions, next command를 렌더링한다.
- 최근 gate risks, missing tests, contract gaps, data-safety gaps를 추가
  acceptance criteria와 First RED candidates로 변환한다.
- 파일 수정, branch/worktree 생성, commit, integration, push, PR 생성을 하지 않는다.
- backlog target이 `planning-only`이면 자동 구현을 제안하지 않는다.
- backend/frontend behavior를 건드리는 slice는 V1 API URL과 response compatibility를 명시한다.

사용자 노출 output labels:

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

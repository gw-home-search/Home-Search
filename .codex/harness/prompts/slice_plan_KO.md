# Slice Plan Prompt


`$v1-slice-harness mode=plan`

dry-run 또는 run 전에 특정 Home Search V1 slice 실행 brief를 요청할 때 사용한다.

## 입력

- `.codex/harness/slices/backlog.toml`의 slice id
- `.codex/harness/reports/*.json` 또는 `--from-report`의 optional recent report evidence
- `--targets`의 optional target filter

## Skill routing

- `$planning`: decision-complete slice plan과 acceptance criteria를 만든다.
- `$tdd`: 실행 전 First RED, Expected RED failure, Minimum GREEN을 정의한다.
- `$api-contract`: backend 또는 frontend behavior가 영향을 받을 수 있으면 V1 API URL, request, response, unit, error compatibility를 확인한다.

## 지시사항

- slice goal, acceptance criteria, First RED candidates, expected RED failure, minimum GREEN, verification, stop conditions, next command를 렌더링한다.
- 최근 gate risks, missing tests, contract gaps, data-safety gaps를 추가 acceptance criteria와 First RED candidates로 변환한다.
- 파일 수정, branch 생성, worktree 생성, commit, integration, push, PR open을 하지 않는다.
- backlog target이 `planning-only`이면 automatic implementation을 제안하지 않는다.
- slice가 backend 또는 frontend behavior를 건드리면 V1 API URL과 response compatibility를 명시한다.

## 사용자 출력 label

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

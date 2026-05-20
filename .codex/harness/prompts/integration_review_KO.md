# Integration Review Prompt


`$v1-slice-harness mode=gate`

## 입력

- Slice: `{{SLICE}}`
- Preset: `{{PRESET}}`
- Integration branch: `{{BRANCH_NAME}}`

Review only. 파일을 수정하지 않는다.

## Skill routing

- `$code-review`: merged api/web diff와 completion evidence를 findings-first로 review한다.
- `$api-contract`: backend/frontend 전체에서 V1 API URL, request, response, unit, error compatibility를 확인한다.
- `$tdd`: behavior 변경이 있을 때 First RED validity와 Minimum GREEN evidence를 확인한다.

## 확인 항목

- main API URL과 response shape가 V1 compatible한지 확인한다.
- Map, search, region, detail, trade flow가 정렬되어 있는지 확인한다.
- Backend data invariant가 보존되었는지 확인한다.
- V2 dependency가 critical path에 들어오지 않았는지 확인한다.
- Verification evidence가 backendQualityCheck, web test, web build, diff check를 포괄하는지 확인한다.
- Verification evidence가 정확한 line format을 사용하는지 확인한다: ``- `command` = pass|fail|not run (Korean reason)``.

## 출력 label

짧은 Korean-first integration review를 다음 label로 출력한다.

- 상태: Pass|Partial|Fail
- 검증:
- 리뷰:
- contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
- reviewer: 지적사항 = none|listed|not run
- 주요 위험:
- 다음 행동:

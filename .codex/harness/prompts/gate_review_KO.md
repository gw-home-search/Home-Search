# Gate Review Prompt


`$v1-slice-harness mode=gate`

## 입력

- Slice: `{{SLICE}}`
- Preset: `{{PRESET}}`
- Target: `{{TARGET}}`
- Branch: `{{BRANCH_NAME}}`

Review only. 파일을 수정하지 않는다.

## Skill routing

`{{SKILL_ROUTING}}`

## 확인 항목

- 변경 범위가 target preset의 allowed edit scope 안에 머물렀는지 확인한다.
- V1 API contract와 data invariant가 보존되었는지 확인한다.
- 필수 verification evidence가 존재하는지 확인한다.
- Verification evidence가 정확한 line format을 사용하는지 확인한다: ``- `command` = pass|fail|not run (Korean reason)``.
- backend, frontend, harness, hook, GitHub workflow, Markdown, KO 변경에 맞는 changed-file PR lint evidence가 있는지 확인한다.
- protected paths, secrets, build output, automatic main merge, push가 추가되지 않았는지 확인한다.

## 출력 label

짧은 Korean-first gate review를 다음 label로 출력한다.

- 상태: Pass|Partial|Fail
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:

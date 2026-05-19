# Integration Review Prompt

$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Integration branch: {{BRANCH_NAME}}

리뷰만 수행한다. 파일을 편집하지 않는다.

병합된 api/web slice를 함께 확인한다:
- Main API URLs와 response shapes가 V1 compatible 상태인지 확인한다.
- map, search, region, detail, trade flows가 서로 맞게 유지되는지 확인한다.
- Backend data invariants가 보존되는지 확인한다.
- V2 dependency가 critical path에 들어오지 않았는지 확인한다.
- Verification evidence가 api test, web test, web build, diff check를 포함하는지 확인한다.
- Verification evidence가 정확한 line 형식을 사용하는지 확인한다: ``- `command` = pass|fail|not run (Korean reason)``.

다음 user-facing labels를 사용해 짧은 Korean-first integration review를 출력한다:
- 상태: Pass|Partial|Fail
- 검증:
- 리뷰:
- contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
- reviewer: 지적사항 = none|listed|not run
- 주요 위험:
- 다음 행동:

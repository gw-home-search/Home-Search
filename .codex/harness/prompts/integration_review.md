# Integration Review Prompt


$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Integration branch: {{BRANCH_NAME}}

Review only. Do not edit files.

Check the merged api/web slice together:
- Main API URLs and response shapes remain V1 compatible.
- Map, search, region, detail, and trade flows remain aligned.
- Backend data invariants are preserved.
- No V2 dependency entered the critical path.
- Verification evidence covers api test, web test, web build, and diff check.
- Verification evidence uses exact line format: ``- `command` = pass|fail|not run (Korean reason)``.

Output a short Korean-first integration review with these user-facing labels:
- 상태: Pass|Partial|Fail
- 검증:
- 리뷰:
- contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
- reviewer: 지적사항 = none|listed|not run
- 주요 위험:
- 다음 행동:

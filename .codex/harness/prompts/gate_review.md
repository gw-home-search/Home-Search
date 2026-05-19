# Gate Review Prompt

$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

Review only. Do not edit files.

Check:
- Scope stayed inside the target app.
- V1 API contract and data invariants were preserved.
- Required verification evidence is present.
- No protected paths, secrets, build output, automatic main merge, or push were introduced.

Output a short Korean gate review with these labels:
- 상태: Pass|Partial|Fail
- First RED:
- Expected RED failure:
- Minimum GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:

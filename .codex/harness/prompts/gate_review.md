# Gate Review Prompt


home-search-harness mode=gate

Work item: {{WORK_ID}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

Review only. Do not edit files.

Skill routing:
{{SKILL_ROUTING}}

Executed verification evidence:
{{VERIFICATION_EVIDENCE}}

TDD evidence:
- Read `{{TDD_EVIDENCE_PATH}}` when it exists.
- If it does not exist, report that gap without inventing evidence.

Check:
- Scope stayed inside the target preset's allowed edit scope.
- public API contract and data invariants were preserved.
- Required verification evidence is present.
- Verification evidence uses exact line format: ``- `command` = pass|fail|not run (Korean reason)``.
- Changed-file PR lint evidence is present for backend, frontend, harness, hook,
  GitHub workflow, Markdown, and KO changes.
- No protected paths, secrets, build output, automatic main merge, main push,
  or PR merge were introduced.
- Security surface (secrets, admin access, SQL, external input, output sinks)
  was reviewed via `$security-audit` and carries no new high/critical finding.
- Explicit `--pr` may push only the generated `feat/*-integration` branch.
- This gate runs before integration and PR body generation. Do not fail only
  because a PR or PR body does not exist yet; validate the inputs that will
  generate it.

Output a short Korean-first gate review with these user-facing labels:
- 상태: Pass|Partial|Fail
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 리뷰:
- reviewer: 지적사항 = none|listed
- 계약 영향:
- contract-reviewer: 게이트 결정 = Pass|Partial|Fail
- 보안 영향:
- security-audit: 지적사항 = none|listed
- 주요 위험:
- 다음 행동:

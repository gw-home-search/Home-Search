# Backend Work Execute Prompt


home-search-harness mode=execute

Work item: {{WORK_ID}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

Allowed edit scope:
- {{ALLOWED_SCOPE}}

Forbidden edit scope:
- {{FORBIDDEN_SCOPE}}
- docs/**
- AGENTS.md
- README.md
- scripts/**
- infra/**
- package-lock.json
- build output

Instructions:
- Read root AGENTS.md, apps/api/AGENTS.md, CONTEXT.md, apps/api/CONTEXT.md, and relevant canonical docs before editing.
- Preserve the public API contract and data invariants.
- Do not introduce later-scope ranking, favorite, alarm, mail, recommendation, auth, or heavy analytics work.
- Treat `cd apps/api && ./gradlew backendQualityCheck` as the backend canonical PR/CI gate.
- Use the minimum GREEN work item and leave a short Korean-first gate summary.

Skill routing:
{{SKILL_ROUTING}}

Required verification:
- {{VERIFICATION_COMMANDS}}
- Use exact evidence line format: ``- `command` = pass|fail|not run (Korean reason)``.

Final user-facing evidence labels:
- 상태:
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 주요 위험:
- 다음 행동:

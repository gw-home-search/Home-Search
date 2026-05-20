# Frontend Slice Execute Prompt


$v1-slice-harness mode=execute

Slice: {{SLICE}}
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
- ai-docs/**
- scripts/**
- infra/**
- package-lock.json
- build output

Instructions:
- Read root AGENTS.md, apps/web/AGENTS.md, CONTEXT.md, apps/web/CONTEXT.md, docs/API_CONTRACT.md, docs/MAP_DISPLAY_FLOW.md, and docs/UI_UX_MIGRATION.md before editing.
- Preserve V1 URLs, request fields, response fields, units, coordinate conventions, and empty/error behavior.
- Keep the map usable on marker API failure.
- Use only scripts that exist in `apps/web/package.json`; currently PR/CI evidence requires `npm run test` and `npm run build`, not `npm run lint`.
- Use the minimum GREEN slice and leave a short Korean-first gate summary.

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

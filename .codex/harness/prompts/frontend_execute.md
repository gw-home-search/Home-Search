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
- Use the minimum GREEN slice and leave a short Korean gate summary.

Required verification:
- {{VERIFICATION_COMMANDS}}

Final evidence labels:
- 상태:
- First RED:
- Expected RED failure:
- Minimum GREEN:
- 검증:
- 주요 위험:
- 다음 행동:

# Backend Slice Execute Prompt

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
- Read root AGENTS.md, apps/api/AGENTS.md, CONTEXT.md, apps/api/CONTEXT.md, and relevant canonical docs before editing.
- Preserve the V1 API contract and data invariants.
- Do not introduce V2 ranking, favorite, alarm, mail, recommendation, auth, or heavy analytics work.
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

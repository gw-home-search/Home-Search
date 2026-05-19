# Frontend Slice Execute Prompt

$v1-slice-harness mode=execute

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

허용 편집 범위:
- {{ALLOWED_SCOPE}}

금지 편집 범위:
- {{FORBIDDEN_SCOPE}}
- docs/**
- AGENTS.md
- README.md
- ai-docs/**
- scripts/**
- infra/**
- package-lock.json
- build output

지시:
- 편집 전에 root `AGENTS.md`, `apps/web/AGENTS.md`, `CONTEXT.md`, `apps/web/CONTEXT.md`, `docs/API_CONTRACT.md`, `docs/MAP_DISPLAY_FLOW.md`, `docs/UI_UX_MIGRATION.md`를 읽는다.
- V1 URL, request fields, response fields, units, coordinate conventions, empty/error behavior를 보존한다.
- marker API 실패 시에도 map을 사용할 수 있게 유지한다.
- 최소 GREEN slice를 사용하고 짧은 Korean-first gate summary를 남긴다.

필수 검증:
- {{VERIFICATION_COMMANDS}}
- 정확한 evidence line 형식을 사용한다: ``- `command` = pass|fail|not run (Korean reason)``.

최종 사용자-facing evidence labels:
- 상태:
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 주요 위험:
- 다음 행동:

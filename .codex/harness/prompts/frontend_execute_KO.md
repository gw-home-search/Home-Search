# Frontend Work Execute Prompt KO

> KO 생성 기준: canonical source only
> Source: `.codex/harness/prompts/frontend_execute.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.codex/harness/prompts/frontend_execute.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# Frontend Work Execute Prompt


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
- ai-docs/**
- scripts/**
- infra/**
- package-lock.json
- build output

Instructions:
- Read root AGENTS.md, apps/web/AGENTS.md, CONTEXT.md, apps/web/CONTEXT.md, docs/API_CONTRACT.md, docs/MAP_DISPLAY_FLOW.md, and docs/UI_UX_MIGRATION.md before editing.
- Preserve public API URLs, request fields, response fields, units, coordinate conventions, and empty/error behavior.
- Keep the map usable on marker API failure.
- Use only scripts that exist in `apps/web/package.json`; currently PR/CI evidence requires `npm run test` and `npm run build`, not `npm run lint`.
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

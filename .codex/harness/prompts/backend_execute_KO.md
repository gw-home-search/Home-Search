# Backend Work Execute Prompt KO

> KO 생성 기준: canonical source only
> Source: `.codex/harness/prompts/backend_execute.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.codex/harness/prompts/backend_execute.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

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
- ai-docs/**
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

# Backend Slice Execute Prompt 한국어 동기화본

$v1-slice-harness mode=execute

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

허용 수정 범위:
- {{ALLOWED_SCOPE}}

금지 수정 범위:
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
- 수정 전에 root AGENTS.md, apps/api/AGENTS.md, CONTEXT.md, apps/api/CONTEXT.md, 관련 canonical docs를 읽는다.
- V1 API contract와 data invariant를 보존한다.
- V2 ranking, favorite, alarm, mail, recommendation, auth, heavy analytics 작업을 넣지 않는다.
- minimum GREEN slice만 구현하고 짧은 한국어 gate summary를 남긴다.

필수 검증:
- {{VERIFICATION_COMMANDS}}

최종 evidence labels:
- 상태:
- First RED:
- Expected RED failure:
- Minimum GREEN:
- 검증:
- 주요 위험:
- 다음 행동:

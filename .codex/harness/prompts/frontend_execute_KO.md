# Frontend Slice 실행 Prompt


`$v1-slice-harness mode=execute`

## 입력

- Slice: `{{SLICE}}`
- Preset: `{{PRESET}}`
- Target: `{{TARGET}}`
- Branch: `{{BRANCH_NAME}}`

## 허용 수정 범위

- `{{ALLOWED_SCOPE}}`

## 금지 수정 범위

- `{{FORBIDDEN_SCOPE}}`
- `docs/**`
- `AGENTS.md`
- `README.md`
- `ai-docs/**`
- `scripts/**`
- `infra/**`
- `package-lock.json`
- build output

## 지시사항

- 편집 전 root `AGENTS.md`, `apps/web/AGENTS.md`, `CONTEXT.md`, `apps/web/CONTEXT.md`, `docs/API_CONTRACT.md`, `docs/MAP_DISPLAY_FLOW.md`, `docs/UI_UX_MIGRATION.md`를 읽는다.
- V1 URL, request fields, response fields, units, coordinate conventions, empty/error behavior를 보존한다.
- marker API failure 상황에서도 map이 usable해야 한다.
- `apps/web/package.json`에 존재하는 script만 사용한다. 현재 PR/CI evidence는 `npm run test`, `npm run build`가 필요하고 `npm run lint`는 필요하지 않다.
- 최소 GREEN slice를 적용하고 짧은 Korean-first gate summary를 남긴다.

## Skill routing

`{{SKILL_ROUTING}}`

## 필수 검증

- `{{VERIFICATION_COMMANDS}}`
- 정확한 evidence line 형식: ``- `command` = pass|fail|not run (Korean reason)``.

## 최종 사용자 evidence label

- 상태:
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 주요 위험:
- 다음 행동:

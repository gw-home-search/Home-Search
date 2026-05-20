# Backend Slice 실행 Prompt


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

- 편집 전 root `AGENTS.md`, `apps/api/AGENTS.md`, `CONTEXT.md`, `apps/api/CONTEXT.md`, 관련 canonical docs를 읽는다.
- V1 API contract와 data invariant를 보존한다.
- V2 ranking, favorite, alarm, mail, recommendation, auth, heavy analytics 작업을 추가하지 않는다.
- backend canonical PR/CI gate는 `cd apps/api && ./gradlew backendQualityCheck`로 취급한다.
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

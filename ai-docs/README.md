# Home Search AI 운영 노트

`ai-docs/`는 내가 읽기 위한 한국어 AI 운영 노트다. 프로젝트 공식 문서가 아니고, Codex 실행 설정도 아니다.

공식 기준은 계속 아래 문서가 담당한다.

- project 제품/아키텍처/API/데이터 기준: `docs/`
- AI 실행 규칙: `AGENTS.md`
- 로컬 skill 지시: `.agents/skills/`
- Codex agent/profile 설정: `.codex/`

## 정책

- `ai-docs`는 KO-only 개인 노트로 유지한다.
- 이 노트가 `docs/` 또는 `AGENTS.md`와 충돌하면 공식 문서가 우선한다.

## 읽는 순서

1. `README.md`: 이 디렉터리의 목적과 경계.
2. `playbook.md`: 공통 AI 작업 흐름.
3. `backend.md`: 백엔드 project 작업 노트.
4. `frontend.md`: 프론트엔드 project 작업 노트.
5. `prompts.md`: 복사용 작업 프롬프트 모음.

## 운영 원칙

- 이 노트는 AI가 매번 자동으로 따라야 하는 source of truth가 아니다.
- 실제 작업을 시작할 때는 `AGENTS.md`와 관련 `docs/*.md`를 먼저 확인한다.
- public API URL, 응답 shape, 데이터 invariant를 바꾸는 작업은 별도 확인 없이 진행하지 않는다.

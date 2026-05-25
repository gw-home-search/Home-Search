# Gate Review Prompt KO

> KO 생성 기준: canonical source only
> Source: `.codex/harness/prompts/gate_review.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.codex/harness/prompts/gate_review.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# Gate Review Prompt


home-search-harness mode=gate

Work item: {{WORK_ID}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

Review only. Do not edit files.

Skill routing:
{{SKILL_ROUTING}}

Check:
- Scope stayed inside the target preset's allowed edit scope.
- public API contract and data invariants were preserved.
- Required verification evidence is present.
- Verification evidence uses exact line format: ``- `command` = pass|fail|not run (Korean reason)``.
- Changed-file PR lint evidence is present for backend, frontend, harness, hook,
  GitHub workflow, Markdown, and KO changes.
- No protected paths, secrets, build output, automatic main merge, main push,
  or PR merge were introduced.
- Explicit `--pr` may push only the generated `feat/*-integration` branch.

Output a short Korean-first gate review with these user-facing labels:
- 상태: Pass|Partial|Fail
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:

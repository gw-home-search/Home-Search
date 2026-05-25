# Integration Review Prompt KO

> KO 생성 기준: canonical source only
> Source: `.codex/harness/prompts/integration_review.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.codex/harness/prompts/integration_review.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# Integration Review Prompt


home-search-harness mode=gate

Work item: {{WORK_ID}}
Preset: {{PRESET}}
Integration branch: {{BRANCH_NAME}}

Review only. Do not edit files.

Skill routing:
- $code-review: review the merged api/web diff and completion evidence findings-first.
- $api-contract: check public API URL, request, response, unit, and error compatibility across backend/frontend.
- $tdd: verify First RED validity and Minimum GREEN evidence when behavior changed.

Check the merged api/web work item together:
- Main API URLs and response shapes remain public API compatible.
- Map, search, region, detail, and trade flows remain aligned.
- Backend data invariants are preserved.
- No later-scope dependency entered the critical path.
- Verification evidence covers backendQualityCheck, web test, web build, and diff check.
- Verification evidence uses exact line format: ``- `command` = pass|fail|not run (Korean reason)``.

Output a short Korean-first integration review with these user-facing labels:
- 상태: Pass|Partial|Fail
- 검증:
- 리뷰:
- contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
- reviewer: 지적사항 = none|listed|not run
- 주요 위험:
- 다음 행동:

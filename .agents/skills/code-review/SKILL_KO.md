---
name: code-review
description: Home Search diff, gate review, PR, completion evidence를 findings-first 방식으로 검토하여 correctness, V1 API compatibility, data safety, frontend map usability, security, missing tests, KO sync를 확인합니다. "code review", "gate review", "PR review", "reviewer findings", "final self-review", "리뷰", "짧은 리뷰", "게이트 리뷰", "PR 리뷰", "지적사항", "검증 공백"에 사용합니다. root-cause debugging이나 RED planning에는 사용하지 말고, failure는 systematic-debugging으로, RED 질문은 tdd/tdd-guide로 라우팅합니다.
---


# Code Review Skill

구현 후 review 요청이나 final self-review에 이 skill을 사용합니다.

## Routes From Gate/PR

- local final self-review, gate review, PR review, reviewer findings triage,
  completion evidence review, `검증 공백` 확인에 이 skill을 사용합니다.
- 이 skill은 diff와 evidence를 검토합니다. read-only subagent가 사용 가능하고
  명시적으로 요청되었거나 허용된 경우 `reviewer`를 대체하지 않습니다.
- failing command, hook block, CI failure, runtime bug, API reproduction work는
  `systematic-debugging`으로 라우팅합니다.
- First RED validity, expected RED failure, public seam, minimum GREEN 질문은
  `tdd` 또는 `tdd-guide`로 라우팅합니다.

## Format

지적사항을 먼저 제시합니다. 각 지적사항에는 severity, file/line, problem,
impact, required fix를 포함합니다.

user-facing review output에는 Korean-first label을 사용합니다.

- 지적사항.
- 검증 근거 확인.
- 검증 공백.
- 잔여 위험.
- Markdown이 변경되었으면 KO sync 상태.

Severity:

- 치명(Critical).
- 높음(High).
- 중간(Medium).
- 낮음(Low).

## Review Axes

- Correctness.
- V1 API compatibility.
- Data safety.
- Frontend map usability.
- Security/secrets.
- Missing tests.
- KO sync.

## Rules

- style-only comment는 문서화된 규칙을 위반하거나 실제 risk가 있을 때만
  보고합니다.
- 지적사항이 없으면 한국어로 명확히 말하고 remaining test gap 또는 residual
  risk를 언급합니다.
- Public API, DB, ingest invariant risk를 style보다 우선합니다.

---
name: code-review
description: correctness, V1 API compatibility, data safety, KO sync risk를 우선해 Home Search 변경을 findings-first로 리뷰한다.
---

# Code Review Skill

review 요청 또는 implementation 후 final self-review에 이 skill을 사용한다.

## Format

Findings를 먼저 둔다. 각 finding은 severity, file/line, problem, impact, required fix를 포함한다.

User-facing review output에는 Korean-first labels를 사용한다:

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

- documented rule을 위반하거나 실제 risk가 있을 때만 style-only comments를 보고한다.
- 지적사항이 없으면 한국어로 명확히 말하고 남은 test gaps 또는 residual risk를 언급한다.
- Public API, DB, ingest invariant risks가 style보다 우선한다.

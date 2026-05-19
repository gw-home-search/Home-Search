---
name: code-review
description: Review Home Search changes findings-first, prioritizing correctness, V1 API compatibility, data safety, and KO sync risk.
---


# Code Review Skill

Use this skill for review requests or final self-review after implementation.

## Format

Findings first. Each finding includes severity, file/line, problem, impact, and required fix.

Use Korean-first labels for user-facing review output:

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

- Report style-only comments only when they violate a documented rule or carry real risk.
- If there are no findings, say so clearly in Korean and mention remaining test gaps or residual risk.
- Public API, DB, and ingest invariant risks take priority over style.

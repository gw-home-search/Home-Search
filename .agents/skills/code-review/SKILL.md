---
name: code-review
description: Review Home Search changes findings-first, prioritizing correctness, V1 API compatibility, data safety, and KO sync risk.
---

# Code Review Skill

Use this skill for review requests or final self-review after implementation.

## Format

Findings first. Each finding includes severity, file/line, problem, impact, and required fix.

For final self-review, also report:

- Verification evidence reviewed.
- Missing tests or verification gaps.
- Residual risk.
- KO sync status when Markdown changed.

Severity:

- Critical.
- High.
- Medium.
- Low.

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
- If there are no findings, say so clearly and mention remaining test gaps or residual risk.
- Public API, DB, and ingest invariant risks take priority over style.

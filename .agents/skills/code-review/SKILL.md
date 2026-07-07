---
name: code-review
description: Review Home Search diffs, gate reviews, PRs, and completion evidence findings-first for correctness, public API compatibility, data safety, frontend map usability, security, missing tests. Use for "code review", "gate review", "PR review", "reviewer findings", "final self-review", "리뷰", "짧은 리뷰", "게이트 리뷰", "PR 리뷰", "지적사항", "검증 공백". Do not use for root-cause debugging or RED planning; route failures to systematic-debugging and RED questions to tdd/tdd-guide.
---


# Code Review Skill

Use this skill for review requests or final self-review after implementation.

## Routes From Gate/PR

- Use this skill for local final self-review, gate review, PR review, reviewer
  findings triage, completion evidence review, and `검증 공백` checks.
- This skill reviews the diff and evidence. It does not replace `reviewer` when
  a read-only subagent is available and explicitly requested or allowed.
- Route failing commands, hook blocks, CI failures, runtime bugs, and API
  reproduction work to `systematic-debugging`.
- Route First RED validity, expected RED failure, public seam, or minimum GREEN
  questions to `tdd` or `tdd-guide`.

## Format

Findings first. Each finding includes severity, file/line, problem, impact, and required fix.

Use Korean-first labels for user-facing review output:

- 지적사항.
- 검증 근거 확인.
- 검증 공백.
- 잔여 위험.

Severity:

- 치명(Critical).
- 높음(High).
- 중간(Medium).
- 낮음(Low).

## Review Axes

- Correctness: logic, boundary conditions, and error paths in the changed
  code. Verify claims against the diff and executed commands, not the
  description of the change.
- Public API compatibility: URL, method, request/response field names, types,
  units, and error shape against the current `docs/API_CONTRACT.md` text.
- Data safety: raw-first ordering, duplicate-safe ingest, failed-match
  queryability, the `complex_id` relation, and migration reversibility.
- Frontend map usability: the map stays usable on API failure, adapters keep
  canonical contract fields, and loading/empty/error states are covered.
- Security/secrets: no keys, tokens, or access codes in source, fixtures, or
  logs. Route anything deeper than a surface check to `security-audit`.
- Missing tests: changed behavior without a test at the right seam. Name the
  missing test concretely. Repository/Flyway/PostGIS seams run under
  `persistenceTest`, not plain `test` — a GREEN `test` run does not cover them.

## Evidence Rule

Do not report a passing review while required verification commands are
unrun. List every unrun command under `검증 공백` with the reason, and treat
"tests exist" claims as unverified until the run output is shown.

## Rules

- Report style-only comments only when they violate a documented rule or carry real risk.
- If there are no findings, say so clearly in Korean and mention remaining test gaps or residual risk.
- Public API, DB, and ingest invariant risks take priority over style.

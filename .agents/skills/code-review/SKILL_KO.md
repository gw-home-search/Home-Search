# Code Review Skill KO

> KO 생성 기준: canonical source only
> Source: `.agents/skills/code-review/SKILL.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.agents/skills/code-review/SKILL.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

---
name: code-review
description: Review Home Search diffs, gate reviews, PRs, and completion evidence findings-first for correctness, public API compatibility, data safety, frontend map usability, security, missing tests, and KO sync. Use for "code review", "gate review", "PR review", "reviewer findings", "final self-review", "리뷰", "짧은 리뷰", "게이트 리뷰", "PR 리뷰", "지적사항", "검증 공백". Do not use for root-cause debugging or RED planning; route failures to systematic-debugging and RED questions to tdd/tdd-guide.
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
- Markdown이 변경되었으면 KO sync 상태.

Severity:

- 치명(Critical).
- 높음(High).
- 중간(Medium).
- 낮음(Low).

## Review Axes

- Correctness.
- public API compatibility.
- Data safety.
- Frontend map usability.
- Security/secrets.
- Missing tests.
- KO sync.

## Rules

- Report style-only comments only when they violate a documented rule or carry real risk.
- If there are no findings, say so clearly in Korean and mention remaining test gaps or residual risk.
- Public API, DB, and ingest invariant risks take priority over style.

---
name: tdd
description: Plan and drive Home Search behavior changes with First RED, Expected RED failure, Minimum GREEN, regression tests, and public seams. Use for "TDD", "RED/GREEN", "First RED", "regression test", "backend controller", "frontend adapter", "contract test", "최초 RED", "예상 RED 실패", "최소 GREEN", "회귀 테스트". Do not use for pure planning-only docs or root-cause debugging; route uncertain RED validity to tdd-guide and failures to systematic-debugging.
---


# TDD Skill

Use this skill for backend or frontend behavior changes. The goal is to anchor implementation in a verifiable failing test before changing production code when practical.

## When To Use

- Backend controller, DTO, service, repository, Flyway, ingest, or API adapter
  behavior changes.
- Frontend adapter, marker transform, component behavior, map fallback, or
  detail/trade drawer behavior changes.
- Regression tests for reproduced bugs.
- Contract tests that protect V1 URL, request, response, unit, or error
  behavior.

## Do Not Use

- Pure docs planning or next-slice comparison without behavior change.
- Root-cause debugging of an already failing command; use
  `systematic-debugging`.
- Final diff, gate, or PR review; use `code-review` or `reviewer`.

## RED Validity

- The failure is directly connected to new behavior or a reproduced bug.
- The failure is deterministic.
- The test verifies a public seam, not private implementation.
- Mocks do not hide V1 API contracts or data invariants.

## Backend Seams

- Controller/DTO/API adapter: URL, method, field, type, and unit.
- Application service: raw save, matching, normalized insert, status update.
- Repository/Flyway: uniqueness, partitioning, latest lookup, failed match query.
- External API adapter: parsing, source key normalization, failure mapping.

## Frontend Seams

- API adapter normalization.
- Marker transform.
- Component behavior.
- Map failure fallback.
- Loading, empty, and error state.
- Fixture/mock contract.
- Detail/trade drawer state.

## Loop

1. State the requirement in one sentence.
2. Choose the public seam and test location.
3. State the expected RED failure before production changes.
4. Confirm why the RED is valid.
5. Write the failing test and confirm the RED reason.
6. Write the minimum production code for GREEN.
7. Keep tests passing during any refactor.
8. Leave verification evidence.

## Required Output

- First RED test (`최초 RED`).
- Public seam.
- Test file candidate.
- Expected RED failure (`예상 RED 실패`).
- Why this is a valid RED.
- Minimum GREEN slice (`최소 GREEN`).
- Verification commands.
- RED waiver reason, only when no valid RED can be created.

## Routes

- Use `tdd-guide` when RED validity, public seam choice, expected RED failure,
  or minimum GREEN is uncertain.
- Use `contract-reviewer` before controller, DTO, frontend adapter, fixture,
  field, type, unit, coordinate, error, or empty-result behavior changes can
  affect the V1 API contract.
- Use `systematic-debugging` when the starting point is a lint, test, build,
  hook, CI, runtime, or API failure requiring root-cause diagnosis.
- Use `code-review` or `reviewer` after the behavior slice is complete.

## No Test Environment

If the test environment does not exist yet, do not claim behavior completion.
Report the needed public seam, temporary verification, and follow-up test.
Use Korean-first prose when reporting TDD evidence to the user, but keep test
names, paths, commands, and status tokens unchanged.

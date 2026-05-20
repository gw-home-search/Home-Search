---
name: systematic-debugging
description: Diagnose Home Search lint/test/build/hook/CI/runtime/API failures with reproducible feedback loops, hypotheses, root-cause fixes, and regression evidence. Use for "lint failure", "test failure", "build failure", "hook block", "npm failure", "Gradle failure", "CI failure", "API mismatch", "검증 실패", "테스트 실패", "빌드 실패", "hook 차단", "복구". Do not use for initial feature planning or final review; route plans to planning and completed diffs to code-review/reviewer.
---


# Systematic Debugging Skill

Use this skill for failing checks, runtime bugs, API mismatches, ingest issues, and map marker failures.

## Failure Routing

- Use this skill when the current work is blocked by lint, test, build, hook,
  CI, runtime, API, ingest, or map marker failure.
- For post-tool or Stop hook blocks, identify the missing evidence first, then
  decide whether recovery needs only rerun evidence or a production fix.
- If the root cause is a behavior bug, route the regression test and minimum
  fix through `tdd` or `tdd-guide`.
- If the symptom is URL, request, response, unit, coordinate, or error shape
  mismatch, use `contract-reviewer` before changing contract-facing behavior.
- After the fix and exact verification line, use `code-review` or `reviewer`
  before completion.

## Do Not Use

- Initial feature planning or next-slice comparison; use `planning`.
- Final diff, gate, or PR review without an active failure; use `code-review`
  or `reviewer`.

## Loop

1. Symptom capture: command, log, input, URL, and environment.
2. Deterministic feedback loop: shortest way to reproduce the failure.
3. Reproduction: confirm the current failure.
4. Hypothesis: one possible cause with an observable prediction.
5. Instrumentation: only the logs, assertions, or queries needed.
6. Root cause fix: smallest change for the confirmed cause.
7. Regression test: prove the same failure does not recur.

## Common Cases

- Gradle failure: pin task, stack trace, profile, and failing test class.
- npm failure: confirm the existing package script before adding a new command
  to presets, hooks, CI, or PR evidence.
- Hook block: separate missing evidence from a real failing verification
  command.
- CI failure: reproduce the failing CI command locally when possible and keep
  CI-only environment differences explicit.
- API mismatch: compare request/response with `docs/API_CONTRACT.md` and use
  `contract-reviewer` before changing contract-facing code.
- Map marker failure: isolate bounds, level, endpoint, adapter, and render state.
- Ingest dedupe bug: trace raw ingest row, source key, unique key, and normalized insert.
- Regression bugfix: use `tdd-guide` to confirm the first RED, expected RED
  failure, public seam, and minimum GREEN slice before production changes.

## Stop Rule

After three failed fixes, stop implementation and return to planning. Preserve the current evidence and excluded hypotheses.
Use Korean-first prose when reporting the debugging result to the user, while
keeping commands, paths, error identifiers, and status tokens unchanged.

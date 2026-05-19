---
name: systematic-debugging
description: Handle Home Search failures through reproducible feedback loops, hypothesis testing, root-cause fixes, and regression evidence.
---

# Systematic Debugging Skill

Use this skill for failing checks, runtime bugs, API mismatches, ingest issues, and map marker failures.

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

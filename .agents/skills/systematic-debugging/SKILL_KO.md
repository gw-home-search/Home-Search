---
name: systematic-debugging
description: reproducible feedback loops, hypothesis testing, root-cause fixes, regression evidence로 Home Search failures를 처리한다.
---

# Systematic Debugging Skill

failing checks, runtime bugs, API mismatches, ingest issues, map marker failures에 이 skill을 사용한다.

## Loop

1. Symptom capture: command, log, input, URL, environment.
2. Deterministic feedback loop: failure를 재현하는 가장 짧은 방법.
3. Reproduction: 현재 failure를 확인한다.
4. Hypothesis: observable prediction이 있는 하나의 가능한 원인.
5. Instrumentation: 필요한 logs, assertions, queries만 추가한다.
6. Root cause fix: 확인된 원인에 대한 가장 작은 변경.
7. Regression test: 같은 failure가 재발하지 않음을 증명한다.

## Common Cases

- Gradle failure: task, stack trace, profile, failing test class를 고정한다.
- API mismatch: request/response를 `docs/API_CONTRACT.md`와 비교하고 contract-facing code 변경 전에 `contract-reviewer`를 사용한다.
- Map marker failure: bounds, level, endpoint, adapter, render state를 분리한다.
- Ingest dedupe bug: raw ingest row, source key, unique key, normalized insert를 추적한다.
- Regression bugfix: production changes 전에 `tdd-guide`로 first RED, expected RED failure, public seam, minimum GREEN slice를 확인한다.

## Stop Rule

세 번의 fix가 실패하면 implementation을 중단하고 planning으로 돌아간다. 현재 evidence와 excluded hypotheses를 보존한다.
Debugging result를 사용자에게 보고할 때는 Korean-first prose를 사용하되 commands, paths, error identifiers, status tokens는 그대로 유지한다.

---
name: systematic-debugging
description: Home Search failing check, API mismatch, ingest bug, map marker failure를 재현 가능한 feedback loop와 regression evidence로 처리한다.
---

# Systematic Debugging Skill

Failing check, runtime bug, API mismatch, ingest issue, map marker failure에 이 skill을 사용한다.

## Loop

1. Symptom capture: command, log, input, URL, environment.
2. Deterministic feedback loop: failure를 재현하는 가장 짧은 방법.
3. Reproduction: 현재 failure를 확인한다.
4. Hypothesis: 관찰 가능한 prediction이 있는 원인 하나.
5. Instrumentation: 필요한 log, assertion, query만 추가한다.
6. Root cause fix: 확인된 원인을 위한 가장 작은 변경.
7. Regression test: 같은 failure가 재발하지 않음을 증명한다.

## Common Cases

- Gradle failure: task, stack trace, profile, failing test class를 고정한다.
- API mismatch: request/response를 `docs/API_CONTRACT.md`와 비교하고 contract-facing code를 바꾸기 전에 `contract-reviewer`를 사용한다.
- Map marker failure: bounds, level, endpoint, adapter, render state를 분리한다.
- Ingest dedupe bug: raw ingest row, source key, unique key, normalized insert를 추적한다.
- Regression bugfix: production 변경 전에 `tdd-guide`로 first RED, expected RED failure, public seam, minimum GREEN slice를 확인한다.

## Stop Rule

세 번의 fix가 실패하면 implementation을 멈추고 planning으로 돌아간다. 현재 evidence와 제외한 hypothesis를 보존한다.

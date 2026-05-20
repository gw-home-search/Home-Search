---
name: systematic-debugging
description: Home Search lint/test/build/hook/CI/runtime/API failure를 reproducible feedback loop, hypothesis, root-cause fix, regression evidence로 진단합니다. "lint failure", "test failure", "build failure", "hook block", "npm failure", "Gradle failure", "CI failure", "API mismatch", "검증 실패", "테스트 실패", "빌드 실패", "hook 차단", "복구"에 사용합니다. initial feature planning이나 final review에는 사용하지 말고, plan은 planning으로, completed diff는 code-review/reviewer로 라우팅합니다.
---


# Systematic Debugging Skill

failing check, runtime bug, API mismatch, ingest issue, map marker failure에 이
skill을 사용합니다.

## Failure Routing

- current work가 lint, test, build, hook, CI, runtime, API, ingest, map marker
  failure로 막혔을 때 이 skill을 사용합니다.
- post-tool 또는 Stop hook block에서는 먼저 missing evidence를 식별하고,
  recovery가 rerun evidence만 필요한지 production fix가 필요한지 결정합니다.
- root cause가 behavior bug이면 regression test와 minimum fix를 `tdd` 또는
  `tdd-guide`로 라우팅합니다.
- symptom이 URL, request, response, unit, coordinate, error shape mismatch이면
  contract-facing behavior를 바꾸기 전에 `contract-reviewer`를 사용합니다.
- fix와 exact verification line 이후 completion 전에 `code-review` 또는
  `reviewer`를 사용합니다.

## Do Not Use

- initial feature planning 또는 next-slice comparison에는 `planning`을 사용합니다.
- active failure가 없는 final diff, gate, PR review에는 `code-review` 또는
  `reviewer`를 사용합니다.

## Loop

1. Symptom capture: command, log, input, URL, environment.
2. Deterministic feedback loop: failure를 재현하는 가장 짧은 방법.
3. Reproduction: 현재 failure 확인.
4. Hypothesis: observable prediction이 있는 가능한 원인 하나.
5. Instrumentation: 필요한 log, assertion, query만 추가.
6. Root cause fix: 확인된 원인에 대한 가장 작은 변경.
7. Regression test: 같은 failure가 재발하지 않음을 증명.

## Common Cases

- Gradle failure: task, stack trace, profile, failing test class를 고정합니다.
- npm failure: preset, hook, CI, PR evidence에 새 command를 추가하기 전에
  existing package script를 확인합니다.
- Hook block: missing evidence와 실제 failing verification command를 분리합니다.
- CI failure: 가능하면 failing CI command를 local에서 재현하고 CI-only
  environment difference를 명시합니다.
- API mismatch: request/response를 `docs/API_CONTRACT.md`와 비교하고
  contract-facing code 변경 전 `contract-reviewer`를 사용합니다.
- Map marker failure: bounds, level, endpoint, adapter, render state를 분리합니다.
- Ingest dedupe bug: raw ingest row, source key, unique key, normalized insert를
  추적합니다.
- Regression bugfix: production change 전에 `tdd-guide`로 first RED, expected
  RED failure, public seam, minimum GREEN slice를 확인합니다.

## Stop Rule

세 번의 fix 시도 후에도 실패하면 implementation을 멈추고 planning으로 돌아갑니다.
현재 evidence와 excluded hypothesis를 보존합니다. debugging result를 user에게
보고할 때는 Korean-first prose를 사용하되 commands, paths, error identifiers,
status tokens는 그대로 유지합니다.

---
name: tdd
description: Home Search behavior change를 First RED, Expected RED failure, Minimum GREEN, regression tests, public seams로 계획하고 진행합니다. "TDD", "RED/GREEN", "First RED", "regression test", "backend controller", "frontend adapter", "contract test", "최초 RED", "예상 RED 실패", "최소 GREEN", "회귀 테스트"에 사용합니다. pure planning-only docs나 root-cause debugging에는 사용하지 말고, RED validity가 불확실하면 tdd-guide로, failure는 systematic-debugging으로 라우팅합니다.
---


# TDD Skill

backend 또는 frontend behavior change에 이 skill을 사용합니다. 목표는 실용적으로
가능할 때 production code 변경 전 검증 가능한 failing test로 구현을 고정하는
것입니다.

## When To Use

- Backend controller, DTO, service, repository, Flyway, ingest, API adapter
  behavior change.
- Frontend adapter, marker transform, component behavior, map fallback,
  detail/trade drawer behavior change.
- 재현된 bug에 대한 regression test.
- V1 URL, request, response, unit, error behavior를 보호하는 contract test.

## Do Not Use

- behavior change가 없는 pure docs planning 또는 next-slice comparison.
- 이미 failing command가 있는 root-cause debugging에는
  `systematic-debugging`을 사용합니다.
- final diff, gate, PR review에는 `code-review` 또는 `reviewer`를 사용합니다.

## RED Validity

- failure가 new behavior 또는 reproduced bug와 직접 연결되어 있습니다.
- failure가 deterministic합니다.
- test가 private implementation이 아니라 public seam을 검증합니다.
- mock이 V1 API contract나 data invariant를 숨기지 않습니다.

## Backend Seams

- Controller/DTO/API adapter: URL, method, field, type, unit.
- Application service: raw save, matching, normalized insert, status update.
- Repository/Flyway: uniqueness, partitioning, latest lookup, failed match query.
- External API adapter: parsing, source key normalization, failure mapping.

## Frontend Seams

- API adapter normalization.
- Marker transform.
- Component behavior.
- Map failure fallback.
- Loading, empty, error state.
- Fixture/mock contract.
- Detail/trade drawer state.

## Loop

1. requirement를 한 문장으로 적습니다.
2. public seam과 test location을 선택합니다.
3. production change 전에 expected RED failure를 적습니다.
4. RED가 valid한 이유를 확인합니다.
5. failing test를 작성하고 RED reason을 확인합니다.
6. GREEN을 위한 minimum production code를 작성합니다.
7. refactor 중에도 tests를 passing 상태로 유지합니다.
8. verification evidence를 남깁니다.

## Required Output

- First RED test (`최초 RED`).
- Public seam.
- Test file candidate.
- Expected RED failure (`예상 RED 실패`).
- Why this is a valid RED.
- Minimum GREEN slice (`최소 GREEN`).
- Verification commands.
- valid RED를 만들 수 없을 때만 RED waiver reason.

## Routes

- RED validity, public seam choice, expected RED failure, minimum GREEN이
  불확실하면 `tdd-guide`를 사용합니다.
- controller, DTO, frontend adapter, fixture, field, type, unit, coordinate,
  error, empty-result behavior change가 V1 API contract에 영향을 줄 수 있으면
  `contract-reviewer`를 먼저 사용합니다.
- 시작점이 root-cause diagnosis가 필요한 lint, test, build, hook, CI, runtime,
  API failure이면 `systematic-debugging`을 사용합니다.
- behavior slice 완료 후 `code-review` 또는 `reviewer`를 사용합니다.

## No Test Environment

test environment가 아직 없으면 behavior completion을 주장하지 않습니다. 필요한
public seam, temporary verification, follow-up test를 보고합니다. TDD evidence를
user에게 보고할 때는 Korean-first prose를 사용하되 test names, paths, commands,
status tokens는 그대로 유지합니다.

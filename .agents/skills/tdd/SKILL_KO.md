---
name: tdd
description: valid RED로 Home Search production behavior changes를 시작하고 public seams를 통해 진행한다.
---

# TDD Skill

backend 또는 frontend behavior changes에 이 skill을 사용한다. 목표는 practical할 때 production code 변경 전에 verifiable failing test로 implementation을 고정하는 것이다.

## RED Validity

- failure가 new behavior 또는 reproduced bug와 직접 연결된다.
- failure가 deterministic이다.
- test가 private implementation이 아니라 public seam을 검증한다.
- mocks가 V1 API contracts 또는 data invariants를 숨기지 않는다.

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
- Loading, empty, and error state.
- Fixture/mock contract.
- Detail/trade drawer state.

## Loop

1. requirement를 한 문장으로 적는다.
2. public seam과 test location을 선택한다.
3. production changes 전 expected RED failure를 적는다.
4. RED가 valid한 이유를 확인한다.
5. failing test를 작성하고 RED reason을 확인한다.
6. GREEN을 위한 최소 production code를 작성한다.
7. refactor 중에도 tests를 passing 상태로 유지한다.
8. verification evidence를 남긴다.

## Required Output

- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- RED waiver reason, only when no valid RED can be created.

## No Test Environment

test environment가 아직 없으면 behavior completion을 주장하지 않는다.
필요한 public seam, temporary verification, follow-up test를 보고한다.
사용자에게 TDD evidence를 보고할 때는 Korean-first prose를 사용하되 test names, paths, commands, status tokens는 그대로 유지한다.

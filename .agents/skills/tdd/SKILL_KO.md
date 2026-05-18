---
name: tdd
description: Home Search production behavior 변경을 valid RED에서 시작하고 public seam을 통해 진행한다.
---

# TDD Skill

Backend 또는 frontend behavior 변경에 이 skill을 사용한다. 목표는 실용적으로 가능할 때 production code를 바꾸기 전에 검증 가능한 failing test로 구현을 고정하는 것이다.

## RED Validity

- 실패가 새 behavior 또는 재현된 bug와 직접 연결된다.
- 실패가 deterministic하다.
- test가 private implementation이 아니라 public seam을 검증한다.
- mock이 V1 API contract나 data invariant를 가리지 않는다.

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

1. 요구사항을 한 문장으로 쓴다.
2. public seam과 test location을 선택한다.
3. production 변경 전 예상 RED failure를 명시한다.
4. RED가 valid한 이유를 확인한다.
5. failing test를 작성하고 RED reason을 확인한다.
6. GREEN을 위한 최소 production code를 작성한다.
7. refactor 중에도 test를 passing 상태로 유지한다.
8. verification evidence를 남긴다.

## Required Output

- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- RED waiver reason, 단 valid RED를 만들 수 없을 때만.

## No Test Environment

test environment가 아직 없으면 behavior completion을 주장하지 않는다. 필요한 public seam, temporary verification, follow-up test를 보고한다.

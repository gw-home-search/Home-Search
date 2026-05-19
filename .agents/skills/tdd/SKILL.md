---
name: tdd
description: Start Home Search production behavior changes with a valid RED and drive them through public seams.
---


# TDD Skill

Use this skill for backend or frontend behavior changes. The goal is to anchor implementation in a verifiable failing test before changing production code when practical.

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

- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- RED waiver reason, only when no valid RED can be created.

## No Test Environment

If the test environment does not exist yet, do not claim behavior completion.
Report the needed public seam, temporary verification, and follow-up test.
Use Korean-first prose when reporting TDD evidence to the user, but keep test
names, paths, commands, and status tokens unchanged.

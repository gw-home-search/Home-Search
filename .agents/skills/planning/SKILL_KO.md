---
name: planning
description: Home Search /goal 또는 모호한 요청을 V1 가드레일이 포함된 결정 완료 계획으로 바꾼다.
---

# Planning Skill

요청이 goal 수준이거나 범위가 모호할 때 이 skill을 사용한다. 목표는 V1 API 또는 데이터 invariant가 바뀌려는 지점에서 멈추면서, 바로 구현할 수 있는 계획을 만드는 것이다.

## 입력

- 사용자 요청 또는 `/goal` brief.
- `AGENTS.md`.
- 관련 canonical `docs/*.md`.
- 필요할 때 target 코드 또는 source backend/frontend read-only reference.

## 필수 계획 필드

- Goal.
- Scope.
- Non-scope.
- Touched subsystem: backend, frontend, data, infra, docs.
- App `AGENTS.md`와 `CONTEXT.md` 확인 여부.
- Code-mapper preflight 필요 여부.
- Contract-reviewer checkpoint.
- Public contract impact.
- Data invariant impact.
- TDD gate decision: required, not applicable, blocked/no test environment.
- TDD slice plan.
- Agent handoffs.
- Web/API collision risk.
- Verification commands.
- Stop conditions.

## Backend Checklist

- V1 API URL과 response shape가 안정적으로 유지된다.
- Raw ingest -> normalized trade 순서가 보존된다.
- Duplicate-safe ingest와 failed match queryability가 보존된다.
- `complex_id` operational relation이 명확하다.
- V2 기능이 critical path에 들어오지 않는다.

## Frontend Checklist

- Map, search, region, detail, trade API compatibility가 보존된다.
- Marker adapter field가 canonical contract와 일치한다.
- Map failure handling이 map을 계속 usable하게 유지한다.
- Verification은 실제 존재하는 package script만 포함한다.

## Output Rule

계획은 짧고 실행 가능하게 유지한다. Backend 또는 frontend behavior 변경은 generic test plan보다 TDD slice plan을 선호한다. Backend/frontend behavior slice에서는 RED validity, public seam, expected RED failure, minimum GREEN이 계획에 포함될 때 `tdd-guide` handoff를 명시한다. 그렇지 않으면 RED waiver reason을 적는다. 사용자가 implementation을 요청했고 stop condition이 없으면 계획 후 진행한다.

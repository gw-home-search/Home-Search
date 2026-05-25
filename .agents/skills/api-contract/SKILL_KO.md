# API Contract Skill KO

> KO 생성 기준: canonical source only
> Source: `.agents/skills/api-contract/SKILL.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `.agents/skills/api-contract/SKILL.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

---
name: api-contract
description: Check Home Search API URL, request, response, unit, and error compatibility before backend/frontend changes.
---


# API Contract Skill

Use this skill when work touches API clients, controllers, DTOs, marker adapters, detail/trade flows, request validation, or error handling.

## Purpose

Keep `apps/api` and `apps/web` compatible with `docs/API_CONTRACT.md` before parallel work begins or lands.

## Required Inputs

- Root `AGENTS.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md` for map work.
- `docs/DATA_STORAGE.md` for trade/detail work.
- Related app `AGENTS.md` and `CONTEXT.md`.

## Checks

Confirm the work preserves:

- URL and HTTP method.
- Request field names and types.
- Response field names and types.
- Amount units.
- Coordinate conventions.
- Error status and `ProblemDetail` shape.
- Empty-result behavior.
- current/later-scope boundary.

## Frontend Rules

- Keep canonical marker fields: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- Accept source variants such as `id`, `latitude`, and `longitude` only inside adapters.
- Do not require backend contract changes for UI redesign.

## Backend Rules

- Return canonical project fields.
- Preserve `complex_id` as the operational trade relation.
- Do not expose audit fields such as `complex_pk`, `apt_seq`, `source`, or `source_key` in public trade responses unless the contract is explicitly changed first.
- Map endpoints must not require ranking, trend, favorite, alarm, mail, or auth state.

## Output

Report:

- Contract impact: none, compatible, or breaking.
- Required tests.
- Required stop condition if any breaking change is found.

Use Korean-first prose for user-facing summaries, but keep API field names,
paths, commands, and status tokens unchanged.

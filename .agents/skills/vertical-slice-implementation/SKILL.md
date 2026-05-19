---
name: vertical-slice-implementation
description: Break Home Search web/api work into thin V1 slices that can be implemented and verified incrementally.
---


# Vertical Slice Implementation Skill

Use this skill after a V1 plan exists and before implementation starts.

## Purpose

Convert a plan into small, independently verifiable slices. Each slice should deliver one observable path through the relevant layers.

This adapts incremental implementation and issue-breakdown patterns for Home Search.

## Slice Rules

Each slice must include:

- User-visible or API-visible behavior.
- Exact app ownership.
- API contract checkpoint.
- Data invariant checkpoint if backend is involved.
- Test seam.
- Verification command.
- Stop condition.

## Good Slice Examples

- Backend: raw RTMS ingest record is saved before normalized trade insert and duplicate source keys do not create duplicate trades.
- Backend: `/api/v1/map/complexes` returns canonical marker fields from V1 tables only.
- Frontend: map idle fetches complex markers, normalizes fields in the adapter, and keeps the map usable on API failure.
- Cross-app: marker click opens detail drawer using `/api/v1/detail/{parcelId}` and `/api/v1/trade/{parcelId}` without contract drift.

## Avoid

- Horizontal slices such as "copy all backend files" or "build all UI components".
- V2 scope.
- Unverified app-wide refactors.
- Cross-app changes without `api-contract`.

## Output

For each slice, provide:

- Slice name.
- App ownership.
- Files likely touched.
- Public seam.
- Tests.
- Verification.
- Parallelism: can run in parallel, blocks another slice, or must run first.

Use Korean-first prose in user-facing slice breakdowns while keeping commands,
paths, API names, and status tokens unchanged.

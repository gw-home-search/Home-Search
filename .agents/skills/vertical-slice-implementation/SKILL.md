---
name: vertical-slice-implementation
description: Sequence an approved Home Search plan into thin, independently verifiable web/api slices with explicit ownership, blocking edges, and parallelism before implementation starts. Use for "slice", "vertical slice", "slice order", "increment breakdown", "parallel work split", "슬라이스", "슬라이스 순서", "증분 분할", "병렬 분배". Do not use to choose direction or compare options (use planning), to run RED/GREEN loops (use tdd), or to review completed diffs (use code-review).
---

# Vertical Slice Implementation Skill

Use this skill after a plan exists and before implementation starts.

## Relationship To Planning

`planning` decides direction: evidence, classification, options, and a
PR-sized roadmap. This skill owns the step after that decision: turning an
approved roadmap into an execution sequence with slice boundaries, blocking
edges, and parallelism. Use it when sequencing is nontrivial — three or more
slices, cross-app work, or work split across parallel agents. For a small
single-slice plan, the `planning` roadmap alone is enough; do not re-slice it
here.

## Slice Rules

Each slice must include:

- User-visible or API-visible behavior. A slice that only moves files or adds
  unused code is not a vertical slice.
- Exact app ownership: `apps/property-data`, `apps/web`, or both.
- API contract checkpoint via `api-contract` when the slice touches a public
  URL, field, unit, or error shape.
- Data invariant checkpoint if backend is involved: raw-first ordering,
  duplicate-safe ingest, failed-match queryability, `complex_id` relation.
- Test seam and expected First RED, or an explicit RED waiver reason.
- Verification command that exists today (see Verification Commands).
- Stop condition.

## Ordering Rules

- Schema and contract slices come before the slices that consume them.
- Keep behavior-preserving moves and behavior changes in separate slices.
- A slice must stay revertable on its own: if reverting one slice would break
  an already-merged slice, the boundary is wrong.
- Prefer finishing one observable path over starting several partial paths.

## Good Slice Examples

- Backend: raw RTMS ingest record is saved before normalized trade insert and duplicate source keys do not create duplicate trades.
- Backend: `/api/v1/map/complexes` returns canonical marker fields from baseline tables only.
- Frontend: map idle fetches complex markers, normalizes fields in the adapter, and keeps the map usable on API failure.
- Cross-app: marker click opens detail drawer using `/api/v1/detail/{parcelId}` and `/api/v1/trade/{parcelId}` without contract drift.

## Avoid

- Horizontal slices such as "copy all backend files" or "build all UI components".
- later-scope work inside a current-scope slice.
- Unverified app-wide refactors.
- Cross-app changes without `api-contract`.
- Slices whose verification command does not exist yet.

## Verification Commands

Name only commands that exist:

- Backend slice: `cd apps/property-data && ./gradlew backendQualityCheck`
  (includes `persistenceTest`; plain `test` skips PostGIS integration tests).
- Frontend slice: `cd apps/web && npm run test` and `cd apps/web && npm run build`.
- Infra slice: `docker compose -f infra/docker-compose.local.yml config`.

## Output

For each slice, provide:

- Slice name.
- App ownership.
- Files likely touched.
- Public seam.
- Tests and expected First RED, or the RED waiver reason.
- Verification command.
- Parallelism: `parallel-ok`, `blocks <slice>`, or `must-run-first`, with the
  blocking reason stated.

Use Korean-first prose in user-facing slice breakdowns while keeping commands,
paths, API names, and status tokens unchanged.

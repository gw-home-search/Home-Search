# apps/web Agent Rules

## Scope

This directory owns the Home Search V1 frontend: Vite React runtime, Kakao map display, API clients/adapters, search, region navigation, filters, detail drawer, trade list/chart, and frontend verification.

## Must Read

Read root `AGENTS.md`, then:

1. `docs/API_CONTRACT.md`
2. `docs/MAP_DISPLAY_FLOW.md`
3. `docs/UI_UX_MIGRATION.md`
4. `CONTEXT.md`
5. `apps/web/CONTEXT.md`

## Writable Scope

Allowed:

- `apps/web/**`

Do not edit outside `apps/web/**` unless the user explicitly approves it.

## Do Not Modify

- `apps/api/**`
- `docs/API_CONTRACT.md`
- Root `AGENTS.md`
- Root `README.md`
- `ai-docs/**`
- Source backend repository
- Source frontend repository
- Secrets or local env values

## API Contract Guardrail

Call the V1 API routes exactly as documented.

Temporary source field variants such as `id`, `latitude`, and `longitude` belong in frontend adapters only. New target code should prefer canonical fields.

## Frontend Work Start Flow

Before changing frontend behavior, complete this flow:

1. Confirm the goal/spec and affected V1 UI/API surface.
2. Read root `AGENTS.md`, canonical docs listed above, `CONTEXT.md`, and
   `apps/web/CONTEXT.md`.
3. Map the current call flow with `code-mapper` when existing frontend code or
   source-reference flow affects the change.
4. Run a contract checkpoint with `contract-reviewer` before changing API
   clients, adapters, fixture/mock response shapes, request params, route
   usage, or any frontend behavior coordinated with `apps/api`.
5. Use `tdd-guide` to validate the first RED when the change touches API
   adapter normalization, marker transform, map fallback,
   loading/empty/error state, fixture/mock contract, or detail/trade drawer
   behavior.
6. Implement only the minimum GREEN slice inside `apps/web/**`.
7. Run the narrowest available npm verification command first, then broader
   available checks.
8. Use `reviewer` or `.agents/skills/code-review` for findings-first
   self-review before claiming completion.

Every frontend behavior slice must state:

- Goal/spec.
- API contract impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

Preferred frontend RED candidates:

- API adapter normalizes canonical fields and temporary source variants.
- Marker transform preserves `parcelId`, `lat`, `lng`, `latestDealAmount`, and
  `unitCntSum`.
- Marker API failure clears stale markers, keeps the map usable, and shows
  non-blocking error state.
- Loading, empty, and error states exist for marker fetch, search, region
  navigation, detail, and trade list.
- Fixtures and mocks preserve documented V1 URLs, fields, types, units,
  coordinate conventions, and empty-result behavior.

## Frontend TDD Usage

Use `.agents/skills/tdd` before frontend behavior changes when a valid RED can
be created.

Before changing production behavior, state:

- First RED test: the first failing behavior test to write.
- Public seam: the externally observable UI or API-client boundary under test.
- Test file candidate: the expected test file or test package.
- Expected RED failure: the failure reason before production changes.
- Minimum GREEN slice: the smallest frontend change that should make the RED
  pass.
- Verification commands: the narrow command first, then broader npm checks
  when available.

No RED exception:

- If no valid RED can be created yet, state why no valid RED exists, the needed
  public seam, temporary verification, and the follow-up test to add.
- Do not claim frontend behavior completion from temporary verification or
  browser smoke verification alone.

Prefer these frontend public seams:

- API adapter normalization: V1 route, params, canonical fields, and temporary
  source variants such as `id`, `latitude`, and `longitude`.
- Marker transform: region marker fields, complex marker fields, coordinate
  normalization, `parcelId`, `latestDealAmount`, and `unitCntSum`.
- Map failure fallback: marker API failure clears stale markers, keeps the map
  usable, and shows a non-blocking error state.
- UI request state: loading, empty, and error states for marker fetch, search,
  region navigation, detail, and trade list flows.
- Fixture/mock contract: mocks and fixtures must preserve documented V1 URLs,
  fields, types, coordinate conventions, amount units, and empty-result
  behavior.
- Detail/trade drawer: complex marker click uses `parcelId`, opens detail
  state, and calls `/api/v1/detail/{parcelId}` and
  `/api/v1/trade/{parcelId}` without contract drift.

Browser smoke verification is useful for map behavior, but it does not replace
the first RED when a deterministic test seam exists.

## Verification Rule

After `apps/web/package.json` exists, inspect scripts and run existing commands only. Typical checks are:

- `npm run lint`
- `npm run build`

Use browser smoke verification when map UI behavior changes.

## Frontend/Backend Conflict Prevention

Frontend must not require backend contract changes for UI redesign.

If a backend response change appears necessary, stop and use the `api-contract` skill before implementation.

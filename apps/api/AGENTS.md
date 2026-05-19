# apps/api Agent Rules


## Scope

This directory owns the Home Search V1 backend: Spring Boot runtime, V1 domain/application/web layers, Flyway migrations, ingest, API tests, and backend verification.

## Must Read

Read root `AGENTS.md`, then:

1. `docs/README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DATA_STORAGE.md`
4. `docs/API_CONTRACT.md`
5. `docs/INFRA_AND_ENV.md`
6. `CONTEXT.md`
7. `apps/api/CONTEXT.md`

## Writable Scope

Allowed:

- `apps/api/**`

Do not edit outside `apps/api/**` unless the user explicitly approves it.

## Do Not Modify

- `apps/web/**`
- `docs/API_CONTRACT.md`
- Root `AGENTS.md`
- Root `README.md`
- `ai-docs/**`
- Source backend repository
- Source frontend repository
- Secrets or local env values

## API Contract Guardrail

Keep every V1 URL, method, field name, field type, unit, and error policy documented in `docs/API_CONTRACT.md`.

Stop before changing public contract.

## Data Guardrail

- Raw ingest records are saved before normalized trade rows.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- The operational trade relation is `complex_id`.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key`.

## Backend Work Start Flow

Before changing backend behavior, complete this flow:

1. Confirm the goal/spec and affected V1 surface.
2. Read root `AGENTS.md`, canonical docs listed above, `CONTEXT.md`, and
   `apps/api/CONTEXT.md`.
3. Map the current call flow with `code-mapper` when existing backend code or
   source-reference flow affects the change.
4. Run a contract checkpoint with `contract-reviewer` before changing
   controllers, DTOs, validation, error policy, API response fields, or any
   backend behavior coordinated with `apps/web`.
5. Use `tdd-guide` to validate the first RED when the change touches
   Controller/DTO, Application service, Repository/Flyway, ingest, or External
   API adapter behavior.
6. Implement only the minimum GREEN slice inside `apps/api/**`.
7. Run the narrowest verification command first, then broader available Gradle
   checks.
8. Use `reviewer` or `.agents/skills/code-review` for findings-first
   self-review before claiming completion.

## Backend Execution Gate

Before any backend write, classify the work as one of:

- Scaffold slice: creates or wires the backend runtime or test environment
  without changing V1 behavior.
- Behavior slice: changes Controller/DTO, application service,
  repository/Flyway, ingest, or external API adapter behavior.
- Debugging slice: starts from a failing command, API mismatch, ingest failure,
  or map marker failure.
- Review slice: inspects completed changes without editing.

For a behavior slice, run the gates in this order:

1. `contract-reviewer` before any V1 URL, request, response, validation, error
   policy, or web/api coordinated behavior.
2. `code-mapper` when existing target code or source-reference flow affects the
   slice.
3. `tdd-guide` to confirm the first RED, public seam, expected RED failure,
   and minimum GREEN.
4. Implement only the minimum GREEN inside `apps/api/**`.
5. Use `.agents/skills/systematic-debugging` when a check fails or behavior
   does not match the contract.
6. Use `reviewer` or `.agents/skills/code-review` before claiming completion.

If no executable backend test environment exists, set the TDD gate decision to
`blocked/no test environment`, name the scaffold slice required to create it,
and name the follow-up First RED. Do not treat temporary verification as
behavior completion.

Every backend behavior slice must state:

- Goal/spec.
- API contract impact.
- Data invariant impact.
- First RED test.
- Public seam.
- Test file candidate.
- Expected RED failure.
- Why this is a valid RED.
- Minimum GREEN slice.
- Verification commands.
- Web/API collision risk.

Use `architecture-reviewer` before implementation when the change may cross
application/domain/infrastructure boundaries, introduce hidden coupling,
reinterpret `complex_id` or `complex_pk`, or create an ADR candidate.

## Backend TDD Usage

Use `.agents/skills/tdd` before backend behavior changes when a valid RED can
be created.

Before changing production behavior, state:

- First RED test: the first failing behavior test to write.
- Public seam: the externally observable backend boundary under test.
- Test file candidate: the expected test file or test package.
- Expected RED failure: the failure reason before production changes.
- Minimum GREEN slice: the smallest backend change that should make the RED
  pass.
- Verification commands: the narrow command first, then broader Gradle checks
  when available.

No RED exception:

- If no valid RED can be created yet, state why no valid RED exists, the needed
  public seam, temporary verification, and the follow-up test to add.
- Do not claim backend behavior completion from temporary verification alone.

Prefer these backend public seams:

- Controller/DTO/API contract: V1 URL, method, request field, response field,
  field type, amount unit, coordinate convention, empty-result behavior, and
  ProblemDetail error shape.
- Application service: raw ingest save before normalized insert, complex
  matching, duplicate handling, failed-match status, and normalized trade
  status transition.
- Repository/Flyway: uniqueness, partition/default partition behavior, latest
  trade lookup, failed match queryability, and `complex_id` operational joins.
- External API adapter: RTMS parsing, source key normalization, invalid source
  data handling, and external failure mapping.

If the first RED would require changing a V1 public URL, field, type, unit, or
error policy, stop before implementation and run an API contract review.

## Verification Rule

After `apps/api` exists, inspect available Gradle tasks first. Run the narrowest relevant test, then run `./gradlew test` or `./gradlew verify` when present.

## Frontend/Backend Conflict Prevention

Backend must not add response fields as a substitute for frontend adapter work.

Map endpoints must not require ranking, trend, favorite, alarm, mail, auth, recommendation, or heavy analytics state.

# apps/api Agent Rules


## Scope

This directory owns the Home Search backend: Spring Boot runtime, domain/application/web layers, Flyway migrations, ingest, API tests, and backend verification.

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
- Source backend repository
- Source frontend repository
- Secrets or local env values

## API Contract Guardrail

Keep every public API URL, method, field name, field type, unit, and error policy documented in `docs/API_CONTRACT.md`.

Stop before changing public contract.

## Data Guardrail

- Raw ingest records are saved before normalized trade rows.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- The operational trade relation is `complex_id`.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key`.

## Backend Package Boundary

Keep the backend `layered-first` and use feature names only under the layer:

```text
com.home
├── application/
│   ├── map/
│   ├── read/
│   ├── ingest/
│   ├── complex/
│   ├── coordinate/
│   └── news/
├── domain/
│   ├── region/
│   ├── parcel/
│   ├── complex/
│   ├── trade/
│   ├── ingest/
│   └── coordinate/
├── infrastructure/
│   ├── web/
│   ├── persistence/
│   ├── external/
│   └── observability/
└── global/
```

Package responsibilities:

- `application/**` owns use cases, application services, commands/results,
  policies, and repository or external-system ports. It may depend on
  `domain/**`, but must not depend on `infrastructure/**`, Spring Web DTOs,
  JDBC row mapping, or external API response DTOs.
- `domain/**` owns pure project concepts and rules for region, parcel,
  complex, trade, ingest identity, and coordinate decisions. It must not depend
  on Spring, JDBC, HTTP clients, Flyway, external API DTOs, or web DTOs.
- `infrastructure/web/**` owns controllers, public request/response DTOs,
  validation, interceptors, and ProblemDetail mapping. Any public field, unit,
  status, or error shape here must match `docs/API_CONTRACT.md`.
- `infrastructure/persistence/**` owns JDBC, SQL, PostGIS, Redis cache
  adapters, Flyway-facing repository implementations, and persistence
  configuration.
- `infrastructure/external/**` owns RTMS, VWorld, ODCloud, Naver, and other
  external API adapters. Provider response DTOs stay inside the provider
  package, usually under `dto`.
- `infrastructure/observability/**` owns metrics, actuator-facing
  instrumentation, and endpoint interceptors that do not change business
  behavior.
- `global/**` is limited to cross-cutting runtime support such as shared error
  handling. Do not place feature logic, repositories, DTOs, or domain policies
  in `global/**`.

Dependency direction:

- Default direction is `infrastructure -> application -> domain`.
- `application/**` defines ports; `infrastructure/**` implements them.
- `domain/**` is the lowest layer and must not import `application/**` or
  `infrastructure/**`.
- `infrastructure/web/**` maps public DTOs to application commands or queries;
  public DTOs must not be reused as application or domain models.
- `infrastructure/persistence/**` maps database rows to application/domain
  results; database table shape must not leak into public API DTOs.

New class placement rules:

- Put a new class in the layer that owns the reason it changes, then choose the
  feature package under that layer.
- Keep repository interfaces and external-client ports in `application/<feature>`
  unless the package becomes too large; introduce `port` subpackages only as a
  focused follow-up refactor.
- Put controller request/response DTOs in the matching `infrastructure/web`
  feature package, usually under `dto`.
- Put JDBC repository implementations in the matching
  `infrastructure/persistence` feature package with a `Jdbc` prefix when that
  matches the existing naming pattern.
- Put external provider DTOs and parsers under the provider package in
  `infrastructure/external`.
- Do not create new top-level feature packages outside `application`,
  `domain`, `infrastructure`, or `global`.

Application capability package rules:

- Keep `application/**` feature-first. The first child under `application` must
  stay a project feature such as `map`, `read`, `ingest`, `complex`,
  `coordinate`, or `news`.
- Split inside `application/<feature>` when that feature has roughly 20 or more
  classes, or when it clearly contains three or more independent capabilities.
- Use business capability package names, not generic role package names. Avoid
  `common`, `dto`, `model`, `service`, and `util` under `application/**`.
- Keep use cases, commands/results, policies, enums, and ports close to the
  capability that owns their reason to change.
- Do not introduce a generic `port` subpackage by default. Use one only when
  a single capability has enough ports that colocating them with the capability
  logic becomes harder to scan.
- Keep small feature packages such as `map`, `read`, and `complex` flat until
  they cross the same size or capability threshold.
- Tests should mirror the production package after package moves.
- Package moves must be behavior-preserving and must not be mixed with public
  API, Flyway, data interpretation, or later-scope feature changes.

Package refactor gate:

- Documentation-only package guidance does not require TDD.
- Before moving Java packages, map the affected flow with `code-mapper` and
  keep the move behavior-preserving.
- If a move touches controllers, DTOs, validation, error policy, or response
  fields, run a contract checkpoint before implementation.
- If a move changes application, repository, ingest, or external adapter
  behavior, treat it as a behavior slice and use the Backend Execution Gate
  below.
- Do not mix later-scope packages or behavior into the map/trade critical path.

## JavaDoc And Enum Conventions

Backend JavaDoc should be Korean-first for application and domain concepts that
encode project decisions, statuses, reasons, or user-visible report semantics.
Keep Java identifiers, API fields, database columns, CLI tokens, and persisted
enum values in their canonical English form.

Application or domain enums that model a project state, reason, classification,
decision, confidence, or mode must:

- Keep enum constants stable unless a migration or API contract change is
  explicitly approved.
- Declare Korean `titleKo` and `descriptionKo` metadata for every constant.
- Expose `titleKo()` and `descriptionKo()` accessors.
- Include Korean class-level JavaDoc that states the project meaning and storage
  boundary for the enum.
- Own repeated predicates, transition checks, count helpers, retry checks, and
  decision-specific score adjustments when that logic naturally belongs to the
  enum.
- Avoid exposing enum title or description through public DTOs unless
  `docs/API_CONTRACT.md` is changed first.

Do not create a generic enum-description framework unless repeated local enum
metadata becomes a real maintenance cost. Prefer small enum-owned methods such
as `isResolved()`, `isRetryable()`, `requiresManualReview()`, or
`confidenceBonus()` when they remove duplicated branching from services.

Enum documentation or logic changes should have narrow tests. Run `javadoc`
when adding or changing JavaDoc.

## Backend Work Start Flow

Before changing backend behavior, complete this flow:

1. Confirm the goal/spec and affected project surface.
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
  without changing project behavior.
- Behavior slice: changes Controller/DTO, application service,
  repository/Flyway, ingest, or external API adapter behavior.
- Debugging slice: starts from a failing command, API mismatch, ingest failure,
  or map marker failure.
- Review slice: inspects completed changes without editing.

For a behavior slice, run the gates in this order:

1. `contract-reviewer` before any public API URL, request, response, validation, error
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

- Controller/DTO/API contract: public API URL, method, request field, response field,
  field type, amount unit, coordinate convention, empty-result behavior, and
  ProblemDetail error shape.
- Application service: raw ingest save before normalized insert, complex
  matching, duplicate handling, failed-match status, and normalized trade
  status transition.
- Repository/Flyway: uniqueness, partition/default partition behavior, latest
  trade lookup, failed match queryability, and `complex_id` operational joins.
- External API adapter: RTMS parsing, source key normalization, invalid source
  data handling, and external failure mapping.

If the first RED would require changing a project public URL, field, type, unit, or
error policy, stop before implementation and run an API contract review.

## Verification Rule

After `apps/api` exists, inspect available Gradle tasks first. Run the narrowest relevant test, then run `./gradlew test` or `./gradlew verify` when present.

## Frontend/Backend Conflict Prevention

Backend must not add response fields as a substitute for frontend adapter work.

Map endpoints must not require ranking, trend, favorite, alarm, mail, auth, recommendation, or heavy analytics state.

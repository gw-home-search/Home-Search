# apps/property-data Agent Rules

## Scope

This directory owns the Home Search property-data backend. It is one service
boundary split into Gradle modules, not separate MSAs.

Keep public map/search/detail/trade API compatibility, data traceability, and
safe ingest behavior ahead of feature expansion.

## Module Boundary

```text
apps/property-data
├── core
│   └── domain, application, persistence, external clients, cache, Flyway
└── api
    └── Spring Boot app, web controllers, DTOs, scheduling, global errors,
        runtime resources, REST Docs/OpenAPI
```

Dependency direction:

```text
api -> core -> libs/rtms-ingest-core
```

Rules:

- `api` may depend on `core`.
- `core` must not depend on `api`.
- `core` must not contain Spring Boot entrypoints, web controllers, public DTOs,
  scheduling runtime, or API resource profiles.
- `api` must not own domain meaning, persistence SQL, Flyway migrations, or
  external provider parsing that belongs to reusable backend logic.
- Keep Java package names stable unless a package rename is explicitly scoped.

## Must Read

Read root `AGENTS.md`, then:

1. `docs/README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DATA_STORAGE.md`
4. `docs/API_CONTRACT.md`
5. `docs/INFRA_AND_ENV.md`
6. `CONTEXT.md`
7. `apps/property-data/CONTEXT.md`

For module split, Spring Batch, package structure, or runtime ownership work,
also read `docs/RESTRUCTURING_PLAN.md`.

## Writable Scope

Default allowed scope:

- `apps/property-data/**`

Do not edit outside `apps/property-data/**` unless the user explicitly approves
repo-wide docs, automation, contract, or cross-app changes.

Do not edit these without explicit approval for that surface:

- `apps/web/**`
- `docs/API_CONTRACT.md`
- Root `AGENTS.md`
- Root `README.md`
- Source backend repository
- Source frontend repository
- Secrets or local env values

## Contract And Data Guardrails

- Preserve every public API URL, method, field name, field type, unit,
  coordinate convention, empty-result behavior, and ProblemDetail error shape
  documented in `docs/API_CONTRACT.md`.
- Raw ingest records are saved before normalized trade rows.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- The operational trade relation is `complex_id`.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key`.
- Map endpoints must not depend on ranking, trend, favorite, alarm, mail, auth,
  recommendation, or heavy analytics state.

Stop before implementation if a change requires a public API change, data-loss
migration, reinterpretation of `complex_id` or `complex_pk`, or a Docker volume
reset.

## Placement Rules

Use the layer and module that owns the reason to change:

- `core/src/main/java/com/home/domain/**`: durable business states, reasons,
  classifications, confidence values, source identities, matching outcomes,
  dedupe identities, and state-transition rules. Domain code must not import
  Spring, JDBC, HTTP clients, Flyway, `application/**`, or `infrastructure/**`.
- `core/src/main/java/com/home/application/**`: use cases, commands, queries,
  orchestration results, ports, and application services.
- `core/src/main/java/com/home/infrastructure/persistence/**`: JDBC, SQL,
  PostGIS, repository implementations, constraints, locks, and row mapping.
- `core/src/main/java/com/home/infrastructure/external/**`: RTMS, VWorld,
  ODCloud, prediction, and other provider clients/parsers.
- `core/src/main/java/com/home/infrastructure/cache/**`: cache adapters and
  cache-specific lookup state.
- `core/src/main/resources/db/migration/**`: Flyway migrations for the
  property-data database.
- `api/src/main/java/com/home/infrastructure/web/**`: controllers, public
  request/response DTOs, validation, interceptors, and HTTP behavior.
- `api/src/main/java/com/home/infrastructure/scheduling/**`: scheduled runtime
  entrypoints and execution templates.
- `api/src/main/java/com/home/global/**`: API runtime error handling and other
  cross-cutting API support only.
- `api/src/main/resources/**`: runtime profiles and API app resources.

Avoid generic `common`, `shared`, `util`, `model`, or role-only packages. Prefer
feature/capability names that match the project domain.

## Workflow

- Use the repo skills and agents named in root `AGENTS.md` for planning, TDD,
  debugging, contract review, code review, and security audit.
- Before changing contract-adjacent code, run a contract checkpoint.
- Before changing backend behavior, use a valid RED test when one can be
  created. If no valid RED exists, state the reason and the follow-up seam.
- Keep package moves behavior-preserving and separate from public API, Flyway,
  data interpretation, or later-scope feature changes.
- Do not claim completion without verification evidence.

## Verification

Run the narrowest relevant module task first, then the aggregate gate before
completion.

Narrow checks:

```bash
./gradlew :core:test
./gradlew :core:persistenceTest
./gradlew :api:test
./gradlew :api:apiContractTest
./gradlew :api:restDocsTest
./gradlew :api:apiDocsCheck
```

Canonical backend gate:

```bash
./gradlew backendQualityCheck
```

Additional checks when relevant:

```bash
docker compose -f ../../infra/docker-compose.local.yml config
bash -n ops/*.sh
./ops/run-daily-batch-live-smoke.sh --self-test
./ops/check-daily-batch-live-smoke.sh --self-test
./ops/verify-clean-db-cutover.sh --self-test
git diff --check
```

Run commands from `apps/property-data` unless the command path explicitly starts
from the repository root.

## Stop Conditions

Stop and ask before:

- Changing a public API URL, response shape, field type, unit, coordinate
  convention, or error body.
- Dropping, deleting, or reinterpreting persisted data.
- Editing applied migrations in a way that changes already-run history.
- Making `domain/**` depend on application, infrastructure, Spring, JDBC, HTTP,
  or Flyway.
- Introducing secrets, local env values, or provider keys into tracked files.
- Running destructive Docker commands such as volume removal or `down -v`.
- Adding `batch-app`, Spring Batch, new `libs/*`, auth, ranking, favorites,
  alarms, mail, recommendations, or heavy analytics without explicit scope.

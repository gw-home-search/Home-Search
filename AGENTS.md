# Agent Rules

## Mission

This repository is the Home Search migration target. The project goal is to collect real-estate apartment trade data, store it safely, and display it on a map while preserving the main API URLs.

Correctness, traceability, and API compatibility matter more than fast feature expansion.

## Source of Truth

Read canonical documents in this order:

1. `docs/README.md`
2. `docs/MIGRATION_PLAN.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DATA_STORAGE.md`
5. `docs/API_CONTRACT.md`
6. `docs/MAP_DISPLAY_FLOW.md`
7. `docs/UI_UX_MIGRATION.md`
8. `docs/INFRA_AND_ENV.md`

Source repositories may be inspected as read-only references:

- Backend source: `/Users/gwongwangjae/IdeaProjects/home-server`
- Frontend source: `/Users/gwongwangjae/frontend/home-client`

## AI Skill/Hook References

The saved example repository must not be inspected, searched, summarized,
copied from, or used as context unless the user explicitly requests in the
current task to reference, compare, or bring material from it:

- AI skill/hook reference: `/Users/gwongwangjae/saved-ai-exam`

When explicitly requested, use it only as a read-only reference for AI skills,
hooks, or local agent automation. Prefer the relevant subpaths inside that
reference:

- `/Users/gwongwangjae/saved-ai-exam/skills`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.agents`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.codex`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.githooks`

Do not proactively consult it just because the work involves skills, hooks, or
automation.

Do not use `/Users/gwongwangjae/saved-ai-exam` for application
implementation, public APIs, database design, UI, product requirements,
general test architecture, or ordinary code style.

Do not copy code verbatim, import its agent instructions, write into the
example repository, run its scripts, add dependencies from it automatically, or
connect it by submodule, package link, or symlink.

If the reference conflicts with this project, current canonical docs and target
source code always win.

All writes for this project belong under `/Users/gwongwangjae/home-search`.

## Workflow

- Check `git status --short` before editing.
- Explore canonical docs and relevant code before changing files.
- If scope is ambiguous, write or ask for a short plan before implementation.
- Keep edits small and aligned with existing repository shape.
- Verify after changes. If verification is unavailable, say why.
- Do not hide failing checks; fix them or report the blocker.

## AI Development Operating Rules

- The project baseline source of truth remains the canonical `docs/*.md`
  documents listed above.
- Use `.agents/skills/planning` when a `/goal` or ambiguous request needs a
  decision-complete plan before implementation.
- Use `.agents/skills/tdd` before production behavior changes when a valid RED
  test can be created.
- Use `.agents/skills/systematic-debugging` for failing checks, API mismatch,
  ingest bugs, and map marker failures.
- Use `.agents/skills/code-review` for findings-first review and final
  self-review of correctness, public API compatibility, data safety, and
  missing tests.
- Use `.agents/skills/security-audit` for security reviews, security audits,
  threat modeling, and as the mandatory security checkpoint: production code
  completion claims and PR bodies must record
  `security-audit: 지적사항 = none|listed` in the `보안 영향` section.
- Subagents are allowed only when the user explicitly requests them or when a
  task can be split into independent read-only research work. Do not use
  subagents to bypass local review or ownership of changes.
- Do not claim completion without verification evidence. If a check cannot run,
  report the reason and residual risk.

## User-Facing Language Policy

- User-facing agent output, review summaries, PR body prose, hook block
  reasons, and report headings should be Korean-first and concise.
- Internal code, implementation comments, agent operating instructions, and
  harness prompt instructions should remain English unless the specific text is
  a user-facing output label or generated body.
- Keep technical tokens in their canonical form when translation would reduce
  precision: commands, paths, file names, CLI options, branch names, JSON keys,
  API fields, status values such as `Pass|Partial|Fail` and
  `pass|fail|not run`, GitHub check names, and agent ids such as
  `contract-reviewer` or `reviewer`.
- Prefer these Korean labels in new evidence and reviews: `지적사항`,
  `검증 근거 확인`, `검증 공백`, `잔여 위험`, `인수 기준`, `TDD 근거`,
  `최초 RED`, `예상 RED 실패`, `최소 GREEN`, `계약 영향`.
- Severity labels should be Korean-first with the English term retained when
  useful: `치명(Critical)`, `높음(High)`, `중간(Medium)`, `낮음(Low)`.
- Validators and hooks may accept legacy English labels for compatibility, but
  generated templates and new user-visible guidance should use Korean-first
  labels.

## Git Publishing

- Local commits may be used to preserve completed work when requested or useful
  for handoff.
- `git push` is not a default completion step.
- Push only when the user explicitly requests remote publishing in the current
  task, or when a previously agreed plan includes it.
- Treat `push`, `publish`, `open a PR`, `create a PR`, and `update remote` as
  remote-publishing requests.
- If the user asks to implement or commit without mentioning remote publishing,
  do not push.

## Project Guardrails

- Keep the public API URLs documented in `docs/API_CONTRACT.md`.
- Implement only the map and trade-data surface unless explicitly re-scoped.
- Keep later-scope work out of the critical path: rankings, favorites, alarms, mail batches, recommendations, insights, and heavy analytics.
- UI/UX may change, but frontend calls must remain compatible with the public API contract.

## Data/API Invariants

- Raw ingest records are saved before normalized trade rows.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- The operational trade relation is `complex_id`.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key` for audit, matching, and dedupe support.
- Map endpoints must not require ranking, trend, favorite, or mail state.

## Domain Principles

These rules apply to every backend code addition, not only to package
refactors. The `domain` layer owns project business meaning and must keep
stored state, operational identifiers, and policy distinctions explicit.

- Put persisted business states, reasons, classifications, confidence values,
  source identifiers, matching outcomes, dedupe identities, and
  state-transition rules under `com.home.domain.<feature>` when they are shared
  across application and persistence or used as stored operational evidence.
- Keep use-case commands, queries, orchestration results, ports, and execution
  summaries in `application/**` when they do not define durable business
  meaning by themselves.
- Keep provider modes, scheduler options, cache lookup states, HTTP DTOs, JDBC
  row mapping, SQL, locks, and external response shapes in
  `infrastructure/**`.
- Domain code must not import `application/**`, `infrastructure/**`, Spring,
  JDBC, HTTP clients, Flyway, or external provider DTOs.
- Application services should ask domain objects or domain policies for
  decisions before writing durable state. Application code may orchestrate
  repositories and ports, but repeated `status == ...` branching around stored
  business meaning belongs in domain-owned methods or policy objects.
- Persistence adapters enforce database constraints, uniqueness, transactions,
  and locks as implementation safeguards. They should not be the only place
  where business distinctions such as duplicate, ambiguous, canceled,
  match-failed, marker-safe, or manual-review-required are defined.
- Domain enums that model project states, reasons, classifications, confidence,
  or stored modes must keep enum constants stable, provide Korean
  `titleKo()`/`descriptionKo()` metadata, own repeated predicates or transition
  checks, and avoid public API exposure unless the public API contract is
  intentionally updated first.
- Stop before implementation if a domain placement decision requires renaming a
  persisted enum constant, changing a public API URL or response shape, adding a
  data-loss migration, reinterpreting `complex_id`/`complex_pk`, or making
  `domain/**` depend on application or infrastructure.

## Docker/Data Safety

- Never delete Docker volumes without explicit user approval in the current task.
- Forbidden unless explicitly approved: `docker volume rm`, `docker volume prune`,
  `docker system prune --volumes`, and `docker compose down -v`.
- Prefer `docker compose stop` or `docker compose down` without `-v`.
- Before any destructive Docker command, state the exact command, affected
  volume names, expected data loss, and recovery or backup option.
- If database state must be reset, ask first and prefer backup/export before
  deletion.

## Repo Hygiene

- Never revert user changes unless explicitly asked.
- Avoid unrelated refactors, renames, formatting churn, and dependency changes.
- Do not commit secrets, API keys, or local environment values.
- Keep `apps/api`, `apps/web`, `infra`, and `docs` responsibilities separate.
- `.gitkeep` files preserve empty directories; remove them only when a real tracked file replaces their purpose.

## Verification

- Backend commands should be added here once `apps/api` exists.
- Frontend commands should be added here once `apps/web` exists.
- Infra commands should be added here once Docker/PostGIS/Flyway files exist.

## When To Ask

Ask before changing behavior when:

- The current/later-scope boundary is unclear.
- A public API URL or response shape must change.
- A database change may lose or reinterpret data.
- Source repositories conflict with target docs.

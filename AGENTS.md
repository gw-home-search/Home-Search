# Agent Rules

## Mission

This repository is the Home Search V1 migration target. The V1 goal is to collect real-estate apartment trade data, store it safely, and display it on a map while preserving the main API URLs.

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

## Example References

Example-code repositories may be inspected as read-only references after the
canonical docs and source repositories have been checked:

- Example code: `/Users/gwongwangjae/saved-ai-exam`

Use example code only for implementation patterns, project structure, test
setup, tooling configuration, and agent-rule examples. Example code is not a
source of truth for Home Search behavior, public APIs, schema, data semantics,
or product scope.

Do not copy example code verbatim, import its agent instructions, write into
the example repository, run its scripts, add dependencies from it automatically,
or connect it by submodule, package link, or symlink.

If an example conflicts with this project, follow this priority order:
canonical docs, target source code, backend/frontend source references, then
example references.

All writes for this project belong under `/Users/gwongwangjae/home-search`.

## Workflow

- Check `git status --short` before editing.
- Explore canonical docs and relevant code before changing files.
- If scope is ambiguous, write or ask for a short plan before implementation.
- Keep edits small and aligned with existing repository shape.
- Verify after changes. If verification is unavailable, say why.
- Do not hide failing checks; fix them or report the blocker.

## V1 Guardrails

- Keep the V1 API URLs documented in `docs/API_CONTRACT.md`.
- Implement only the V1 map and trade-data surface unless explicitly re-scoped.
- Keep V2 work out of the critical path: rankings, favorites, alarms, mail batches, recommendations, insights, and heavy analytics.
- UI/UX may change, but frontend calls must remain compatible with the V1 API contract.

## Data/API Invariants

- Raw ingest records are saved before normalized trade rows.
- Duplicate ingest must not create duplicate normalized trades.
- Failed matches must be explainable and queryable.
- The operational trade relation is `complex_id`.
- Preserve `complex_pk`, `apt_seq`, `source`, and `source_key` for audit, matching, and dedupe support.
- Map endpoints must not require ranking, trend, favorite, or mail state.

## Repo Hygiene

- Never revert user changes unless explicitly asked.
- Avoid unrelated refactors, renames, formatting churn, and dependency changes.
- Do not commit secrets, API keys, or local environment values.
- Keep `apps/api`, `apps/web`, `infra`, and `docs` responsibilities separate.
- `.gitkeep` files preserve empty directories; remove them only when a real tracked file replaces their purpose.

## Human-Only KO Docs

- `*_KO.md` files are human-only Korean reference files.
- AI agents must not read, search, summarize, quote, import, diff, or use `*_KO.md` or `*_KO.local.md` as implementation context.
- Treat lowercase variants such as `*_ko.md` the same way.
- KO sync metadata lives in `.ko-docs.toml`, not inside KO files.
- AI agents may read `.ko-docs.toml` and canonical `.md` files to decide whether KO docs are stale.
- When searching Markdown, exclude KO files:

```sh
rg --files -g '*.md' -g '!**/*_KO.md' -g '!**/*_KO.local.md' -g '!**/*_ko.md' -g '!**/*_ko.local.md'
```

- `scripts/check-ko-docs.sh` must not read or print KO body content. It may only check that KO paths from `.ko-docs.toml` exist.

## Markdown Update Rule

- For every canonical `.md` file, keep a same-location `*_KO.md` file.
- When a canonical `.md` is created, create its `*_KO.md`.
- When a canonical `.md` meaningfully changes, regenerate its `*_KO.md` from the canonical file only and update `.ko-docs.toml`.
- When a canonical `.md` is renamed or deleted, rename or delete the paired `*_KO.md`.
- Do not read the existing KO file to update it. Use the canonical source as the input and overwrite the KO file.
- Personal notes belong in `*_KO.local.md`; those files are ignored and must not be touched.

## Verification

- Run `scripts/check-ko-docs.sh` after Markdown changes.
- Backend commands should be added here once `apps/api` exists.
- Frontend commands should be added here once `apps/web` exists.
- Infra commands should be added here once Docker/PostGIS/Flyway files exist.

## When To Ask

Ask before changing behavior when:

- V1/V2 scope is unclear.
- A public API URL or response shape must change.
- A database change may lose or reinterpret data.
- Source repositories conflict with target docs.
- A KO file appears to contain different requirements than the canonical source.

---
name: planning
description: Convert Home Search /goal, ambiguous requests, cleanup/refactor goals, next-slice choices, acceptance criteria, module-separation ideas, code-smell reduction, test/DB pruning, and public API/data guardrail questions into evidence-backed, decision-complete plans. Use for "plan", "planning", "roadmap", "next slice comparison", "acceptance criteria", "API contract impact", "cleanup", "refactor", "code smell", "test diet", "DB cleanup", "module split", "목표", "플랜", "계획", "로드맵", "다음 slice 비교", "인수 기준", "정리", "리팩터링", "테스트 다이어트", "DB 정리", "분리". Do not use for failed command debugging or final diff review; route failures to systematic-debugging and review to code-review/reviewer.
---


# Planning Skill

Use this skill when a request is goal-level or has ambiguous scope. The goal is to produce an implementation-ready plan while stopping when public API or data invariants would change.

## Do Not Use

- Failed lint, test, build, hook, CI, runtime, or API reproduction work; use
  `systematic-debugging`.
- Final diff, gate, or PR review; use `code-review` or `reviewer`.
- Running First RED/GREEN loops; route behavior changes to `tdd`.

## Routes To

- `tdd` or `tdd-guide` when the plan needs First RED, expected RED failure,
  public seam, or minimum GREEN decisions.
- `contract-reviewer` when URL, request, response, unit, or error compatibility
  can change.
- `systematic-debugging` when the plan starts from a concrete failure.
- `code-review` or `reviewer` when the plan becomes a completed diff review.

## Inputs

- User request or `/goal` brief.
- `AGENTS.md`.
- Relevant canonical `docs/*.md`.
- Target code or source backend/frontend read-only references when needed.

## Planning Workflow

1. Restate the decision to make in one sentence.
2. Read the canonical docs that own the affected surface before recommending a
   direction. For backend work, include `apps/api/AGENTS.md` and
   `apps/api/CONTEXT.md`. For frontend work, include `apps/web/AGENTS.md` and
   `apps/web/CONTEXT.md`.
3. Check the current repository state with local evidence, not memory. Use `rg`,
   `find`, `wc`, package scripts, migration files, and config wiring as needed.
   Check `git status --short` before planning work that may become edits.
4. Classify each proposed target before ranking it:
   - `live`: active runtime path or documented invariant.
   - `live-capable`: row count may be zero, but current code/config can still use
     it safely.
   - `maintenance`: operational safety path such as reconciliation, partition
     maintenance, or smoke verification.
   - `one-shot`: migration/backfill/admin runner intended for bounded use.
   - `later-scope`: outside current map/trade critical path but intentionally
     parked.
   - `dead`: no reachable code path, no required migration history, no active
     docs, and safe to remove after data evidence.
5. Compare realistic options when a choice is non-obvious. Use a compact
   recommendation table with `효과`, `위험`, `검증 비용`, and `되돌리기`.
6. Convert the recommendation into PR-sized slices. Do not mix public contract
   changes, data deletion, package moves, behavior changes, and broad test
   rewrites in one slice unless the user explicitly accepts the blast radius.
7. State stop conditions before implementation. Stop when a plan would change a
   public API, reinterpret persisted data, drop non-empty tables, remove
   raw-first/dedupe/failure-queryability safeguards, or remove a live-capable
   path without an explicit decision.

## Evidence Rules

- Treat user-supplied counts as hypotheses until verified locally when feasible.
- Prefer concrete file paths, migration names, bean/config conditions, import
  edges, package counts, LOC counts, and table row-count queries over general
  impressions.
- For DB cleanup, require all of:
  - DDL owner migration or current table definition.
  - Runtime readers/writers and tests that reference the table.
  - Row-count evidence or a planned query for row-count evidence.
  - Migration strategy: append-only drop migration, never editing applied
    migrations unless the repository explicitly treats them as disposable.
  - Backup/export decision for any non-empty or uncertain table.
- For runner cleanup, distinguish `@Scheduled` jobs from `ApplicationRunner`
  one-shots and from maintenance runners. A runner with no schedule is not
  automatically dead.
- For module split plans, identify code ownership, package dependencies,
  Flyway/history ownership, shared DB/read dependencies, build settings, and
  deployment/runtime ownership separately.
- For frontend refactors, distinguish behavior-preserving extraction from UI/API
  behavior changes. Behavior-preserving extraction can use component-level tests
  and package checks; adapter, marker, detail, search, or error-state behavior
  needs a TDD gate.

## Required Plan Fields

- Goal.
- Scope.
- Non-scope.
- Touched subsystem: backend, frontend, data, infra, docs.
- App `AGENTS.md` and `CONTEXT.md` checked.
- Evidence gathered.
- Current classification: live, live-capable, maintenance, one-shot,
  later-scope, dead.
- Options considered and recommendation.
- Slice order and rollback path.
- Code-mapper preflight need.
- Contract-reviewer checkpoint.
- Public contract impact.
- Data invariant impact.
- Acceptance criteria.
- TDD gate decision: required, not applicable, or blocked/no test environment.
- TDD slice plan.
- Agent handoffs.
- Web/API collision risk.
- Verification commands.
- Stop conditions.

## Cleanup And Refactor Checklist

- Remove only `dead` code/data in a low-risk cleanup slice.
- Preserve or explicitly re-scope `live`, `live-capable`, and `maintenance`
  paths.
- Do not classify a table as dead from `0 rows` alone.
- Do not classify a runner as dead from "not scheduled" alone.
- Keep domain policies if another live path still uses the business distinction,
  even when a persistence table or admin surface is removed.
- Prefer "disable then remove" for one-shot or ops capabilities when runtime
  usage is uncertain.
- Keep tests that protect current invariants; delete tests only with the
  production behavior or obsolete contract they covered.
- When reducing large tests, preserve named invariant tests before deleting
  coverage. Move repeated setup to fixtures only if it makes failures easier to
  localize.
- For package/module moves, keep the first slice behavior-preserving and defer
  deletion or API changes.

## Backend Checklist

- public API URLs and response shapes remain stable.
- Raw ingest -> normalized trade ordering is preserved.
- Duplicate-safe ingest and failed match queryability are preserved.
- The `complex_id` operational relation is clear.
- later-scope features do not enter the critical path.

## Frontend Checklist

- Map, search, region, detail, and trade API compatibility is preserved.
- Marker adapter fields match the canonical contract.
- Map failure handling keeps the map usable.
- Verification includes only package scripts that exist.

## Recommended Output Shape

Use Korean-first prose and this order unless the user asks for a different
format:

1. `판단`: short diagnosis and recommended direction.
2. `근거`: local evidence with paths, counts, migrations, and config gates.
3. `분류`: what is live, live-capable, maintenance, one-shot, later-scope, dead.
4. `선택지`: compact comparison when there is a real choice.
5. `권장 로드맵`: PR-sized slices in execution order.
6. `인수 기준`: user-visible or invariant-level completion checks.
7. `검증`: exact commands or SQL evidence to collect.
8. `중단 조건`: conditions that require user approval or a different skill.

For very small plans, collapse these fields into short paragraphs but preserve
the same decisions.

## Output Rule

Keep the plan short and executable. Prefer TDD slice plans over generic test
plans for backend or frontend behavior changes. For backend/frontend behavior
slices, name the `tdd-guide` handoff when RED validity, public seam, expected
RED failure, or minimum GREEN is part of the plan; otherwise state the RED
waiver reason. Use Korean-first prose for the user-facing plan body while
keeping commands, paths, status tokens, and API names unchanged. Be explicit
about assumptions and weak evidence. If the user asked for implementation and no
stop condition is hit, proceed after the plan.

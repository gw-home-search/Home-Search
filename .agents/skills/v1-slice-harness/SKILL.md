---
name: v1-slice-harness
description: Route Home Search V1 slice workflow through the harness launcher for next/plan/dry-run/run/report, worktree orchestration, integration reports, push, draft PR, and PR lint evidence. Use for "V1 slice", "harness", "next slice", "dry-run", "gate review", "PR", "worktree", "다음 slice", "plan만", "진행해줘", "PR까지", "짧은 gate review", "hook evidence 복구". Does not replace planning, tdd, systematic-debugging, or code-review; routes gate/recover requests to those modes when needed.
---


# V1 Slice Harness

Use this skill when the user wants repeatable Home Search V1 slice operation
without pasting a long prompt. This skill is a launcher router plus routing
guide; it does not replace `planning`, `tdd`, `systematic-debugging`, or
`code-review`.

## Ground Rules

- Read `AGENTS.md`, `ai-docs/README.md`, and relevant non-KO canonical docs or
  skills before changing behavior.
- Do not read, summarize, diff, or reuse `*_KO.md` bodies.
- If a KO companion must be updated, regenerate it from the canonical
  `SKILL.md` without reading the existing KO body.
- Prefer the short launcher `.codex/harness/v1` for slice workflow execution.
  Use lower-level scripts only for debugging or when the launcher cannot
  express the required operation.
- If the user does not know the next slice, run `.codex/harness/v1 next`
  or use `$v1-slice-harness 다음 slice 골라줘` before proposing work.
- Before executing a named slice, run `.codex/harness/v1 plan <slice-id>` and
  then `.codex/harness/v1 dry <slice-id>` unless the user explicitly asks for
  planning only.
- Use `run` only after the plan and dry-run evidence are clear.
- Use `mode=execute` only when implementation or file mutation is requested.
- In `mode=plan`, `mode=gate`, `mode=next`, and `mode=recover`, do not edit
  files by default.
- Treat `planning-only` results as planning evidence, not permission to
  automatically implement.
- Hooks are gates only. Do not design or claim automatic fix, retry, commit,
  merge, push, or full test/build execution from hooks.
- Keep the final user-facing review short and Korean-first. Keep commands,
  paths, status tokens, and agent ids unchanged.

## Slice Identity

- This `SKILL.md` is not a backlog, preset, or slice registry.
- This `SKILL.md` does not define slice ids and must not be treated as a slice
  registry.
- `v1-slice-harness` is a slice workflow router for launcher operations
  `next`, `plan`, `dry`, `run`, and `report`; `gate` and `recover` are skill
  routing modes, not launcher subcommands.
- Durable routing policy lives in `.codex/harness/skill_routing.py` and the
  launcher/prompt files. This skill should stay thin so the harness can later
  be exposed through another entrypoint without losing the same skill routing
  behavior.
- Actual slice ids come from backlog, preset resolution, recent gate evidence,
  or `.codex/harness/v1 next` results.
- `<slice-id>` and `<target>` are placeholders in this document, not literal
  values to pass to the launcher.

## Short Prompt Grammar

Short prompts are user input examples only. They are not slice ids, preset ids,
or literal commands.

- `$v1-slice-harness 다음 slice 골라줘`
- `$v1-slice-harness <slice-id> plan만 세워줘`
- `$v1-slice-harness <slice-id> dry-run 해줘`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`
- `$v1-slice-harness <slice-id> 진행해줘`
- `$v1-slice-harness <slice-id> PR까지 만들어줘`
- `$v1-slice-harness <slice-id> merge push PR까지 해줘`
- `$v1-slice-harness <slice-id> push만 해줘`
- `$v1-slice-harness <slice-id> report 다시 보내줘`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`

## Launcher Mapping

Map short user prompts to the launcher first:

- next:
  `.codex/harness/v1 next`
- plan:
  `.codex/harness/v1 plan <slice-id>`
- dry:
  `.codex/harness/v1 dry <slice-id>`
- target dry:
  `.codex/harness/v1 dry <slice-id> --targets <target>`
- run:
  `.codex/harness/v1 run <slice-id>`
- PR or draft PR:
  `.codex/harness/v1 run <slice-id> --pr`
- merge + push + PR:
  `.codex/harness/v1 run <slice-id> --pr`
- push:
  `.codex/harness/v1 run <slice-id> --push`
- report:
  `.codex/harness/v1 report <slice-id>`
- sync stale backlog status from merged GitHub PRs:
  `.codex/harness/v1 sync-backlog --merged`
- inspect stale backlog status without writing:
  `.codex/harness/v1 sync-backlog --merged --dry-run`
- If the prompt mentions `notion`, append `--notion`.
- If the prompt mentions `slack`, append `--slack`.
- If the prompt names a target, append `--targets backend`,
  `--targets frontend`, `--targets both`, or `--targets planning-only`.

Launcher defaults:

- User provides only the slice id in the common path.
- Preset and target are resolved from slice id, backlog, or explicit options.
- Branch and worktree names are generated automatically.
- Default `run` performs commit, integration branch creation, and local report.
- After a non-planning integration succeeds, `run` marks the current backlog
  slice `done` on the integration branch and commits that metadata change.
  The `done` status reaches `main` only when the GitHub PR is merged.
- `backend` creates/runs only the backend worktree.
- `frontend` creates/runs only the frontend worktree.
- `both` creates/runs backend and frontend worktrees; `--parallel` is valid
  only for this target.
- `planning-only` creates no implementation worktree and must not run Codex
  execute, verification, gate, commit, integration, push, or PR automation.
- In short prompts, `merge` means merging prepared target branches into the
  generated `feat/*-integration` branch. It never means merging into `main` or
  merging the GitHub PR.
- Main merge, main push, PR merge, approve, live Open API calls, and DB
  migration execution are never automatic.
- `sync-backlog` reads merged GitHub PR state and updates stale local backlog
  metadata only. It never merges PRs, pushes branches, or changes app code.
- Remote push and draft PR creation happen only when the user explicitly asks
  for PR/push in the current prompt.
- `--push` pushes only the generated `feat/*-integration` branch after
  integration succeeds. `--pr` performs the same integration branch push, then
  creates a draft PR.
- PR requests always target the generated `feat/*-integration` branch and
  create a draft PR by default. The `--pr` flow must pass strict PR lint before
  creating the draft PR.
- `dry --pr` is command expansion and safety preflight only. Changed-file PR
  lint is fully enforced after a real integration branch exists.
- Notion and Slack are optional best-effort notifications and must not break the
  critical path. When PR creation is requested, PR URL notification happens only
  after the PR URL is known.
- Users should keep prompts short and natural; the launcher expands slice,
  preset, target, branch, worktree, and report details.

## Launcher Skill Routing

- `.codex/harness/v1 plan` and `.codex/harness/v1 next` render explicit
  `사용 skill:` evidence from `.codex/harness/skill_routing.py`.
- `.codex/harness/v1 run` injects `Skill routing:` into the Codex execute and
  gate prompts so target agents are prompted with the relevant `$tdd`,
  `$backend-api`, `$frontend-web`, `$api-contract`, `$systematic-debugging`,
  and `$code-review` triggers.
- The launcher does not pretend to own those workflows. It selects the mode and
  target context, then routes to the specialized skill that owns planning, RED
  validity, backend/frontend domain rules, contract checks, failure recovery,
  or final review.
- `planning-only` routes to planning evidence only and must not continue into
  implementation, verification, gate, commit, integration, push, or PR
  automation.
- Failure or hook-block evidence routes to `systematic-debugging`; behavior
  root causes then route to `tdd`, and completed recovery routes to
  `code-review`.

## Lint/Test/Build Handling

- Frontend verification commands must come from scripts that exist in
  `apps/web/package.json`. At this writing, use `cd apps/web && npm run test`
  and `cd apps/web && npm run build`; do not add `npm run lint` until the
  script exists.
- If frontend lint is introduced, update all matching enforcement points
  together: `.codex/harness/v1_flow.py` `KNOWN_VERIFICATION_COMMANDS`,
  `.codex/harness/presets/*.toml`, `.codex/hooks/post_tool_use_review.py`,
  `.codex/hooks/stop_verification_gate.py`, `.codex/harness/pr_lint.py`,
  `.codex/harness/v1_report.py`, and `.github/workflows/ci.yml`.
- Backend verification must not guess Gradle lint or check tasks. Use
  `cd apps/api && ./gradlew backendQualityCheck` as the canonical backend
  PR/CI quality gate. Use `test` only for ad-hoc debugging or explicit plans,
  not as the canonical PR evidence for `apps/api/**` changes.
- PR evidence is changed-file based: `apps/api/**` requires
  `backendQualityCheck`, `Coverage: >=90%`, and
  `Docs/OpenAPI: generated + verified`; `apps/web/**` requires `npm run test`
  and `npm run build`; harness, hook, GitHub workflow, Markdown, and KO changes
  require the matching self-tests and KO sync evidence.
- Verification command failures or hook blocks route to `mode=recover` and
  `systematic-debugging`. If the root cause is a behavior bug, route regression
  evidence through `tdd` or `tdd-guide`; after the fix, record the exact
  verification line and use `code-review` or `reviewer` before completion.

## Mode Selection

Choose a mode from the user's short prompt:

- `mode=plan`: "다음 slice 플랜", "이전 gate 기준 plan", "리뷰 기준으로 plan".
- `mode=execute`: "slice 실행", "backend slice 실행", "web slice 실행", "구현".
- `mode=gate`: "현재 slice gate", "짧은 gate review", "완료 전 리뷰".
- `mode=next`: "다음 slice", "다음 작업 추천".
- `mode=recover`: "hook이 막은 evidence 복구", "실패 복구", "검증 실패 복구".

If the mode is unclear, ask one concise question before changing files.

## Required Routing

Routing names the required review capability. Spawn subagents only when the
active operating rules allow it; otherwise perform the equivalent local skill
review and record the limitation in the final evidence.

- `mode=plan`:
  - Use `planning` for ambiguous goals.
  - Use `vertical-slice-implementation` when the target is `backend`,
    `frontend`, or `both` so the plan remains a thin independently
    verifiable V1 slice.
  - Use `tdd` when First RED can be planned.
  - Use `code-mapper`, `backend-planner`, or `frontend-planner` only for focused
    read-only research.

- `mode=execute`:
  - Use `tdd` for all behavior changes.
  - Use `contract-reviewer` for V1 API request, response, unit, or error impact.
  - Use `tdd-guide` when RED validity is uncertain.
  - Use `reviewer` or `code-review` before completion.

- `mode=gate`:
  - Use `reviewer` for implementation changes.
  - Use `contract-reviewer` for contract-adjacent changes.
  - Use `tdd-guide` for First RED validity.
  - If subagent spawning is allowed, spawn the matching read-only agent;
    otherwise run the local review path and state the limitation.

- `mode=next`:
  - Do not spawn by default.
  - Convert current gate evidence, missing tests, and risks into one to three
    next slice candidates.

- `mode=recover`:
  - Use `systematic-debugging` for reproducible failures.
  - Do not mutate files unless the user switches to `mode=execute`.

## Mode Rules

### mode=plan

- File mutation: forbidden.
- Convert previous gate `missing tests`, `findings`, and `주요 위험` into
  next-slice acceptance criteria.
- User-facing output labels:

```text
상태:
목표:
인수 기준:
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
다음 행동:
```

### mode=execute

- File mutation: allowed.
- Start with a valid First RED when practical; otherwise state
  `최초 RED: blocked/no test environment` in the user-facing evidence.
- Use the minimum GREEN implementation for the slice.
- Output labels:

```text
상태:
변경:
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
주요 위험:
다음 행동:
```

### mode=gate

- File mutation: forbidden.
- Produce a short Korean-first gate review that Stop hook evidence can
  recognize.
- Output labels:

```text
상태: Pass|Partial|Fail
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
리뷰:
주요 위험:
다음 행동:
```

### mode=next

- File mutation: forbidden.
- Recommend one to three next slices from current gate evidence.
- Prefer unfinished risks, missing tests, and contract/data safety gaps before
  new feature expansion.
- Use `.codex/harness/v1 next` when a backlog recommendation is needed.
- Output labels:

```text
상태:
현재:
다음 slice 후보:
인수 기준:
검증:
다음 행동:
```

### mode=recover

- File mutation: forbidden by default.
- Do not fix automatically. Turn hook blocks or verification failures into a
  recovery plan and the minimum evidence needed to continue.
- Output labels:

```text
상태:
차단 사유:
누락 evidence:
복구 순서:
검증:
다음 행동:
```

## Standard Evidence

Use these exact labels when closing a slice so the Stop hook and humans can read
the same evidence:

```text
상태: Pass|Partial|Fail
최초 RED: 있음|없음|blocked/no test environment
예상 RED 실패: 확인|미확인|해당 없음
최소 GREEN: 확인|미확인|해당 없음
검증: <command> = pass|fail|not run (<사유>)
tdd-guide: RED validity = Pass|Partial|Fail|not used
contract-reviewer: 게이트 결정 = Pass|Partial|Fail|not needed
reviewer: 지적사항 = none|listed|not run
주요 위험: 없음|<short risk>
다음 행동: <one short action>
```

## Examples Only

These examples explain prompt usage. They are not a slice registry and do not
define the set of valid slice ids.

- Assume `.codex/harness/v1 next` returned `kakao-map-marker-refresh-flow`.
- `$v1-slice-harness kakao-map-marker-refresh-flow plan만 세워줘`
- For target dry-run, replace `<slice-id>` with the same returned id:
  `$v1-slice-harness <slice-id> frontend target으로 dry-run 해줘`

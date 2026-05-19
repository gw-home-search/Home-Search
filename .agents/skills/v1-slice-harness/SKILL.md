---
name: v1-slice-harness
description: Orchestrate Home Search V1 slice workflow with short Korean prompts; supports mode=plan, mode=execute, mode=gate, mode=next, mode=recover. Use for "$v1-slice-harness", "다음 slice 플랜", "현재 slice gate", "짧은 gate review", "hook evidence 복구", "backend/web worktree slice 실행".
---

# V1 Slice Harness

Use this skill when the user wants repeatable Home Search V1 slice operation
without pasting a long prompt. This skill orchestrates the existing Home Search
skills; it does not replace `planning`, `tdd`, `systematic-debugging`, or
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
- Keep the final user-facing review short and Korean.

## Slice Identity

- This `SKILL.md` does not define slice ids and must not be treated as a slice
  registry.
- `v1-slice-harness` is a slice workflow router for plan, dry-run, run, gate,
  next, and recover operations.
- Actual slice ids come from backlog, preset resolution, recent gate evidence,
  or `.codex/harness/v1 next` results.
- `<slice-id>` and `<target>` are placeholders in this document, not literal
  values to pass to the launcher.

## Launcher UX

Map short user prompts to the launcher first:

- `$v1-slice-harness 다음 slice 골라줘` or `다음 작업 추천`:
  `.codex/harness/v1 next`
- `$v1-slice-harness <slice-id> plan만 세워줘`:
  `.codex/harness/v1 plan <slice-id>`
- `$v1-slice-harness <slice-id> dry-run 해줘`:
  `.codex/harness/v1 dry <slice-id>`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`:
  `.codex/harness/v1 dry <slice-id> --targets <target>`
- `$v1-slice-harness <slice-id> 진행해줘`:
  `.codex/harness/v1 run <slice-id>`
- `$v1-slice-harness <slice-id> PR까지 만들어줘` or `draft PR 생성해줘`:
  `.codex/harness/v1 run <slice-id> --pr`
- `$v1-slice-harness <slice-id> push만 해줘`:
  `.codex/harness/v1 run <slice-id> --push`
- If the prompt mentions `notion`, append `--notion`.
- If the prompt mentions `slack`, append `--slack`.
- If the prompt asks for report resend, use
  `.codex/harness/v1 report <slice-id>`.
- If the prompt names a target, append `--targets backend`,
  `--targets frontend`, `--targets both`, or `--targets planning-only`.

Launcher defaults:

- User provides only the slice id in the common path.
- Preset and target are resolved from slice id, backlog, or explicit options.
- Branch and worktree names are generated automatically.
- Default `run` performs commit, integration branch creation, and local report.
- `backend` creates/runs only the backend worktree.
- `frontend` creates/runs only the frontend worktree.
- `both` creates/runs backend and frontend worktrees; `--parallel` is valid
  only for this target.
- `planning-only` creates no implementation worktree and must not run Codex
  execute, verification, gate, commit, integration, push, or PR automation.
- Main merge, main push, PR merge, approve, live Open API calls, and DB
  migration execution are never automatic.
- Remote push and draft PR creation happen only when the user explicitly asks
  for PR/push in the current prompt.
- PR requests always target the generated `feat/*-integration` branch and
  create a draft PR by default.
- Notion and Slack are optional best-effort notifications and must not break the
  critical path. When PR creation is requested, PR URL notification happens only
  after the PR URL is known.
- Users should keep prompts short and natural; the launcher expands slice,
  preset, target, branch, worktree, and report details.

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
- Output labels:

```text
상태:
목표:
Acceptance Criteria:
First RED:
Expected RED failure:
Minimum GREEN:
검증:
다음 행동:
```

### mode=execute

- File mutation: allowed.
- Start with a valid First RED when practical; otherwise state
  `First RED: blocked/no test environment`.
- Use the minimum GREEN implementation for the slice.
- Output labels:

```text
상태:
변경:
First RED:
Expected RED failure:
Minimum GREEN:
검증:
주요 위험:
다음 행동:
```

### mode=gate

- File mutation: forbidden.
- Produce a short Korean gate review that Stop hook evidence can recognize.
- Output labels:

```text
상태: Pass|Partial|Fail
First RED:
Expected RED failure:
Minimum GREEN:
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
다음 Slice:
Acceptance Criteria:
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
First RED: 있음|없음|blocked/no test environment
Expected RED failure: 확인|미확인|해당 없음
Minimum GREEN: 확인|미확인|해당 없음
검증: <command> = pass|fail|not run (<reason>)
tdd-guide: RED validity = Pass|Partial|Fail|not used
contract-reviewer: Gate decision = Pass|Partial|Fail|not needed
reviewer: Findings = none|listed|not run
주요 위험: 없음|<short risk>
다음 행동: <one short action>
```

## Short Prompt Grammar

- `$v1-slice-harness 다음 slice 골라줘`
- `$v1-slice-harness <slice-id> plan만 세워줘`
- `$v1-slice-harness <slice-id> dry-run 해줘`
- `$v1-slice-harness <slice-id> <target> target으로 dry-run 해줘`
- `$v1-slice-harness <slice-id> 진행해줘`
- `$v1-slice-harness <slice-id> PR까지 만들어줘`
- `$v1-slice-harness 현재 slice 짧은 gate review`
- `$v1-slice-harness hook이 막은 evidence 복구`

## Examples Only

These examples explain prompt usage. They are not a slice registry and do not
define the set of valid slice ids.

- Assume `.codex/harness/v1 next` returned `kakao-map-marker-refresh-flow`.
- `$v1-slice-harness kakao-map-marker-refresh-flow plan만 세워줘`
- For target dry-run, replace `<slice-id>` with the same returned id:
  `$v1-slice-harness <slice-id> frontend target으로 dry-run 해줘`

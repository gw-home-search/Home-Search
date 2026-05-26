---
name: home-search-design
description: Guide Home Search apps/web map-first UI/UX design, Figma-to-code translation, visual QA, marker/filter/detail drawer layout, public API-compatible frontend design decisions, and removal of generic AI-like gradient or card-heavy UI.
---

# Home Search Design Skill

Use this skill for Home Search `apps/web` design planning, Figma-to-code
translation, visual QA, map-first layout decisions, and AI-like UI cleanup.

This skill defines design direction only. It does not replace `frontend-web`
for React implementation, `api-contract` for project compatibility, `tdd` for
behavior tests, or `code-review` for final findings.

## Required Inputs

- Root `AGENTS.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md`.
- `docs/UI_UX_MIGRATION.md`.
- `.agents/skills/frontend-web/SKILL.md`.
- `apps/web/AGENTS.md`.
- `apps/web/CONTEXT.md`.

## Writable Scope

- Design skill work: `.agents/skills/home-search-design/**`.
- Frontend design implementation work: `apps/web/**`, only when the user asks
  for frontend changes and `frontend-web` rules are also followed.

## Design Role

Act as the map workflow owner, not a generic visual decorator.

- Keep the Kakao map as the primary surface.
- Preserve public API URLs, fields, units, and error behavior.
- Design for repeated operational use: compact, readable, predictable.
- Prefer restrained surfaces, borders, and dense data display over decoration.
- Keep detail and trade flows visible without hiding the current map context.

## Required Workflow

1. Read the required inputs before making a design decision.
2. Classify the request: UX brief, Figma translation, visual QA, AI-like UI
   cleanup, mobile adaptation, or implementation handoff.
3. Identify the affected UI unit: app bar, map surface, marker layer, filter
   controls, exploration panel, detail drawer, trade list, or mobile sheet.
4. Map every visible data need to a documented Home Search endpoint and field.
5. Apply the visual doctrine in `references/map-ux-principles.md`.
6. If Figma is involved, follow `references/figma-workflow.md`.
7. Before claiming completion, use `references/visual-qa-checklist.md`.
8. Route implementation, contract, TDD, and review work to the existing Home
   Search skills named above.

## Style Guardrails

Default to a restrained operational map UI.

- Do not create landing-page hero composition.
- Do not use decorative gradient washes, glow effects, glassmorphism, bento
  grids, nested cards, large decorative shadows, or oversized marketing type.
- Do not add feature explanations, sales copy, onboarding banners, or visual
  filler inside the app.
- Use color for role and state only; never rely on color as the only signal.
- Use tables or compact rows for structured trade data.
- Keep markers short enough to scan while panning and zooming.

Limited exceptions are allowed only when the effect supports a concrete map
workflow, such as a low-contrast loading fallback grid, a non-blocking error
state, or subtle depth that separates an active drawer from the map.

## References

- Read `references/map-ux-principles.md` for baseline visual language and UI
  unit rules.
- Read `references/figma-workflow.md` only for Figma-driven work.
- Read `references/visual-qa-checklist.md` before final design review or after
  frontend visual changes.

## Stop Conditions

Stop and use the proper Home Search skill before proceeding if:

- A design requires changing a public API URL, response field, request field, type, or
  unit.
- A design depends on ranking, favorite, alarm, mail, recommendation, auth, or
  heavy analytics flows.
- Figma asks for data not available in the project contract.
- The map becomes secondary to a panel, drawer, hero, or card layout.
- Screenshot evidence is unavailable for a meaningful visual change.

## User-Facing Review Output

Use Korean-first concise review labels:

- `지적사항`
- `검증 근거 확인`
- `검증 공백`
- `잔여 위험`

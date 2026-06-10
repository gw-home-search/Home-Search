# Home Search Design System Audit

Use this reference for visual consistency reviews, AI-like UI cleanup, or a
pre-implementation design audit of `apps/web`.

## Audit Dimensions

Score or comment only where the change is relevant:

- Color consistency: role colors for action, markers, status, and errors are
  reused instead of random values.
- Typography hierarchy: app bar, panel title, row label, marker label, drawer
  heading, table cell, and caption have distinct but compact roles.
- Spacing rhythm: gaps follow a predictable scale and do not default to uniform
  padding everywhere.
- Component consistency: similar controls, rows, badges, drawers, and sheets
  behave and align consistently.
- Responsive behavior: desktop panel, detail drawer, and mobile sheets do not
  overlap map controls or clip labels.
- Accessibility: contrast, focus states, labels, keyboard order, and touch
  targets are sufficient.
- Information density: trade and marker data are dense but not crowded.
- State polish: hover, focus, active, loading, empty, and error states are
  accounted for.

## AI/Template Drift Signals

Flag these as `지적사항` unless there is a documented workflow reason:

- decorative gradient wash
- purple/blue generic accent palette with no product role
- glass or blurred translucent panels
- glow effects around buttons, cards, panels, or markers
- centered hero copy or marketing CTA language
- bento/card grid where rows, table, or map controls are clearer
- same radius, same padding, and same shadow on every surface
- unmodified component-library defaults treated as finished UI
- vague feature explanations inside the app instead of direct controls

## Home Search-Specific Consistency Checks

- The map is still the strongest first-viewport signal.
- Marker labels remain short and stable while panning or zooming.
- Filters stay close to the map and do not become a detached settings page.
- Exploration panel rows are scannable; repeated cards are avoided.
- Detail drawer shows selected complex identity before metrics and trades.
- Trade history uses compact rows or a table, not large decorative cards.
- Mobile sheets leave map panning and zooming reachable.

## Output

Use Korean-first findings:

- `지적사항`: concrete file/unit/problem/fix when reviewing an implementation.
- `검증 근거 확인`: screenshots, commands, or inspected docs.
- `검증 공백`: missing breakpoint, state, or browser evidence.
- `잔여 위험`: unresolved design or contract risk.

# Home Search Map UX Principles

Use this reference when designing Home Search map UI, layout hierarchy,
markers, panels, filters, drawers, and mobile adaptations.

## Product Posture

Home Search is an operational real-estate map tool. The UI should feel quiet,
trustworthy, dense, and repeatable. It should not feel like a SaaS landing
page, portfolio page, or generated marketing mockup.

Before proposing a visual direction, state:

- Purpose: the map workflow or decision the screen must support.
- Audience: the repeating user and the first information they need to scan.
- Tone: operational, calm, trustworthy, dense, or another explicit product
  tone that still keeps the map primary.
- Memorable detail: one small visual or interaction choice that helps the map
  workflow, such as stable marker labels, a clearer active filter state, or a
  drawer transition that preserves spatial context.
- Constraints: public API fields, Kakao map behavior, mobile sheet limits, and
  existing frontend components.

The memorable detail must serve the workflow. Do not introduce a decorative
theme just to make the screen look more designed.

## Visual Hierarchy

Layer the interface by task importance:

1. Map surface: full-screen primary workspace.
2. Map controls and marker labels: immediate spatial actions.
3. Exploration panel and filter controls: compact query tools.
4. Detail drawer and trade list: selected parcel investigation.
5. Non-blocking status and retry messages: short recovery feedback.

Panels must support the map. If a panel hides too much of the map, blocks
markers, or makes panning feel secondary, the design is wrong.

## UI Units

### App Bar

- Keep it thin and utilitarian, around the existing 52-56px range.
- Include only brand, environment/status, and necessary account or utility
  actions.
- Do not use hero typography, slogans, large logos, or marketing copy.

### Map Surface

- The map owns the viewport.
- Loading or runtime fallback states may use a simple functional grid.
- Do not use decorative backgrounds, illustration scenes, or animated visual
  filler behind the map.

### Marker Labels

- Complex labels show the most useful scan data: latest trade amount and unit
  count.
- Region labels show the region name only unless a documented project field is
  needed.
- Keep labels short, high contrast, and stable in size.
- Prefer border and solid fill over glow, blurred shadows, or translucent glass.

### Exploration Panel

- Owns complex search and region navigation.
- Keep rows compact and predictable.
- Use one active selection indicator at a time.
- Do not place repeated cards inside the panel when a row list is enough.

### Filter Controls

- Keep filters compact and close to the map.
- Use short labels and predictable numeric fields for unit, price, area, and
  age filters.
- Avoid large filter cards, marketing-style chips, or hidden filter flows that
  delay marker refresh.

### Detail Drawer

- Opens from a complex marker and keeps the selected map context visible.
- Shows complex identity first, then metrics, then trade history.
- Uses a table or compact rows for trades.
- Does not expose audit identifiers or backend-only fields.

### Mobile

- Exploration panel becomes a bottom sheet.
- Detail drawer becomes a full-height or near-full-height bottom sheet.
- Floating filters become horizontally scrollable or collapsible controls.
- Preserve map panning and zooming as a first-class action.

## Visual Language

- Radius: controls around 6px, panels around 8px.
- Depth: use border first, shadow second, and only for layer separation.
- Typography: compact operational scale; no hero type inside the app.
- Spacing: use dense but breathable gaps. Avoid oversized empty bands.
- Color: neutral base with role accents for action, complex markers, region
  markers, and errors.
- Numbers: use stable alignment for prices, counts, areas, and dates so labels,
  rows, and counters do not jump while data changes.
- States: hover, focus, active, loading, empty, and error states should feel
  designed, but remain quiet enough for repeated map work.

## Anti-Patterns

Avoid these unless the user explicitly re-scopes the product:

- Landing-page hero layout.
- Purple or blue decorative gradient wash.
- Glassmorphism and blur panels.
- Nested cards or card grids where rows or tables are clearer.
- Decorative blobs, orbs, abstract background art, or generated illustrations.
- Glowing buttons, gradient text, animated flourish, or oversized stats cards.
- UI text that explains features instead of supporting the active task.

## Acceptance Signals

A design is acceptable when:

- The map remains the strongest first-viewport signal.
- A user can search, filter, pan, click a marker, and inspect trades without
  losing context.
- The same public API contract works before and after the visual change.
- Error and empty states are visible, short, and non-blocking.
- The UI still works at desktop and mobile widths without overlap or clipped
  text.

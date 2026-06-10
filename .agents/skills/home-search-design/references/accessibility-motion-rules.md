# Home Search Accessibility And Motion Rules

Use this reference when a design touches focus behavior, keyboard navigation,
icon-only controls, live status, modal/drawer behavior, mobile sheets, or
animation.

## Accessibility

- Use native `button`, `input`, `select`, `a`, and table semantics where the UI
  role matches.
- Icon-only controls need an accessible name.
- Form fields and filters need labels that remain understandable when the
  layout compresses.
- Focus indicators must be visible against map, panel, drawer, and sheet
  surfaces.
- Focus order should follow the visible workflow: app bar, exploration panel,
  filters, map controls, selected drawer/sheet.
- Drawer and sheet close controls must be keyboard reachable.
- Status and error messages should use appropriate live-region semantics when
  they update without navigation.
- Do not communicate state with color alone. Add text, icon shape, border,
  position, or explicit selected state.
- Keep pointer targets at least 40x40px where possible; mobile sheet controls
  should aim for 44x44px.

## Motion Purpose

Motion is allowed only when it does at least one of these:

- guides attention to a changed map, filter, marker, drawer, or sheet state
- communicates loading, success, error, selected, expanded, or dismissed state
- preserves spatial continuity between map marker selection and detail view

If an animation does none of these, remove it.

## Motion Constraints

- Respect reduced motion. Prefer opacity-only or instant state changes when
  reduced motion is requested.
- Prefer `transform` and `opacity`; avoid animating layout properties such as
  width, height, margin, padding, top, or left.
- Drawer and sheet transitions should be short and should not block map use.
- Marker animations must not make labels jump while panning or zooming.
- Loading animations must not consume unnecessary CPU/GPU in background tabs.
- Avoid decorative scroll animations, pulsing glows, and repeated attention
  loops.

## Practical Defaults

- Button press feedback: subtle scale or color/border change, disabled under
  reduced motion.
- Drawer/sheet enter: small translate plus opacity, short duration.
- Drawer/sheet exit: shorter and quieter than enter.
- Icon swaps: cross-fade or instant swap; avoid spinning unless it means
  loading.
- Filter applied state: selected border/background change plus text or icon
  state, not color alone.

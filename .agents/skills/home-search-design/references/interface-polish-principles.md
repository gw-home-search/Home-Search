# Home Search Interface Polish Principles

Use this reference when a Home Search UI feels flat, cramped, generic, jumpy,
or unfinished. These rules adapt general interface polish to the map-first
workflow.

## Polish Target

Polish should make the map workflow faster to read and easier to operate. It
should not add a decorative theme, marketing posture, or visual dependency.

Prefer concrete improvements:

- clearer hierarchy for search, filters, markers, and selected complex
- tighter spacing rhythm inside panels and drawers
- stable numeric alignment for prices, unit counts, areas, and dates
- visible hover, focus, active, selected, loading, empty, and error states
- hit areas large enough for repeated use
- quiet depth that separates layers without hiding map context

## Radius And Layering

- Keep controls around 6px radius and panels around 8px unless existing tokens
  say otherwise.
- For nested rounded surfaces, keep the inner radius optically smaller than the
  outer radius.
- Avoid card-inside-card structures. If two surfaces need separation, prefer a
  row, divider, inset border, or compact section heading.
- Use borders before shadows. Use shadows only to separate an active drawer,
  menu, or overlay from the map.

## Typography And Numbers

- Use compact type scales. Hero-size type does not belong inside the app.
- Use `text-wrap: balance` only for short headings and selected-complex titles.
- Use `text-wrap: pretty` for short captions and status messages.
- Use tabular numbers for prices, unit counts, areas, dates, marker labels,
  counters, and trade table columns.
- Keep units visible and stable: amount unit remains the documented 10,000 KRW
  source value unless a display formatter clearly labels the conversion.

## Optical Alignment

- Icon-only controls should look visually centered, not just geometrically
  centered.
- Marker label text, badge text, and numeric pills should stay centered when
  digits change.
- Align table and trade-list values by meaning: dates together, prices
  together, areas together.

## Interaction States

Every interactive control touched by a design pass should define or preserve:

- default
- hover where pointer input exists
- focus-visible
- active/pressed
- disabled or loading when applicable
- selected/expanded when the control toggles state

Do not use `transition: all`. Limit transition properties to the values that
actually change, such as `background-color`, `border-color`, `box-shadow`,
`opacity`, or `transform`.

## Hit Areas

- Prefer at least 40x40px hit areas for pointer controls.
- Use 44x44px where the layout allows, especially mobile sheets and map
  overlays.
- Expanded hit areas must not overlap adjacent map controls or marker labels.

## Loading, Empty, And Error Polish

- Loading states should preserve layout size when possible.
- Empty states should be short and task-local, not onboarding copy.
- Non-blocking errors should keep the map usable and offer a clear retry path.
- Avoid animated shimmer unless it improves perceived state without hurting map
  performance.

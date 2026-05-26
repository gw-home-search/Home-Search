# Home Search Visual QA Checklist

Use this checklist before accepting a Home Search design change or Figma
translation.

## Required Screens

Check screenshots or browser output at:

- Desktop width with exploration panel open.
- Desktop width with detail drawer open.
- Narrow width with bottom-sheet behavior.
- Marker loading, empty, error, and ready states when the change touches map
  fetch behavior.

## Map Priority

Pass only if:

- The map remains visible and dominant.
- Panels do not cover critical marker clusters by default.
- Detail drawer opens without destroying spatial context.
- Zoom controls and marker labels remain reachable.
- Non-blocking errors do not navigate away from the map.

## Readability

Pass only if:

- Marker labels are short and readable at a glance.
- Button and input text fits without clipping.
- Panel rows and trade table columns align predictably.
- Numeric values keep consistent units.
- Text contrast is sufficient against surfaces and map fallback states.

## Accessibility

Pass only if:

- Interactive elements are native buttons, inputs, links, or equivalent
  accessible controls.
- Form fields have usable labels.
- Alert and status messages use appropriate live region semantics.
- Focus order follows the visible task flow.
- Meaning is not communicated by color alone.

## Anti-AI Visual Review

Fail if the design includes:

- Decorative gradient wash.
- Glass or blurred translucent panels.
- Glow effects around controls or markers.
- Nested cards inside panels or drawers.
- Generic bento grid sections.
- Hero-scale typography or marketing slogans.
- Decorative blobs, orbs, abstract background art, or generated filler images.
- Large soft shadows used as decoration rather than layer separation.
- Gradient text or glowing primary buttons.

Allowed exceptions must be functional and documented, such as a simple map
runtime fallback grid or a subtle active-layer shadow.

## Contract Review

Pass only if:

- No public API URL changes.
- No project request or response field changes.
- No unit changes for prices, coordinates, dates, or areas.
- No later-scope feature enters the map path.
- Detail and trade drawers still use `parcelId` from complex markers.

## Completion Evidence

Final design review should report:

- `지적사항`
- `검증 근거 확인`
- `검증 공백`
- `잔여 위험`

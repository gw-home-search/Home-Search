# Home Search Figma Workflow

Use this reference only when a Home Search design task includes Figma input or
asks to translate a Figma frame into `apps/web`.

## Boundary

Figma is design evidence, not the product contract. The V1 API contract and
Home Search map workflow remain authoritative.

If Figma asks for data or behavior outside V1, reduce the UI or mark the gap.
Do not change public API shape to satisfy a visual comp.

## Required Flow

1. Extract the exact Figma node or selected frame.
2. Fetch structured design context for that node.
3. Capture a screenshot for visual comparison.
4. If the node is too large, inspect metadata and fetch only the needed child
   nodes.
5. Identify which Home Search UI unit the design affects.
6. Map visible data to documented V1 endpoint fields.
7. Translate the design into project conventions rather than copying generated
   React or utility classes directly.
8. Validate with browser screenshots after implementation.

## Translation Rules

- Preserve `apps/web` API adapter boundaries.
- Preserve canonical marker fields: `parcelId`, `lat`, `lng`,
  `latestDealAmount`, and `unitCntSum`.
- Keep temporary source variants inside adapters only.
- Use existing map shell, panel, filter, marker, drawer, and table patterns
  before creating new primitives.
- Prefer Home Search's restrained operational style over literal Figma
  decoration.

## Figma Conflict Handling

Stop or revise the design if Figma requires:

- Ranking, favorites, alarms, mail, recommendation, auth, or heavy analytics.
- Backend audit fields in public UI.
- New public response fields.
- Map secondary to hero, content cards, or dashboard tiles.
- Color-only state communication.

## Visual Parity Priority

Aim for useful parity:

1. Task flow and information hierarchy.
2. V1 data compatibility.
3. Responsive behavior.
4. Accessibility and keyboard behavior.
5. Pixel-level spacing and color similarity.

Pixel parity must not override API compatibility, map usability, or
accessibility.

# Home Search Web Design Decisions

This file records stable UI decisions for `apps/web`. Canonical API behavior
still lives in root `docs/API_CONTRACT.md`.

## Product Posture

Home Search is a map-first real-estate trade exploration tool. The public web
surface should feel operational, dense, calm, and repeatable.

Do:

- Keep the Kakao map as the primary viewport surface.
- Keep search, region navigation, filters, marker state, detail, and trades in
  one exploration flow.
- Use compact rows, tables, and stable numeric alignment for repeated scanning.
- Treat loading, empty, error, selected, focus, and disabled states as part of
  the design, not as afterthoughts.

Do not:

- Turn the app into a landing page, hero layout, card grid, or marketing page.
- Add decorative gradients, glass blur, glow effects, abstract background art,
  or oversized typography.
- Introduce ranking, favorites, alarms, mail, recommendation, auth, or heavy
  analytics into the map path.

## Public API Boundary

UI/UX work must not require backend contract changes.

- Map markers stay on `/api/v1/map/complexes` and `/api/v1/map/regions`.
- Search stays on `/api/v1/search/complexes?q=`.
- Region navigation stays on `/api/v1/region` and `/api/v1/region/{regionId}`.
- Detail and trade drawers stay on `/api/v1/detail/{parcelId}` and
  `/api/v1/trade/{parcelId}` with optional `complexId`.
- Amount display must preserve the documented 10,000 KRW source unit unless the
  formatter clearly labels a display conversion.

## Stable UI Units

- `data-ui-surface="map-first"` marks the public map shell.
- `data-ui-layer="filter-controls"` marks the floating filter layer.
- `data-ui-layer="exploration-panel"` marks the search and region panel.
- `data-ui-layer="detail-drawer"` marks the selected complex drawer.
- `data-detail-section="identity"` marks selected complex identity and metrics.
- `data-detail-section="trade-history"` marks the trade history table.

These attributes are intentional test seams. Keep them stable unless the public
map workflow is explicitly redesigned.

## Interaction And Accessibility

- Icon-only or compact controls need accessible names.
- Focus indicators must remain visible on map overlays, panels, drawers, and
  sheet-like mobile surfaces.
- Filter state must not rely on color alone. Use text such as `필터 없음` or
  `필터 N개 적용`.
- Marker, filter, price, count, date, and trade values should use tabular
  numeric alignment.
- Motion is allowed only for state or spatial continuity, and must respect
  reduced motion.

## Verification

Meaningful map UI changes need:

- `npm run test`
- `npm run build`
- browser smoke at desktop and narrow viewport sizes when visual layout changes
  are included

No `lint` command is listed here because `apps/web/package.json` currently does
not define a lint script.

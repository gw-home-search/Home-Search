# UI/UX Migration


## Goal

Redesign the frontend around map exploration while preserving the public API
contract.

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target frontend:

- `/Users/gwongwangjae/home-search/apps/web`

## Current UX

The current source app has:

- A full-height page.
- A top header.
- A fixed left sidebar.
- Filter bar above the map.
- Kakao map as the main content.
- Detail view inside the left sidebar.

Important source files:

- `src/App.jsx`
- `src/components/Header.jsx`
- `src/components/filters/FilterBar.jsx`
- `src/components/sidebar/LeftSidebar.jsx`
- `src/components/sidebar/SearchListSidebar.jsx`
- `src/components/sidebar/region/RegionNavSidebar.jsx`
- `src/components/sidebar/detail/DetailSidebar.jsx`
- `src/components/sidebar/detail/TradeSidebar.jsx`

## Project Target UX

Use a map-first layout:

- Full-screen map as the primary surface.
- Thin app bar for brand, environment, and account actions.
- Collapsible exploration panel for search and region navigation.
- Floating filter controls on top of the map.
- Detail drawer opened from a complex marker.
- Trade chart and list inside the detail drawer.
- Optional authenticated favorite toggle in the detail identity header. Its
  failure state is non-blocking and never hides public detail or trade data.

## Behavior Rules

- API routes do not change for UI/UX work.
- Search remains backed by `/api/v1/search/complexes?q=`.
- Region navigation remains backed by `/api/v1/region` and
  `/api/v1/region/{regionId}`.
- Complex markers remain backed by `/api/v1/map/complexes`.
- Detail drawer uses `/api/v1/detail/{parcelId}` and
  `/api/v1/trade/{parcelId}`. When a marker or search result includes
  `complexId`, the drawer passes `?complexId={complexId}` to keep detail and
  trade data scoped to the selected complex.
- Favorite state uses user-service `/api/v1/favorites/{complexId}` with the
  memory-only access token. Anonymous users can still use every public map and
  detail flow and are prompted to log in only when activating the heart.
- Chatbot submit captures the current map bounds, map level, and a complete
  `complexId`/`parcelId` selection pair. The server treats these values only as
  hints and revalidates the selected complex against property facts.
- New conversations never inherit another conversation's memory. A saved
  conversation may keep only the version 1 complex/region memory patch in its
  browser IndexedDB record; the server does not persist the conversation or UI
  context.
- `BEST_EFFORT`, `PARTIAL`, and `NO_RESULT` are answer states, not retry-only
  errors. The answer renders first, followed only when present by at most three
  applied assumptions and the omitted items. A retry action is reserved for a
  hard transient HTTP error.
- Decision reports render in this order: direct result, representative map
  action, primary table, applied basis, grounded candidate differences, other
  candidates, follow-up, and sources. `uiReport` does not suppress a verified
  `uiSummary.followUp`, and applied criteria are not repeated as warnings.
- Recommendation and comparison data use native tables. Vertical rules and
  nested card surfaces are avoided; only data rows use subtle horizontal rules.
- Candidate detail uses native `details`; rank one starts open and later rows
  start closed. Empty sections are omitted.
- Opening the panel selects the greatest `updatedAt` conversation and reveals
  its final turn. Submitting reveals the new question. A completed answer follows
  only when the reader remains near the bottom; otherwise a `새 답변 보기`
  control appears.
- A recommendation with two or more verified candidates stores only their ranked
  `complexId` values in browser-owned conversation memory v2. Follow-up comparison
  requests reuse and revalidate those ids; a new conversation starts without them.

## Component Direction

Target feature groups:

- `features/map`: Kakao map, marker layers, bounds state.
- `features/search`: search input and results.
- `features/region`: region navigation.
- `features/filters`: unit, price, area, and age controls.
- `features/complex-detail`: detail drawer, trade chart, trade table.
- `shared`: common buttons, panel shell, formatters.

Do not refactor into this structure before the copied app works. First migrate
the source frontend, then redesign.

## Visual Direction

- Keep UI dense and practical.
- Avoid marketing-page composition.
- Prefer compact map controls and panels.
- Make marker labels readable at a glance.
- Avoid hiding current map context when opening details.

## Mobile Direction

Mobile is not the first project target, but the layout should not block it:

- Exploration panel becomes a bottom sheet.
- Detail drawer becomes a full-height bottom sheet.
- Floating filters become horizontally scrollable controls.

## Acceptance Criteria

- Users can start from the map, search a complex, move the map, and open
  detail without losing context.
- The same API contract works before and after UI/UX redesign.
- Filter changes refresh complex markers.
- Detail drawer clearly shows complex info and trade list.

## Additive Map Insights Route

- Public `/insights` renders the same `MapApp`, Kakao map, and filter bar as
  `/`; it is a fourth rail mode rather than an independent page.
- The flat rail navigation contains region plus five visible trade metrics. Metric
  changes reuse the already loaded response for the current scope.
- It supports nationwide/SIDO filtering and separately renders `FRESH`,
  `STALE`, `UNAVAILABLE`, loading, empty, and error states. Map and insight
  failures remain isolated from each other.
- User copy says `최근 계약 · 직전 거래 비교`, shows
  `최근 7일 · M.D–M.D` and `계약 M.D · 등록 M.D`, and never invents a
  registration time from a date. An uncanceled fallback row says
  `등록일 미제공 · 계약일 기준`.
  The criteria disclosure says current contracts are limited to one calendar
  month; rise/fall use the same complex, exact area, and a previous contract
  within six calendar months. Record-high requires that recent previous
  contract but displays its separate all-time historical maximum baseline.
  Comparison rows show the applicable baseline date. Registration-date
  fallback counts are not shown as a prominent quality message. The copy does
  not expose `DAILY`, snapshot, work-unit, or collection internals.
- Scope/region responses are cached with `fetchedAt`. Re-entering insights or
  returning to a visible browser tab refreshes only when the cache is at least
  five minutes old or the KST date changed. There is no polling; stale request
  responses cannot overwrite the newly selected scope.
- Trade rows open the existing map detail drawer. Closing detail restores the
  `/insights` metric, scope, list scroll, and prior row focus.
- `/insights` without a scope is replaced with Seoul SIDO scope before the first
  request. The inline region chooser reuses the existing 3-column SIDO tile
  anatomy and keeps nationwide available as an explicit scope.
- On mobile, direct `/insights` access opens a 58dvh bottom sheet while keeping
  at least 180px of usable map area below the filter bar.
- `/my/insights` combines authenticated inbox and opt-in delivery settings.
- External news strings are rendered as text. The web app never renders NAVER
  title/description HTML.

## News Rail And Complex Detail

- `/insights/news` keeps `MapApp`, the active map viewport, search, and region
  state. Only the rail/mobile sheet content changes.
- News is separated from the five trade metrics in `MapModeNavigation`.
  Scope, root SIDO, and category are URL state; pagination cursor is not.
- `VITE_MARKET_NEWS_ENABLED=false` is the rollback path: it removes the news
  navigation and detail tab and redirects direct news-hub entry to the map.
- Seven text category tabs support arrow, Home, and End keys. Rows are a
  divider list with category, two-line visual title, region, provider date, and
  an external-link cue. The full title remains the accessible link name.
- Links use `target="_blank"` and `rel="noopener noreferrer"`. No external
  HTML, description, thumbnail, AI summary, inferred publisher, or internal
  exclusion count is rendered.
- Detail adds a fourth mobile tab, `뉴스`, and a desktop `관련 뉴스` section
  after basic information. Its independent request may fail without blocking
  information, prices, trades, or charts.

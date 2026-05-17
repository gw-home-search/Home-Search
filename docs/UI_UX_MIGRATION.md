# UI/UX Migration

## Goal

Redesign the frontend around map exploration while preserving the V1 API
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

## V1 Target UX

Use a map-first layout:

- Full-screen map as the primary surface.
- Thin app bar for brand, environment, and account actions.
- Collapsible exploration panel for search and region navigation.
- Floating filter controls on top of the map.
- Detail drawer opened from a complex marker.
- Trade chart and list inside the detail drawer.

## Behavior Rules

- API routes do not change for UI/UX work.
- Search remains backed by `/api/v1/search/complexes?q=`.
- Region navigation remains backed by `/api/v1/region` and
  `/api/v1/region/{regionId}`.
- Complex markers remain backed by `/api/v1/map/complexes`.
- Detail drawer uses `/api/v1/detail/{parcelId}` and
  `/api/v1/trade/{parcelId}`.

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

Mobile is not the first V1 target, but the layout should not block it:

- Exploration panel becomes a bottom sheet.
- Detail drawer becomes a full-height bottom sheet.
- Floating filters become horizontally scrollable controls.

## Acceptance Criteria

- Users can start from the map, search a complex, move the map, and open
  detail without losing context.
- The same API contract works before and after UI/UX redesign.
- Filter changes refresh complex markers.
- Detail drawer clearly shows complex info and trade list.

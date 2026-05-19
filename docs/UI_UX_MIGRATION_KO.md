# UI/UX Migration

## Goal

V1 API contract를 보존하면서 frontend를 map exploration 중심으로 redesign한다.

Source frontend:

- `/Users/gwongwangjae/frontend/home-client`

Target frontend:

- `/Users/gwongwangjae/home-search/apps/web`

## Current UX

현재 source app은 다음을 가진다:

- full-height page.
- top header.
- fixed left sidebar.
- map 위 filter bar.
- main content로 Kakao map.
- left sidebar 안의 detail view.

중요한 source files:

- `src/App.jsx`
- `src/components/Header.jsx`
- `src/components/filters/FilterBar.jsx`
- `src/components/sidebar/LeftSidebar.jsx`
- `src/components/sidebar/SearchListSidebar.jsx`
- `src/components/sidebar/region/RegionNavSidebar.jsx`
- `src/components/sidebar/detail/DetailSidebar.jsx`
- `src/components/sidebar/detail/TradeSidebar.jsx`

## V1 Target UX

map-first layout을 사용한다:

- primary surface로 full-screen map.
- brand, environment, account actions를 위한 thin app bar.
- search와 region navigation을 위한 collapsible exploration panel.
- map 위 floating filter controls.
- complex marker에서 열리는 detail drawer.
- detail drawer 안의 trade chart와 list.

## Behavior Rules

- UI/UX work에서 API routes는 바뀌지 않는다.
- Search는 `/api/v1/search/complexes?q=` 기반으로 유지된다.
- Region navigation은 `/api/v1/region` 및 `/api/v1/region/{regionId}` 기반으로 유지된다.
- Complex markers는 `/api/v1/map/complexes` 기반으로 유지된다.
- Detail drawer는 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를 사용한다.

## Component Direction

Target feature groups:

- `features/map`: Kakao map, marker layers, bounds state.
- `features/search`: search input and results.
- `features/region`: region navigation.
- `features/filters`: unit, price, area, age controls.
- `features/complex-detail`: detail drawer, trade chart, trade table.
- `shared`: common buttons, panel shell, formatters.

copied app이 동작하기 전에는 이 structure로 refactor하지 않는다. 먼저 source frontend를 migrate하고 그 뒤 redesign한다.

## Visual Direction

- UI를 dense하고 practical하게 유지한다.
- marketing-page composition을 피한다.
- compact map controls와 panels를 선호한다.
- marker labels를 한눈에 읽기 쉽게 만든다.
- details를 열 때 current map context를 숨기지 않는다.

## Mobile Direction

Mobile은 첫 V1 target은 아니지만 layout이 막으면 안 된다:

- Exploration panel은 bottom sheet가 된다.
- Detail drawer는 full-height bottom sheet가 된다.
- Floating filters는 horizontally scrollable controls가 된다.

## Acceptance Criteria

- Users가 map에서 시작해 complex를 search하고, map을 움직이고, context를 잃지 않고 detail을 열 수 있다.
- UI/UX redesign 전후로 같은 API contract가 동작한다.
- Filter changes가 complex markers를 refresh한다.
- Detail drawer가 complex info와 trade list를 명확히 보여준다.

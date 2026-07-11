# Home Search Web Design Decisions

This file records stable UI decisions for `apps/web`. Canonical API behavior
still lives in root `docs/API_CONTRACT.md`.

## Product And API Boundary

Home Search is a calm, map-first real-estate trade exploration tool. Keep the
map, search, region, filters, detail, and trades in one dense operational flow.
Do not add gradients, glass, glow, marketing composition, ranking, favorites,
alarms, auth, or other later-scope features. Header and filter layers use flat
semantic surfaces separated by subtle borders.

UI changes preserve the existing map, search, region, detail, trend, and trade
URLs, fields, types, coordinates, and amount units. API hooks and adapters own
contract behavior; visual components do not reinterpret it.

## KOSA Team5 Design Baseline

The read-only KOSA team5 frontend at `/Users/gwongwangjae/kosa-team5/web` and
its supplied captures define the current visual and information-density
baseline.

| KOSA team5 source | Adopted in Home Search | Intentional adaptation |
| --- | --- | --- |
| `src/app/App.tsx`, `styles/app-shell.css` | 38px CSS house/search mark and wordmark/subtitle anatomy | use a 56px flat white header and 380px rail so the map keeps priority; omit KOSA operational badges and all account/auth actions; compact to 58px on mobile |
| `components/SearchPanel.tsx`, `styles/exploration.css` | 42px outlined search, visible Sky search action, 58px result cards, 3-column region grid | compact region tiles to 56px while retaining debounce, Enter submit, target loading/error states, accessible buttons, and docked mobile sheet |
| `components/MarkerFilterPanel.tsx`, `styles/map.css` | 62px map-top filter band, 92×46 two-line chips, reset pill, 320px dropdown, dual range slider | retain nullable drafts, explicit Apply/Enter, validation, dynamic ceiling, and target request fields |
| `features/complex-detail/DetailSidebar.tsx`, `styles/detail-sidebar.css` | 72px cyan identity band, 3-column API status and key-stat rows, 54px same-parcel cards, 94px labels | retain prediction, pagination, target mobile tabs, and current detail/trade API ownership |
| `styles/map.css`, `components/MarkerPreviewList.tsx` | white complex price card with 4px Sky rail and 6px radius; white/Sky split region card; `yAnchor=1` | Kakao and fallback share one view-model; retain selected `aria-pressed`, canonical amount unit, and null household semantics |
| `presentation-deck/src/imports/home-search-right-example.png`, `image-3.png`, `image-4.png` | visual density, header/rail/filter/detail/marker composition | exclude KOSA chatbot, AI 집찾기 launcher/panel, recommendation and RAG flows |

The source repository remains read-only. Geometry is re-expressed through local
React/CSS components; no Tailwind, `lucide-react`, Redux, or
`react-kakao-maps-sdk` dependency is imported.

## Stable Test Seams

- `data-ui-surface="map-first"`: public map shell.
- `data-layout-region="map-column"`: fixed filter and map column.
- `data-ui-layer="filter-controls"`: map-column filter row.
- `data-ui-layer="exploration-panel"`: search/region rail or docked sheet.
- `data-ui-layer="detail-sidebar"`: selected complex rail or docked sheet.
- `data-exploration-open` and `data-sidebar-mode`: responsive workspace state.
- `data-detail-order`: desktop detail sequence.
- `data-marker-shape`: shared renderer shape (`price-card` or `split-card`).

## Fixed Shell

The shell has a full-width white header and a workspace below it. The workspace
contains one fixed-width sidebar and one map column; the map column contains a
filter row above the actual Kakao map.

| Viewport | Header | Sidebar | Filter |
| --- | ---: | ---: | ---: |
| `>=1101px` | 56px | 380px | 62px |
| `721–1100px` | 56px | 352px | 62px |
| `<=720px` | 58px | docked/closed | 62px |

Exploration and detail always use the same desktop width. At `<=720px`, a
closed sheet leaves the workspace to the map column. Search/region uses
`minmax(242px, 1fr) / min(46dvh, calc(100dvh - 300px))`; detail uses
`minmax(242px, 1fr) / min(68dvh, calc(100dvh - 300px))`. The 242px first-row
minimum includes the 62px filter and a 180px actual map.

At `<=900px`, `<=500px`, landscape, an open sheet becomes a 320px left rail and
the map column owns the remaining width. At `<=360px`, subtitle is hidden,
chips stay 84px, icon targets stay 44px, popovers keep 8px insets, and only the
detail trade table may scroll horizontally. Short portrait screens keep the
180px map minimum and reduce the chart to 150px.

The sheet never overlays Kakao attribution. Grid rows/columns are not animated.
`ResizeObserver` preserves center around `map.relayout()`.

## Header And Icons

Use a flat white header with a 1px neutral bottom border, no shadow,
20/18/12px horizontal padding, and no right-side account/status actions. The
38px (34px mobile) Brand Soft tile draws the house/search mark in CSS. Wordmark
is `홈서치`; subtitle is `HomeSearch · 실거래가 인사이트`.

Public map icons live under `src/shared/icons`, use `currentColor`, 1.75px round
strokes, and no icon dependency or raster asset. Icon-only buttons require an
accessible name and 40px desktop or 44px mobile target where the layout allows.

## Exploration

Search uses a 68px block and a 42px, 16px-radius field with a leading search
icon and visible Sky search action. Debounce and Enter submit remain. Search
results and suggestions use one-column 58px cards with 14px radius,
border, restrained hover, and a leading selected signal.

Exploration typography is token-controlled rather than DOM-order-controlled.
Panel headings use 15/20px, result names use 13/18px with one-line ellipsis and
`word-break: keep-all`, and addresses/metadata use 11/16px. Result markup uses
explicit `panel-list-title` and `panel-list-meta` classes. The breadcrumb root
owns a centered 32px control and each trail label owns its own ellipsis box.

Region navigation uses a quiet single-line breadcrumb on the white panel. Its
stage actions are unframed text, the current stage is distinguished by weight,
and each transition uses a plain 14px neutral chevron separator. Region choices are a three-column grid of
56px white tiles with 12px radius and Sky hover/selection states. Tile labels stay
optically centered and use an emergency two-line wrap for long, unspaced names
such as `세종특별자치시`; the selected Check icon is absolutely positioned
so it does not shift the label. Selected tiles expose `aria-pressed=true`, a
Check icon, and a Sky border and fill. The complex-list step uses the KOSA
82px card anatomy: 16px complex name, address, and optional household/building/
approval-year metadata pills.

The breadcrumb is the only region-stage navigation label. Do not render a
separate `시군구 선택` or `읍면동 선택` summary row. `시도 선택` and every
selected ancestor are 32px breadcrumb buttons; selecting an ancestor reloads
that region, truncates deeper trail items, and returns to its child grid.

Region complex rows are leaf-only. A selected region with non-empty `children`
shows only the next region grid and must not request or render
`/api/v1/region/{regionId}/complexes`. The complex request starts only when the
selected region has no children; changing stages clears stale complex rows
before the next response.

## Filters

Each chip is two-line label/summary: 92×46px desktop, 88×46px tablet, and
88×46px mobile. Inactive chips show only the group label; applied chips add the
range summary. Active and open states use border, background, and expanded
semantics as well as color. Group labels use 14/18px. Reset follows the four
filter chips inside the same horizontal scroller; it is a desktop pill and
mobile icon target. Do not render a separate applied-filter count label.
Filter controls do not add decorative hover color shifts.

The filter bar uses Surface Muted with a 1px neutral bottom border. It has no
gradient, colored underline, or shadow; Sky remains limited to applied/open
chip state and focus feedback.

Each 320px popover has two range inputs plus min/max number fields. Number draft
state is authoritative. Its 15/20px range title sits inside the content box
rather than cutting through the fieldset border. Blank bounds stay `null`;
untouched slider thumbs only
display floor/default ceiling. Default ceilings are 5000 units, 120 pyeong,
80 eok, and 40 years, with steps 1/1/0.1/1. A draft above the default expands
the slider ceiling. Only Apply or Enter commits. Escape/outside click discards;
negative, non-finite, and reversed ranges do not commit. Group reset writes its
two request fields as `null`; global reset clears all groups.

Range layout uses `minmax(0, 1fr) / 40px / minmax(0, 1fr)`. Fieldset, labels,
and number controls all set `min-width: 0`; native number spinners are hidden,
the unit owns a fixed non-wrapping slot, and both popover actions are equal-width
nowrap buttons. This contract prevents `최소 / 평 / – / 최대 / 평` and
`이 필터 초기화` from fragmenting at narrow widths.

Range adjustment is visually neutral: track, selected segment, thumb, and
focus treatment use Line/Line Strong grays. Do not show the app-wide Sky focus
outline across the transparent range input; retain keyboard visibility with a
subtle gray thumb ring and number-field border.

## Detail

Desktop order is fixed: identity header, API status, key-stat/prediction
summary, same-parcel switcher, basic information, trend, trade table. The 72px
Sky Soft header shows address and complex name. Hide same-parcel switching for
zero or one complex. Basic information is a label/value list with 94px labels
and `-` for missing values.

Price and prediction use separate semantic roles: Ink for values and Red plus
a label/icon for failure. The trend uses Sky. Trades use a divider table
with right-aligned amount/area/floor and a full-width 40px more button.

Mobile keeps a handle, sticky 48px navigation, sticky 44px tabs, and common
identity. Information shows switcher/basic data; price shows overview/chart;
trades shows the table. Back restores the previous exploration state. X closes
detail and sheet and restores focus to the mobile search action.

## Markers

Complex markers are white 96–148px price cards with a 3px medium-teal leading
rail, 10px kicker, 15px Ink price, and compact complex name. The default state
keeps deep action color out of the main text; selected state alone adds the
deep-teal outline/ring and `aria-pressed=true`. Dense idle markers hide the
`recent trade` kicker and show only price plus complex name; the selected
marker restores the full three-line anatomy.

Region markers are 130px Soft Split Cards: a 34px white name row and a 30px
soft-teal unit row with deep-teal text. Hover strengthens only the neutral
border, rather than filling the marker with a darker color. Shape, not only
color, distinguishes marker
types. Kakao `CustomOverlay` and fallback renderers share the same view-model,
key, accessible label, shape, values, and selected state.

## Color, Layer, Scroll, And State

Raw public colors live only in `design-system.css` `--hs-map-*` declarations.
Deep teal Sky is brand/action, exploration region controls, and map-marker
emphasis; the brighter teal is reserved for focus and lightweight borders.
Blue-gray neutrals separate surfaces without competing with the Kakao map.
Ink is price text, and Red is failure only. Use
6/10/12/14/16/999px semantic radii and KOSA's restrained
appbar/card/marker/dropdown shadows.

Layers are host 0, markers 10, notices 20, controls 30, rail/sheet 40, header 50,
popover 60. Notice and zoom stay at map top corners; the map bottom remains
clear. Body/workspace are hidden. Search/region and detail each own one vertical
scroll; chips own horizontal scroll; only constrained popovers scroll.

Loading is delayed to avoid flash, empty/error copy is task-local, and marker
errors keep the map usable. Focus, selected, open, disabled, and error states do
not rely on color alone. Reduced motion disables nonessential transitions.

## Verification

Run `npm run test` and `npm run build`. For meaningful visual changes, inspect
1536×1024, 1280×800, 1024×768, 390×844, and 844×390 states. If browser evidence
is unavailable, report visual QA as `Partial` and do not claim visual completion.

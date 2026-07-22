# Home Search Web Design Decisions

This file records stable UI decisions for `apps/web`. Canonical API behavior
still lives in root `docs/API_CONTRACT.md`.

## Product And API Boundary

Home Search is a calm, map-first real-estate trade exploration tool. Keep the
map, search, region, filters, detail, and trades in one dense operational flow.
Do not add gradients, glass, glow, marketing composition, ranking, alarms, or
other later-scope features. Optional OAuth login and the detail-drawer favorite
toggle are the current account surfaces. Header and filter layers use flat
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
| `src/app/App.tsx`, `styles/app-shell.css` | 38px CSS house/search mark and wordmark/subtitle anatomy | use a 56px flat white header and 380px rail so the map keeps priority; omit KOSA operational badges and reserve the right side for the optional login/account control; compact to 58px on mobile |
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
- `data-marker-density`: zoom-level marker scale (`dense`, `compact`, `standard`, or `overview`).
- `data-map-level`: current clamped Kakao map level used for visual QA.
- `data-map-display-mode`: active Kakao base/terrain view (`roadmap`, `terrain`, or `hybrid`).
- `data-ui-layer="map-control-rail"`: shared zoom, map-display, and map-tool rail.
- `data-map-tool`: active interactive map tool (`none`, `roadview`, or `distance`).
- `data-cadastral-visible`: independent cadastral overlay state.
- `data-roadview-state` and `data-distance-phase`: public UI seams for tool progress and completion.
- `data-auth-status`: header auth state without exposing token or email data.
- `data-ui-component="auth-dialog"` and `data-auth-provider`: login dialog and
  allowlisted provider actions.

## Fixed Shell

The shell has a full-width white header and a workspace below it. The workspace
contains one fixed-width sidebar and one map column; the map column contains a
filter row above the actual Kakao map.

| Viewport | Header | Sidebar | Filter |
| --- | ---: | ---: | ---: |
| `>=1440px` | 56px | 380px | 62px |
| `1101–1439px` | 56px | 360px | 62px |
| `721–1100px` | 56px | 336px | 62px |
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

The docked sheet and its nested rail controls use a consistent restrained 1px
corner. The sheet never overlays Kakao attribution. Grid rows/columns are not animated.
`ResizeObserver` preserves center around `map.relayout()`.

## Header And Icons

Use a flat white header with a 1px neutral bottom border, no shadow, and
20/18/12px horizontal padding. The right side contains only the optional OAuth
login/account control; notification mocks and email/user ids are not shown. The
38px (34px mobile) Brand Soft tile uses the selected H1 house/search image and
the same image supplies browser and Apple touch icons. Wordmark is `홈서치`;
subtitle is `HomeSearch · 실거래가 인사이트`.

Public map icons live under `src/shared/icons`, use `currentColor`, 1.75px round
strokes, and no icon dependency or raster asset. Icon-only buttons require an
accessible name and 40px desktop or 44px mobile target where the layout allows.

The auth dialog is a 440px native `dialog` on desktop and an `<=720px`
bottom sheet. Its header login action pairs a restrained account icon with the
label without changing the app-bar height. Provider buttons have equal geometry
and preserve each official identity: Kakao yellow with the black talk symbol,
Naver `#03A94D` with the white N, and Google white with a neutral border and the
standard color G. Kakao provider corners remain 12px per its login design guide;
the same geometry keeps all three providers equally prominent. The dialog
centers each provider icon and label in an 8px-gap flex row so stale plain-text
markup during hot reload still remains centered and cannot wrap vertically. It
explains automatic signup and keeps access JWTs out of UI state and Web Storage.
Header height and map viewport remain fixed across auth states.
The authenticated account trigger is an unframed 44px header action: avatar,
single-line display name, and a down chevron only. Provider identity stays in
the opened account menu. Hover uses Surface Muted; expanded state uses Brand
Soft plus chevron rotation, avoiding a permanent card-like box in the app bar.
The trigger is content-sized and right-aligned so the name and chevron remain
a compact group instead of being separated by a flexible middle column. At
`<=720px`, only the avatar remains visible so brand, chatbot Beta action, and
account access fit without compressing the app bar.

## Chatbot

The authenticated chatbot entry is a compact, borderless app-header action
beside the account control. It uses three compact rounded data bars, rendered as
a local SVG so the mark stays crisp and aligns with the 20px `AI` label baseline.
The compact app-bar action pairs the mark
with the visible `AI` label, and the same mark appears beside the open drawer
title without adding a surrounding tile or badge. `Beta` stays out of the app bar and appears only beside the
`홈서치 AI` title after the drawer opens. The open state stays borderless and
does not add a persistent box around the app-bar action. Opening it creates a flat right drawer
below the app header, not a floating launcher or a card-like modal. On desktop
and short landscape screens, the drawer stays between 420px and 460px using
`clamp(420px, 30vw, 460px)` and consumes that width from the map workspace
instead of covering it. Between 721px and 1279px, opening chat closes the
exploration rail so the side-by-side map keeps a usable width. At `<=900px`
with enough vertical room, chat becomes a bottom split:
the conversation uses up to `62dvh` while the filter and at least 180px of map
remain visible above it. Kakao map relayout preserves its center
and refreshes viewport-bound data after either split changes size.

Conversation history stays in browser IndexedDB, but a blank conversation is a
memory-only draft. Opening the drawer or repeatedly selecting `새 대화` does
not create stored records; the first submitted question creates the record and
its title. A legacy record with no messages is removed on the next load, while
every conversation containing a message is retained. The main drawer never
shows a persistent conversation sidebar or a second brand block. The flat
toolbar uses an asymmetric Home Search composition: the AI data mark, title,
and small `Beta` stay grouped on the left, while a labeled new-conversation
action, history, and close controls stay on the right. History opens a 320px
`내 대화` popover grouped by `오늘`, `어제`, `최근 7일`, and `이전`.
Selection is a quiet muted row; deletion lives in each row's `⋯` menu and uses
a recoverability warning. Import, export, and delete-all live in the popover
header menu rather than in a permanently visible tool footer. The empty
conversation uses a left-anchored 440px
content column instead of centering the narrower content block. Its greeting is
15px, the task heading is 23px, and guidance is 15/24px. Mobile keeps the same
left edge while stepping the task heading to 21px and guidance to 14/22px. Three
separate soft recommendation cards share that left edge. The section
heading is 15px, each card uses a 14px radius, and the category and 14px question
copy keep a lighter hierarchy than the previous table-like divider rows. Each card names one
supported capability (`최근 실거래`, `가격 흐름`, or `단지 정보`) and uses a
different apartment complex so the available question range is visible before
selection. Example copy asks for specific output such as transaction date,
floor, monthly volume, or identity details instead of using short generic
prompts.
On desktop, the examples consume the empty conversation column's remaining
space and settle above the composer, with a 34px minimum separation from the
introduction when height is constrained. Mobile uses a fixed 32px separation
so the examples remain reachable inside the bottom split.
The app-bar AI action and drawer toolbar actions keep transparent surfaces in
hover, pressed, and expanded states; only icon/text color changes. Keyboard
`focus-visible` outlines remain, so removing the gray press box does not remove
the accessible focus signal.
User questions are right-aligned muted bubbles without a border, shadow,
avatar, or visible speaker name. Assistant answers occupy the full left column
as ordinary content without a bubble, avatar, or repeated `홈서치 AI` label.
Whitespace separates turns; horizontal dividers do not. Structured answers
keep headings, tables, and interpretation order, but scope notices, fact lists,
limitations, and follow-ups use flat text and data rows rather than nested
tinted cards. Recommendation results alone keep a neutral 1px outlined surface
to distinguish separate complexes.

The composer starts as a 56px white pill and grows with the question up to four
24px lines. A single-line textarea is optically centered against the 40px send
action; as the question grows, only the circular send action stays aligned to
the lower edge. Longer questions scroll inside the textarea so the send action
and map context stay visible. Enter submits once, Shift+Enter inserts a newline,
and IME composition Enter never submits. The action uses a heavier upward
arrow, a brand focus ring, and 16px input text. While a response is pending,
the thread shows the flat `데이터를 확인하고 있어요` status and the send
action exposes the accessible name `답변 생성 중`. The
small italic `Beta` wordmark uses Inter/SF Pro Display without a badge box or
all-caps treatment. Evidence metadata (`requestId`, data date, counts, and
grade) remains stored for validation and archive compatibility but is not
rendered as a separate evidence panel. With no citations, no source UI is
rendered. With actual citations, unique source names appear at the end of the
answer; four or more sources use a compact `출처 전체 보기` menu. No chatbot
UI decision changes the JSON/SSE contract or adds server-side conversation
persistence.

## Exploration

Search starts the exploration rail without a redundant visible section title.
It uses the same 62px baseline as the filter bar and a 42px field with an
explicit 4px radius on both input and submit button
with a leading search icon. The input is neutral until focus and the visible
Sky submit button owns the primary action emphasis. Debounce and Enter submit
remain. Search results, suggestions, and region complexes share the reusable
`ComplexList` divider-row anatomy. Rows avoid individual cards, repeated
shadows, and a left accent bar; optional approval year and scale values occupy
the same secondary positions used by region complex rows.

Exploration typography is token-controlled rather than DOM-order-controlled.
Panel headings and result names use 15/20px with one-line ellipsis, and
addresses/metadata use 11/16px. Result markup uses explicit
`complex-list-name` and `complex-list-address` classes. The breadcrumb root
owns a centered 32px control and each trail label owns its own ellipsis box.

Region navigation uses a quiet single-line breadcrumb as the only visible
region heading on the white panel. Its stage actions are unframed text, the
current stage uses 14px Sky text and a 2px underline while ancestors step back
to smaller muted text. Each transition uses a quiet 12px right chevron
separator. Breadcrumb items never compress their labels;
the row scrolls horizontally and brings the current stage into view. Region
choices are a three-column grid of 52px
white tiles with restrained, visibly rounded 4px corners and Sky hover/selection states. Tile labels stay
optically centered and use an emergency two-line wrap for long, unspaced names
such as `세종특별자치시`; the selected Check icon is absolutely positioned so
it does not shift the label. Selected tiles expose `aria-pressed=true`, a Check
icon, and a Sky border and fill. The complex-list step uses compact 76px
comparison rows: a 15px complex name and address/approval context on the left,
with household/building counts aligned on the right.

The breadcrumb is the only region-stage navigation label. Do not render a
separate `시군구 선택` or `읍면동 선택` summary row. `시도 선택` and every
selected ancestor are 32px breadcrumb buttons; selecting an ancestor reloads
that region, truncates deeper trail items, and returns to its child grid. A map
marker extends the trail only when its id is present in the currently loaded
child grid; otherwise it starts a new trail so unrelated regions are never
presented as an administrative hierarchy.

Region complex rows are leaf-only. Their list uses 76px comparison rows without
an outer rounded card: complex name plus address/approval year stay on the left,
while unit and building counts use a fixed right-aligned numeric column. The
section count is quiet text rather than an outlined pill. A selected region with non-empty `children`
shows only the next region grid and must not request or render
`/api/v1/region/{regionId}/complexes`. The complex request starts only when the
selected region has no children; changing stages clears stale complex rows
before the next response.

## Filters

Each chip is two-line label/summary: 92×46px desktop, 88×46px tablet, and
88×46px mobile. Inactive chips use a neutral white surface and show only the
group label; applied chips add the range summary on Brand Soft. Active and open
states use border, background, and expanded semantics as well as color. Group
labels use 14/18px while idle and active. Each complete filter button uses a
visibly rounded 4px radius; opened popovers, number fields, and action buttons
keep the compact rail corner system. Reset follows the four filter chips inside the same
horizontal scroller; it is a quiet desktop action and mobile icon target. Do not render
a separate applied-filter count label.
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

Desktop order is fixed: identity header, optional favorite error row, API status, key-stat/prediction
summary, same-parcel switcher, basic information, trend, trade table. The 76px
Sky Soft header shows address and complex name. Its centered identity sits
between symmetric 40px control columns; the back action is an unframed arrow
inside a full hit target. Hide same-parcel switching for
zero or one complex. Basic information is a label/value list with 94px labels
and `-` for missing values.

Price and prediction use separate semantic roles: Ink for values and Red plus
a label/icon for failure. The prediction heading uses a fixed 32px alignment
row; its transparent help target centers the question icon without adding a
second visible container. The prediction card keeps an explicit 4px corner
radius independent of shared rail controls. The trend uses Sky. Trades use a divider table
with 56px rows and right-aligned amount/area/floor columns. Amounts keep `억` and
`만원` together on one non-wrapping line; numeric apartment buildings and floors
split into explicit `동` and `층` lines. Square meters and pyeong stay as
primary/secondary area lines. The more button remains full-width and 40px tall.

The identity header reserves symmetric action geometry. Desktop uses
`40px minmax(0, 1fr) 40px` for back, identity, and favorite. Mobile uses
`44px minmax(0, 1fr) 88px` for back, identity, and separate 44px favorite/close
targets. The heart uses outline/filled shape plus `aria-pressed`, not color
alone. Both states use the Favorite Red semantic color and a transparent hit
area without a white circular surface. Favorite failures render a compact non-blocking row below the
identity header.

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

Region markers preserve the same Soft Split Card anatomy at every zoom: a white
name row above a soft-teal household-count row with deep-teal text. Level 10 uses
the 130×64px standard card, levels 8–9 use 112×54px compact cards, levels 5–7
use 98×48px dense cards, and levels 11–12 use 92×46px overview cards. The map
caps zoom-out at level 12. Hover strengthens only the neutral border, rather
than filling the marker with a darker color. Shape, not only color,
distinguishes marker types. Kakao `CustomOverlay` and fallback renderers share
the same view-model, key, accessible label, shape, values, and selected state.

## Color, Layer, Scroll, And State

Raw public colors live only in `design-system.css` `--hs-map-*` and
`--hs-auth-*` declarations.
Deep teal Sky is brand/action, exploration region controls, and map-marker
emphasis; the brighter teal is reserved for focus and lightweight borders.
Blue-gray neutrals separate borders without competing with the Kakao map.
Ink is price text, and Red is reserved for failures and the explicit favorite
heart state. App bars, panels, markers, and controls keep a clean white default
surface. A near-white teal tint is limited to repeated functional groups: the
search band, filter band, chatbot recommendations, and composer band. It is not
used as the page or panel base. Use
6/10/12/14/16/999px semantic radii and KOSA's restrained
appbar/card/marker/dropdown shadows.

Layers are host 0, markers 10, notices 20, controls 30, rail/sheet 40, popover 60,
and app header/chat drawer 61. Notice and zoom stay at map top corners; the map-type toggle shares
the right-side tool rail below zoom without covering Kakao attribution. Body/workspace are hidden. Search/region and detail each own one vertical
scroll; chips own horizontal scroll; only constrained popovers scroll.

The right-side map-type toggle sits below the zoom stack and shows only the current
mode while closed. Activating it expands a compact segmented menu to the left:
`지도` maps to Kakao `ROADMAP`, `지형` to `ROADMAP` plus the `TERRAIN` overlay,
and `위성` to labeled `HYBRID`. Selection closes the menu, which uses
`aria-expanded` and `aria-pressed`, and does not persist across a full page refresh.
The zoom actions remain a separate top-right vertical stack of individual rounded
buttons. A second tool toggle follows the same left-opening menu pattern for
`거리뷰`, `지적`, and `거리`; only one menu can be open. `시설` is a separate,
always-visible category-settings button. It is not a working mode and selected
facilities remain visible while distance, cadastral, or display-mode controls are
used. Roadview temporarily clears facility requests and overlays while preserving
the category selection for restoration. The control is enabled as soon as the
Kakao runtime is ready and does not require a selected complex.

Roadview replaces the map stage at every viewport instead of opening a fixed split.
Its 44px header provides back, title/status, and close actions while the Roadview fills
the remaining map area, keeping Kakao Roadview controls unobstructed. Distance
measurement uses a compact bottom-center action bar with explicit undo, reset, complete,
and exit actions. Cadastral mode adds a map-local reference disclaimer. All tool states
reset on full-page refresh and must not change Home Search public API requests.

Facilities opens an eight-item picker to the left of its rail button in the fixed
order `대형마트`, `편의점`, `음식점`, `어린이집·유치원`, `학교`, `학원`, `지하철역`, `병원`.
Zero to three categories may be selected; a visible Check plus `aria-pressed`
identifies selection, and an accessible status message explains the maximum.
Closing the picker does not clear POIs. Desktop uses a 336px 4-by-2 grid; narrow
screens use a 2-by-4 grid with 44px targets. Level 5 or wider never calls the
viewport API and displays a zoom prompt. Each
category contributes at most five symbols and the combined layer is capped at
fifteen, with duplicate Kakao place ids rendered once.

POIs use a 40px hit area containing a 30px white symbol tile with 4px radius and
teal outline. Icons share one `currentColor`, 1.75px rounded-stroke descriptor
between React controls and imperative Kakao overlays. There are no pin tails,
emoji, or letter markers. Selection expands to an icon plus a place-name label
and changes background, border, and size together. A compact bottom-center info
bar shows only the selected place name, category, address, optional phone, and a
validated Kakao link. Routine loading, empty, and zoom states stay visually quiet;
only partial/error recovery uses the same compact footprint with retry.
Ordinary apartment markers remain visible and interactive while the selected
complex keeps its normal emphasis.

Loading is delayed to avoid flash, empty/error copy is task-local, and marker
errors keep the map usable. Focus, selected, open, disabled, and error states do
not rely on color alone. Reduced motion disables nonessential transitions.

## Chatbot Decision Report

The chatbot is a map-side decision aid, not a second dashboard. User questions
use one quiet right-aligned bubble; assistant responses remain flat, left-aligned
documents without repeated avatars, speaker labels, or turn dividers.

Recommendation responses keep a stable reading order: result, basis, comparison
table, two grounded differences, candidate details, actions, and sources. The
comparison table uses native table semantics and horizontal row rules only.
Candidate one is open by default; candidates two through five are native collapsed
details. Gray tint is reserved for the user bubble, selected tab, and compact
status controls rather than large panel backgrounds.

The composer grows from one to four lines. Enter submits, Shift+Enter inserts a
line break, and IME composition never submits. On submit the question itself is
revealed immediately. Answers follow only while the reader is near the bottom;
otherwise a compact `새 답변 보기` action preserves the reader's position.

Successful observation notes such as scope and source method belong in report
basis or Sources, not under `확인할 점`. Internal source abbreviations and policy
identifiers never appear in visible prose. Legacy v1 artifacts remain readable,
including narrow-panel table overflow contained inside the table surface.

Recommendation follow-ups keep only ranked complex ids in the current
conversation's IndexedDB memory. The assistant can therefore compare “1위와
2위” without asking the user to repeat names, while a new conversation has no
candidate memory.

## Verification

Run `npm run test` and `npm run build`. For meaningful visual changes, inspect
1536×1024, 1280×800, 1024×768, 390×844, and 844×390 states. If browser evidence
is unavailable, report visual QA as `Partial` and do not claim visual completion.

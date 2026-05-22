# Home Search Map UX 원칙

Home Search V1 map UI, layout hierarchy, marker, panel, filter, drawer, mobile adaptation을 설계할 때 이 reference를 사용한다.

## Product Posture

Home Search는 운영형 real-estate map tool이다. UI는 quiet, trustworthy, dense, repeatable해야 한다. SaaS landing page, portfolio page, generated marketing mockup처럼 보이면 안 된다.

## Visual Hierarchy

task importance 순서로 interface를 쌓는다.

1. Map surface: full-screen primary workspace.
2. Map controls and marker labels: immediate spatial actions.
3. Exploration panel and filter controls: compact query tools.
4. Detail drawer and trade list: selected parcel investigation.
5. Non-blocking status and retry messages: short recovery feedback.

Panel은 map을 보조해야 한다. panel이 map을 너무 많이 가리거나 marker를 막거나 panning을 secondary처럼 만들면 잘못된 디자인이다.

## UI Units

### App Bar

- 기존 52-56px 범위에 가까운 thin and utilitarian bar를 유지한다.
- brand, environment/status, 필요한 account 또는 utility action만 포함한다.
- hero typography, slogan, large logo, marketing copy를 사용하지 않는다.

### Map Surface

- map이 viewport를 소유한다.
- loading 또는 runtime fallback state에는 simple functional grid를 사용할 수 있다.
- map 뒤에 decorative background, illustration scene, animated visual filler를 두지 않는다.

### Marker Labels

- complex label은 즉시 스캔할 수 있는 latest trade amount와 unit count를 보여준다.
- region label은 문서화된 V1 field가 필요하지 않으면 region name만 보여준다.
- label은 짧고 high contrast이며 size가 안정적이어야 한다.
- glow, blurred shadow, translucent glass보다 border와 solid fill을 선호한다.

### Exploration Panel

- complex search와 region navigation을 담당한다.
- row는 compact하고 predictable해야 한다.
- active selection indicator는 한 번에 하나만 둔다.
- row list로 충분하면 panel 내부에 반복 card를 넣지 않는다.

### Filter Controls

- filter는 compact하고 map 가까이에 둔다.
- unit, price, area, age filter에는 짧은 label과 예측 가능한 numeric field를 사용한다.
- large filter card, marketing-style chip, marker refresh를 지연시키는 hidden filter flow를 피한다.

### Detail Drawer

- complex marker에서 열리고 selected map context를 visible하게 유지한다.
- complex identity, metrics, trade history 순서로 보여준다.
- trade에는 table 또는 compact row를 사용한다.
- audit identifier나 backend-only field를 public UI에 노출하지 않는다.

### Mobile

- exploration panel은 bottom sheet가 된다.
- detail drawer는 full-height 또는 near-full-height bottom sheet가 된다.
- floating filters는 horizontally scrollable 또는 collapsible controls가 된다.
- map panning과 zooming을 first-class action으로 보존한다.

## Visual Language

- Radius: control은 약 6px, panel은 약 8px.
- Depth: border를 먼저 쓰고, shadow는 layer separation에만 보조적으로 쓴다.
- Typography: compact operational scale을 사용하고 app 내부 hero type을 쓰지 않는다.
- Spacing: dense하지만 읽을 수 있는 gap을 사용한다. oversized empty band를 피한다.
- Color: neutral base에 action, complex marker, region marker, error를 위한 role accent만 둔다.

## Anti-Patterns

사용자가 명시적으로 product scope를 바꾸지 않는 한 피한다.

- Landing-page hero layout.
- Purple 또는 blue decorative gradient wash.
- Glassmorphism과 blur panel.
- Row나 table이 더 명확한 곳의 nested card 또는 card grid.
- Decorative blob, orb, abstract background art, generated illustration.
- Glowing button, gradient text, animated flourish, oversized stats card.
- Active task를 돕지 않고 feature를 설명하는 UI text.

## Acceptance Signals

다음 조건을 만족하면 수용 가능하다.

- map이 가장 강한 first-viewport signal로 남는다.
- 사용자가 context를 잃지 않고 search, filter, pan, marker click, trade inspection을 수행할 수 있다.
- visual change 전후에 동일한 V1 API contract가 작동한다.
- error와 empty state가 visible, short, non-blocking이다.
- desktop과 mobile width에서 overlap 또는 clipped text 없이 동작한다.

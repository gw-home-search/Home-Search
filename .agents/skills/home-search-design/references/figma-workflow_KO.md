# Home Search Figma Workflow

Home Search 디자인 작업에 Figma input이 있거나 Figma frame을 `apps/web`으로 옮기라는 요청이 있을 때만 이 reference를 사용한다.

## Boundary

Figma는 design evidence이지 product contract가 아니다. V1 API contract와 Home Search map workflow가 계속 authoritative하다.

Figma가 V1 밖의 data나 behavior를 요구하면 UI를 줄이거나 gap으로 표시한다. Visual comp를 만족하기 위해 public API shape를 바꾸지 않는다.

## Required Flow

1. 정확한 Figma node 또는 selected frame을 추출한다.
2. 해당 node의 structured design context를 가져온다.
3. visual comparison을 위한 screenshot을 캡처한다.
4. node가 너무 크면 metadata를 확인하고 필요한 child node만 가져온다.
5. 디자인이 어떤 Home Search UI unit에 영향을 주는지 식별한다.
6. 화면에 보이는 data를 문서화된 V1 endpoint field에 매핑한다.
7. generated React 또는 utility class를 직접 복사하지 말고 project convention으로 번역한다.
8. 구현 후 browser screenshot으로 검증한다.

## Translation Rules

- `apps/web` API adapter boundary를 보존한다.
- canonical marker field를 보존한다: `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`.
- temporary source variant는 adapter 내부에만 둔다.
- 새 primitive를 만들기 전에 기존 map shell, panel, filter, marker, drawer, table pattern을 우선 사용한다.
- literal Figma decoration보다 Home Search의 restrained operational style을 선호한다.

## Figma Conflict Handling

Figma가 아래를 요구하면 멈추거나 디자인을 수정한다.

- Ranking, favorite, alarm, mail, recommendation, auth, heavy analytics.
- Public UI의 backend audit field.
- 새 public response field.
- Hero, content card, dashboard tile이 map보다 우선하는 구조.
- Color-only state communication.

## Visual Parity Priority

유용한 parity를 목표로 한다.

1. Task flow and information hierarchy.
2. V1 data compatibility.
3. Responsive behavior.
4. Accessibility and keyboard behavior.
5. Pixel-level spacing and color similarity.

Pixel parity가 API compatibility, map usability, accessibility보다 우선할 수 없다.

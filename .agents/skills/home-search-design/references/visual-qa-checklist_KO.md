# Home Search Visual QA Checklist

Home Search 디자인 변경 또는 Figma translation을 수용하기 전에 이 checklist를 사용한다.

## Required Screens

다음 화면 폭이나 browser output을 확인한다.

- Exploration panel이 열린 desktop width.
- Detail drawer가 열린 desktop width.
- Bottom-sheet behavior가 적용된 narrow width.
- 변경이 map fetch behavior를 건드렸다면 marker loading, empty, error, ready states.

## Map Priority

다음 조건을 만족해야 통과다.

- map이 visible하고 dominant하다.
- panel이 기본 상태에서 중요한 marker cluster를 가리지 않는다.
- detail drawer가 spatial context를 파괴하지 않고 열린다.
- zoom controls와 marker labels에 접근 가능하다.
- non-blocking error가 map 밖으로 navigation하지 않는다.

## Readability

다음 조건을 만족해야 통과다.

- marker label이 짧고 한눈에 읽힌다.
- button과 input text가 clipped되지 않는다.
- panel row와 trade table column이 예측 가능하게 정렬된다.
- numeric value는 consistent units를 유지한다.
- surface와 map fallback state 위에서 text contrast가 충분하다.

## Accessibility

다음 조건을 만족해야 통과다.

- Interactive element는 native button, input, link 또는 이에 준하는 accessible control이다.
- Form field에는 usable label이 있다.
- Alert와 status message에는 적절한 live region semantics가 있다.
- Focus order는 visible task flow를 따른다.
- 의미를 color만으로 전달하지 않는다.

## Anti-AI Visual Review

다음 항목이 있으면 실패다.

- Decorative gradient wash.
- Glass 또는 blurred translucent panel.
- Control 또는 marker 주변 glow effect.
- Panel 또는 drawer 내부 nested cards.
- Generic bento grid section.
- Hero-scale typography 또는 marketing slogan.
- Decorative blob, orb, abstract background art, generated filler image.
- Layer separation이 아닌 decoration 목적의 large soft shadow.
- Gradient text 또는 glowing primary button.

허용 예외는 simple map runtime fallback grid나 subtle active-layer shadow처럼 기능적이고 문서화된 경우뿐이다.

## Contract Review

다음 조건을 만족해야 통과다.

- V1 URL 변경 없음.
- V1 request 또는 response field 변경 없음.
- Price, coordinate, date, area unit 변경 없음.
- V2 feature가 V1 map path에 들어오지 않음.
- Detail과 trade drawer는 complex marker의 `parcelId`를 계속 사용함.

## Completion Evidence

최종 디자인 review에는 다음을 보고한다.

- `지적사항`
- `검증 근거 확인`
- `검증 공백`
- `잔여 위험`
- Markdown이 변경되었으면 `KO sync 상태`

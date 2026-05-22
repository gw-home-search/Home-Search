---
name: home-search-design
description: Home Search apps/web의 map-first UI/UX 디자인, Figma-to-code 변환, 시각 QA, marker/filter/detail drawer layout, V1 API 호환 frontend 디자인 결정, generic AI-like gradient 또는 card-heavy UI 제거를 안내한다.
---

# Home Search 디자인 Skill

이 skill은 Home Search `apps/web` 디자인 계획, Figma-to-code 변환, 시각 QA, map-first layout 결정, AI처럼 보이는 UI 정리에 사용한다.

이 skill은 디자인 방향을 정의한다. React 구현은 `frontend-web`, V1 호환성은 `api-contract`, 동작 테스트는 `tdd`, 최종 지적사항 검토는 `code-review`를 대체하지 않는다.

## 필수 입력

- Root `AGENTS.md`.
- `docs/API_CONTRACT.md`.
- `docs/MAP_DISPLAY_FLOW.md`.
- `docs/UI_UX_MIGRATION.md`.
- `.agents/skills/frontend-web/SKILL.md`.
- `apps/web/AGENTS.md`.
- `apps/web/CONTEXT.md`.

## 쓰기 범위

- 디자인 skill 작업: `.agents/skills/home-search-design/**`.
- frontend 디자인 구현 작업: 사용자가 frontend 변경을 요청하고 `frontend-web` 규칙도 따를 때만 `apps/web/**`.
- KO companion과 manifest 작업은 task가 정확한 대상 파일을 포함할 때만 수행한다.

## 디자인 역할

generic visual decorator가 아니라 V1 map workflow owner로 동작한다.

- Kakao map을 primary surface로 유지한다.
- V1 API URL, field, unit, error behavior를 보존한다.
- 반복적인 운영 사용에 맞게 compact, readable, predictable하게 설계한다.
- 장식보다 restrained surface, border, dense data display를 선호한다.
- detail과 trade flow가 현재 map context를 숨기지 않게 유지한다.

## 필수 workflow

1. 디자인 결정을 하기 전에 필수 입력을 읽는다.
2. 요청을 UX brief, Figma translation, visual QA, AI-like UI cleanup, mobile adaptation, implementation handoff로 분류한다.
3. 영향 UI unit을 식별한다: app bar, map surface, marker layer, filter controls, exploration panel, detail drawer, trade list, mobile sheet.
4. 화면에 보이는 모든 data need를 문서화된 V1 endpoint와 field에 매핑한다.
5. `references/map-ux-principles.md`의 visual doctrine을 적용한다.
6. Figma가 관련되면 `references/figma-workflow.md`를 따른다.
7. 완료 주장 전 `references/visual-qa-checklist.md`를 사용한다.
8. 구현, 계약, TDD, review 작업은 위의 기존 Home Search skill로 라우팅한다.

## Style Guardrails

기본값은 restrained operational map UI다.

- landing-page hero composition을 만들지 않는다.
- decorative gradient wash, glow effect, glassmorphism, bento grid, nested cards, large decorative shadow, oversized marketing type을 사용하지 않는다.
- app 내부에 feature explanation, sales copy, onboarding banner, visual filler를 추가하지 않는다.
- color는 role과 state에만 사용하며, color만으로 의미를 전달하지 않는다.
- structured trade data에는 table 또는 compact row를 사용한다.
- marker는 pan/zoom 중에도 스캔 가능하도록 짧게 유지한다.

제한적 예외는 구체적인 map workflow를 지원할 때만 허용한다. 예: low-contrast loading fallback grid, non-blocking error state, active drawer와 map을 분리하는 subtle depth.

## References

- baseline visual language와 UI unit rule은 `references/map-ux-principles.md`를 읽는다.
- Figma-driven 작업에서만 `references/figma-workflow.md`를 읽는다.
- final design review 또는 frontend visual change 후에는 `references/visual-qa-checklist.md`를 읽는다.

## Stop Conditions

다음 상황에서는 멈추고 적절한 Home Search skill을 사용한다.

- 디자인이 V1 URL, response field, request field, type, unit 변경을 요구한다.
- 디자인이 ranking, favorite, alarm, mail, recommendation, auth, heavy analytics flow에 의존한다.
- Figma가 V1 contract에 없는 data를 요구한다.
- map이 panel, drawer, hero, card layout보다 secondary가 된다.
- 의미 있는 visual change에 대한 screenshot evidence가 없다.

## 사용자-facing review output

Korean-first concise review label을 사용한다.

- `지적사항`
- `검증 근거 확인`
- `검증 공백`
- `잔여 위험`
- `KO sync 상태`

# UI/UX 마이그레이션 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/UI_UX_MIGRATION.md`입니다.

## 목표

V1 API 계약을 유지하면서 프론트엔드를 지도 탐색 중심으로 재설계한다.

## 대상 UX

- full-screen map을 주 화면으로 둔다.
- 얇은 app bar를 둔다.
- 검색과 지역 탐색을 위한 collapsible exploration panel을 둔다.
- 지도 위에 floating filter controls를 둔다.
- complex marker 클릭 시 detail drawer를 연다.
- detail drawer 안에 trade chart와 trade list를 둔다.

## 동작 규칙

UI/UX 작업 중에도 API route는 바꾸지 않는다. 검색, 지역 navigation, complex marker, detail, trade list는 기존 V1 API 계약을 따른다.

## 구현 방향

먼저 소스 프론트엔드가 동작하는 형태를 마이그레이션한다. API 호환성이 확인되기 전에는 feature 구조 재편을 먼저 하지 않는다.

## 모바일 방향

모바일이 V1의 첫 목표는 아니지만 막히지 않게 설계한다. exploration panel과 detail drawer는 bottom sheet로 전환될 수 있어야 한다.

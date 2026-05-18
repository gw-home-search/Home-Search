# V1 마이그레이션 계획 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/MIGRATION_PLAN.md`입니다.

## 요약

V1은 실거래 데이터 수집, raw 데이터 보존, normalized trade 저장, 지도 표시 API와 프론트엔드 지도 UX를 위한 최소 안전 제품을 마이그레이션한다.

## 단계

- Phase 0: 문서 기준선을 확정한다.
- Phase 1: `apps/api`, `apps/web`, `infra`, `docs` 구조를 기준으로 대상 저장소를 정리한다.
- Phase 2: PostGIS, Flyway, raw trade 보존, `complex_id` 중심 저장 모델을 준비한다.
- Phase 3: Spring Boot 백엔드, ingest, API controller, V1 테스트를 옮긴다.
- Phase 4: Vite React 프론트엔드를 옮기고 기존 V1 API URL을 유지한다.
- Phase 5: 수집, 중복 ingest, 지도 bounds API, 상세/거래 목록 흐름을 통합 검증한다.

## V1에서 제외

랭킹, 즐겨찾기, 알림, 메일, 추천/인사이트는 명시적으로 재범위화하지 않는 한 V1 critical path에 넣지 않는다.

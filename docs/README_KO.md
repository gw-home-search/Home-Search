# Home Search 마이그레이션 문서 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/README.md`입니다.

## 고정 경로

- 소스 백엔드: `/Users/gwongwangjae/IdeaProjects/home-server`
- 소스 프론트엔드: `/Users/gwongwangjae/frontend/home-client`
- 대상 저장소: `/Users/gwongwangjae/home-search`

## V1 목표

V1은 부동산 아파트 실거래 데이터를 수집하고, 안전하게 저장하고, 지도에 표시하는 데 필요한 최소 제품 범위만 마이그레이션한다.

포함 범위는 region, parcel, complex, trade 도메인 데이터, RTMS 수집, raw source 보존, 중복-safe ingest, failed match 추적, 지도 API, 검색/지역/상세/거래 목록 API, 기존 API 계약을 사용하는 프론트엔드 지도 UX다.

제외 범위는 랭킹, 추세 테이블, 30일 집계, 즐겨찾기, 알림, 메일 배치, 추천/인사이트, 지도 표시와 무관한 분석 최적화다.

## 저장소 구조

- `docs/`: 마이그레이션 결정과 구현 가이드
- `apps/api/`: 백엔드 대상 위치
- `apps/web/`: 프론트엔드 대상 위치
- `infra/`: Postgres/PostGIS, Docker Compose, 모니터링, 환경 문서

## 반드시 지킬 결정

- 주요 API URL은 V1에서 안정적으로 유지한다.
- V1 지도/거래 데이터 범위 밖 백엔드 동작은 V2까지 마이그레이션하지 않는다.
- 데이터 안전성이 집계 기능보다 중요하다.
- `complex_id`와 `complex_pk` 불일치는 명시적으로 해결한다.
- UI/UX는 바뀔 수 있지만 V1 API 계약과 호환되어야 한다.

# API 계약 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/API_CONTRACT.md`입니다.

## 원칙

V1은 주요 API URL을 유지한다. 내부 구현은 안전한 저장 구조로 바뀔 수 있지만, 프론트엔드가 지도 표시를 위해 새 route 이름을 요구하면 안 된다.

## V1 API

- `POST /api/v1/map/regions`: 지도 bounds와 region level 기준으로 지역 marker를 반환한다.
- `POST /api/v1/map/complexes`: 지도 bounds와 필터 기준으로 complex marker를 반환한다.
- `GET /api/v1/search/complexes?q=`: 단지 검색 결과를 반환한다.
- `GET /api/v1/region`: root region navigation 데이터를 반환한다.
- `GET /api/v1/region/{regionId}`: region detail, children, center coordinates를 반환한다.
- `GET /api/v1/detail/{parcelId}`: parcel과 대표 complex 상세를 반환한다.
- `GET /api/v1/trade/{parcelId}`: parcel 아래 complex들의 trade list를 반환한다.

## 호환성 주의

소스에는 `parcelId`, `id`, `latitude`, `lat`, `longitude`, `lng` 이름이 섞여 있다. V1 public response는 문서화된 필드로 안정화하고, 프론트엔드 adapter는 필요 시 old variant를 임시로 받을 수 있다.

## V2 제외 API

랭킹, 즐겨찾기, 사용자 정보, 인증, trade alarm admin batch는 V1 critical path에서 제외한다.

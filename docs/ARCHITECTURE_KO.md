# 아키텍처 기준 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/ARCHITECTURE.md`입니다.

## 백엔드 방향

소스 백엔드는 layered Spring Boot 구조를 사용한다. 대상 백엔드는 V1 경계를 명확히 하면서 region, parcel, complex, trade, ingest, map/detail/search API 흐름을 옮긴다.

중요한 것은 패키지 이름 변경 자체가 아니라 V1 범위를 작게 유지하고 데이터 저장과 API 호환성을 안전하게 만드는 것이다.

## 프론트엔드 방향

소스 프론트엔드는 Vite React 앱이다. 대상 프론트엔드는 API 호출 호환성을 먼저 확인한 뒤 map-first UX로 재구성한다.

소스 앱이 동작하는 형태를 먼저 옮기고, 그 다음 feature group 구조와 UI/UX 개선을 적용한다.

## 핵심 리스크

소스에는 `complex_id`와 `complex_pk` 사용이 섞여 있다. V1은 운영 조회 모델을 `complex_id` 기준으로 정리하고, `complex_pk`, `apt_seq`, source 식별자는 감사와 매칭 보조 정보로 보존해야 한다.

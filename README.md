# Home Search

아파트 실거래 데이터를 수집하고 안전하게 정규화하여 지도에서 탐색할 수 있도록 제공하는 부동산 검색 프로젝트입니다.

Home Search는 RTMS 실거래 원본을 보존하면서 단지와 거래를 연결하고, 사용자가 지도에서 지역과 아파트 단지를 검색해 상세 정보와 거래 내역을 확인할 수 있도록 합니다.

데이터의 정확성, 실패 원인의 추적 가능성, 중복 수집 안전성, 공개 API 호환성을 빠른 기능 확장보다 우선합니다.

## 프로젝트 목표

- RTMS 아파트 실거래 원본 데이터를 먼저 보존합니다.
- 중복 거래가 정규화 테이블에 다시 저장되지 않도록 보장합니다.
- 매칭에 실패한 거래의 원인과 판단 근거를 조회 가능하게 남깁니다.
- 지도, 검색, 단지 상세, 거래 목록 API의 기존 계약을 유지합니다.
- 좌표와 단지 매칭 근거를 보존해 지도에 표시되는 데이터의 출처를 추적할 수 있게 합니다.

## 주요 기능

- **지도 탐색**: 현재 지도 영역에 포함된 지역 및 아파트 단지 marker 조회
- **단지 검색**: 단지명, 별칭, 주소를 이용한 검색과 자동완성
- **상세 조회**: 단지 기본 정보와 연결된 실거래 목록 제공
- **실거래 수집**: RTMS 공개 데이터를 raw ingest로 먼저 저장한 뒤 정규화
- **중복 방지**: 동일한 source identity의 반복 수집이 중복 거래를 만들지 않도록 처리
- **매칭 근거 관리**: 단지 매칭 성공·실패 경로와 후보 정보를 evidence로 보존
- **좌표 관리**: PNU 좌표 조회와 동일 필지 내 복수 단지 좌표 예외 처리

## 핵심 설계 원칙

- 운영 거래 관계는 `complex_id`를 사용합니다.
- `complex_pk`, `apt_seq`, `source`, `source_key`는 감사와 중복 방지 근거로 보존합니다.
- `domain`은 Spring, JDBC, 외부 API 구현에 의존하지 않습니다.
- raw ingest 저장 후에만 정규화 거래를 생성합니다.
- UI는 변경할 수 있지만 공개 API 계약은 명시적인 결정 없이 변경하지 않습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 17, Spring Boot, Gradle, JDBC, Flyway |
| Frontend | React 19, TypeScript, Vite, Vitest |
| Data | PostgreSQL, PostGIS, Redis |
| Infra | Docker Compose, Prometheus, Loki, Grafana |
| Quality | JUnit, Testcontainers, JaCoCo, REST Docs, OpenAPI |

## 저장소 구조

```text
home-search/
├── apps/
│   ├── api/       # Spring Boot API, ingest, domain, persistence
│   └── web/       # React 지도 중심 UI
├── docs/          # 아키텍처, 데이터 저장, API 계약 문서
└── infra/         # 로컬 Docker, PostGIS, Redis, 모니터링
```

## 품질 검증

```bash
# Backend: 테스트, persistence, API 계약, OpenAPI, coverage, Javadoc
cd apps/api
./gradlew backendQualityCheck

# Frontend
cd ../web
npm run test
npm run build
```

Backend는 JaCoCo instruction/line coverage 90% 이상과 branch coverage 65% 이상을 품질 게이트로 사용합니다.

## 문서 읽기

프로젝트의 주요 결정은 다음 문서에서 확인할 수 있습니다.

1. [Migration Plan](docs/MIGRATION_PLAN.md)
2. [Architecture](docs/ARCHITECTURE.md)
3. [Data Storage](docs/DATA_STORAGE.md)
4. [API Contract](docs/API_CONTRACT.md)
5. [Map Display Flow](docs/MAP_DISPLAY_FLOW.md)
6. [Infrastructure and Environment](docs/INFRA_AND_ENV.md)

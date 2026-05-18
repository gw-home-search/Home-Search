# 인프라와 환경 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/INFRA_AND_ENV.md`입니다.

## V1 필수 인프라

V1에는 PostgreSQL with PostGIS, API runtime, frontend runtime, Flyway migration execution, RTMS public data API access가 필요하다.

## 환경 변수

백엔드는 RTMS service key와 database connection 설정이 필요하다. GIS/building enrichment를 V1에 포함할 경우 관련 service key도 필요할 수 있다.

프론트엔드는 소스의 `VITE_API_SERVER_IP`와 동등한 API base URL 설정을 유지해야 한다.

## Flyway 전략

V1 migration은 region, parcel, complex, raw trade ingest, normalized trade, failed match, PostGIS extension/index를 다룬다. V2 migration은 rankings, trends, favorites, alerts, mail batch를 다룬다.

## 모니터링

최소 V1 지표와 로그는 ingest read/inserted/duplicate/failed counts, map endpoint error rate, slow query candidates, failed match counts를 포함한다.

## 수용 기준

Local PostGIS가 시작되고, API가 DB에 연결되며, Flyway가 V1 table을 scratch에서 만들 수 있고, frontend가 env base URL로 API를 호출할 수 있어야 한다.

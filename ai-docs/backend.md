# 백엔드 작업 노트

이 문서는 `apps/api`에 Spring Boot Home Search backend를 만들거나 수정할 때 참고하는 개인 노트다.

## 읽기 순서

1. `AGENTS.md`
2. `docs/README.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DATA_STORAGE.md`
5. `docs/API_CONTRACT.md`
6. `docs/INFRA_AND_ENV.md`
7. 관련 target backend 파일
8. 필요한 경우 source backend read-only reference

## Project Guardrails

- public API URL과 response shape를 임의로 바꾸지 않는다.
- raw ingest record를 normalized trade보다 먼저 저장한다.
- duplicate ingest가 duplicate normalized trade를 만들면 안 된다.
- failed match는 explainable하고 queryable해야 한다.
- operational trade relation은 `complex_id`다.
- `complex_pk`, `apt_seq`, `source`, `source_key`는 audit, matching, dedupe를 위해 보존한다.
- ranking, favorite, alarm, mail, recommendation, heavy analytics는 later-scope로 둔다.

## 작업 루프

1. controller, application service, domain/repository, migration 흐름을 먼저 확인한다.
2. public API 또는 data invariant 영향 여부를 표시한다.
3. behavior 변경이면 가능한 한 failing test를 먼저 작성한다.
4. migration은 fresh DB와 기존 데이터 안전성을 함께 검토한다.
5. 구현 후 controller/DTO/repository boundary를 review한다.
6. 검증 명령과 결과를 남긴다.

## 검증 기준

`apps/api`가 없으면 문서 검증만 한다. 생성된 뒤에는 Gradle task를 확인하고 가능한 명령만 실행한다.

- 기본 후보: `./gradlew test`
- 있으면: `./gradlew verify`
- API contract 영향: controller/DTO/API adapter test 필요.
- ingest 영향: raw save, dedupe, failed match, normalized insert regression test 필요.

## 멈출 조건

API contract 변경, 데이터 손실 가능 migration, `complex_id`/`complex_pk` 해석 변경은 사용자 확인 없이 진행하지 않는다.

# Backend API Skill

이 문서는 `backend-api` 스킬의 한국어 companion이다. 기준은 영문 `SKILL.md`이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## 목적

`apps/api`의 Spring Boot, Flyway, ingest, V1 API 작업을 안내한다.

## 필수 입력

Root `AGENTS.md`, `apps/api/AGENTS.md`, root `CONTEXT.md`, `apps/api/CONTEXT.md`, `docs/ARCHITECTURE.md`, `docs/DATA_STORAGE.md`, `docs/API_CONTRACT.md`, `docs/INFRA_AND_ENV.md`.

## 수정 가능 범위

사용자가 명시적으로 승인하지 않는 한 `apps/api/**`만 수정한다.

## 백엔드 가드레일

V1 API URL과 response shape를 보존한다. Raw ingest record를 normalized trade보다 먼저 저장한다. Duplicate ingest는 duplicate normalized trade를 만들면 안 된다. Failed match는 explainable하고 queryable해야 한다. Operational trade relation은 `complex_id`다. `complex_pk`, `apt_seq`, `source`, `source_key`는 audit, matching, dedupe를 위해 보존한다.

## 검증

`apps/api`가 생기면 Gradle task를 먼저 확인한다. 가장 좁은 관련 test를 실행한 뒤, 존재하면 `./gradlew test` 또는 `./gradlew verify`를 실행한다.

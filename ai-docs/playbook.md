# AI 작업 플레이북

이 문서는 Home Search 작업을 AI에게 맡길 때 내가 참고하는 공통 운영 노트다. 실행 규칙 자체는 `AGENTS.md`, `.agents/skills/`, `.codex/`가 담당한다.

## 기본 루프

1. `git status --short`로 기존 변경을 확인한다.
2. `AGENTS.md`와 관련 `docs/*.md`를 읽는다.
3. 작업이 목표 정리, 구현, TDD, 디버깅, 리뷰 중 어디에 가까운지 나눈다.
4. 변경 전 entrypoint, adapter, public contract, test seam을 확인한다.
5. public behavior 변경이면 가능한 한 RED 테스트에서 시작한다.
6. bug/failure는 재현 가능한 feedback loop부터 만든다.
7. 변경 후 review 관점으로 API, 데이터, 테스트, KO sync 영향을 확인한다.
8. 최종 응답에는 실행한 검증과 남은 위험을 남긴다.

## 목표 기반 작업

`/goal` 작업은 바로 구현하지 말고 짧은 goal brief로 고정한다.

- 목표
- 사용자/운영 가치
- 성공 기준
- 범위와 비범위
- 영향 영역
- 관련 V1 문서
- 검증 증거
- 중단 조건

다음 상황은 구현 전에 멈춘다.

- V1 API URL 또는 response shape 변경이 필요하다.
- DB 변경이 기존 데이터를 잃거나 재해석할 수 있다.
- source repo와 target docs가 충돌한다.
- V2 기능이 V1 critical path에 들어갈 수 있다.

## TDD 작업

Production behavior 변경은 public seam에서 검증한다.

- Backend seam: controller/DTO, application service, repository/Flyway, external API adapter.
- Frontend seam: API adapter, marker transform, component behavior, map failure fallback.
- Private implementation detail만 검증하는 테스트는 피한다.
- 테스트 환경이 아직 없으면 완료를 주장하지 말고 필요한 seam과 임시 검증만 남긴다.

## 디버깅 작업

Bug나 failing check는 추측으로 고치지 않는다.

1. 증상과 실패 명령을 고정한다.
2. 가장 짧은 재현 명령을 만든다.
3. 가설을 하나씩 세우고 관찰 가능한 예측을 둔다.
4. 필요한 계측만 추가한다.
5. 확인된 root cause만 최소 수정한다.
6. 회귀 테스트 또는 검증 명령으로 재발 방지를 확인한다.

세 번 이상 수정이 실패하면 plan으로 돌아간다.

## 코드 리뷰

Review는 findings first로 한다.

- Critical: 데이터 손실, 보안 사고, V1 API 중단, 배포 불가.
- High: 주요 사용자 흐름 실패, duplicate-safe ingest 파손, map display 불능.
- Medium: edge case bug, missing regression test, degraded fallback.
- Low: 실제 위험이 있는 maintainability 또는 documented rule 위반.

검토 축은 correctness, V1 API compatibility, data safety, frontend map usability, security/secrets, missing tests, KO sync다.

## 코드베이스 맵

Codemap은 source of truth가 아니라 탐색 보조 자료다.

- Backend: controller -> application service -> domain/repository -> migration.
- Frontend: App -> map components -> sidebar -> store -> API adapter.
- Data: region, parcel, complex, trade, raw ingest, failed match.

Codemap이 실제 코드와 다르면 코드와 canonical docs가 우선한다.

## 검증 매트릭스

공통 검증:

- `scripts/check-ko-docs.sh`
- `git diff --check`
- `git status --short`

Backend가 생긴 뒤:

- Gradle task 확인.
- 가능하면 `./gradlew test`.
- `verify` task가 있으면 `./gradlew verify`.

Frontend가 생긴 뒤:

- `apps/web/package.json` scripts 확인.
- 있으면 `npm run lint`.
- 있으면 `npm run build`.
- source frontend에 없는 `npm test`를 임의로 요구하지 않는다.

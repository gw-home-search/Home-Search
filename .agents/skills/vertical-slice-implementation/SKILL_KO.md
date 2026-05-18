# Vertical Slice Implementation Skill

이 문서는 `vertical-slice-implementation` 스킬의 한국어 companion이다. 기준은 영문 `SKILL.md`이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## 목적

V1 계획을 작고 독립적으로 검증 가능한 slice로 나눈다. 각 slice는 관련 layer를 통과하는 하나의 관찰 가능한 동작을 제공해야 한다.

## Slice 규칙

각 slice에는 사용자 또는 API에서 관찰 가능한 동작, 정확한 app ownership, API contract checkpoint, 백엔드가 관련될 경우 data invariant checkpoint, test seam, verification command, stop condition을 포함한다.

## 좋은 예

- Raw RTMS ingest record가 normalized trade보다 먼저 저장되고 duplicate source key가 duplicate trade를 만들지 않는다.
- `/api/v1/map/complexes`가 V1 table만 사용해 canonical marker field를 반환한다.
- Map idle이 complex marker를 fetch하고 adapter에서 field를 normalize하며 API 실패 시 map usability를 유지한다.
- Marker click이 contract drift 없이 detail drawer를 연다.

## 피할 것

전체 backend file copy, 전체 UI component 작성 같은 horizontal slice, V2 scope, 검증 없는 app-wide refactor, `api-contract` 없는 cross-app 변경을 피한다.

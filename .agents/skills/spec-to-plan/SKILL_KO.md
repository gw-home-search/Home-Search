# Spec To Plan Skill

이 문서는 `spec-to-plan` 스킬의 한국어 companion이다. 기준은 영문 `SKILL.md`이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## 목적

Home Search의 목표 수준 요청을 V1 API 계약, 데이터 invariant, `apps/api`와 `apps/web` 소유 경계를 보존하는 구현 준비 완료 계획으로 바꾼다.

## 사용 시점

- 요청이 목표 수준이거나 범위가 모호할 때.
- 백엔드와 프론트엔드가 함께 영향을 받을 때.
- 구현 전 API contract, data invariant, 검증 기준을 고정해야 할 때.

## 필수 입력

- root `AGENTS.md`.
- `docs/README.md`.
- `docs/MIGRATION_PLAN.md`.
- 관련 canonical docs.
- root `CONTEXT.md`.
- 백엔드가 관련되면 `apps/api/CONTEXT.md`.
- 프론트엔드가 관련되면 `apps/web/CONTEXT.md`.
- 존재하는 target 파일.

## 계획 필드

계획에는 목표, 성공 기준, 범위, 비범위, public API contract 영향, data invariant 영향, app ownership, vertical slice, test strategy, verification command, stop condition을 포함한다.

## 가드레일

V1 URL, method, field, type, unit 변경, 데이터 손실 가능 migration, V2 기능의 V1 critical path 진입, cross-app 변경의 API contract checkpoint 누락이 있으면 멈추고 확인한다.

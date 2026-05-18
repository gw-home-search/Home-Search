# API Contract Skill

이 문서는 `api-contract` 스킬의 한국어 companion이다. 기준은 영문 `SKILL.md`이며, AI 작업자는 기존 KO 파일을 읽지 않고 영문 원문을 기준으로 동기화한다.

## 목적

`apps/api`와 `apps/web` 작업이 `docs/API_CONTRACT.md`와 호환되는지 확인한다.

## 사용 시점

API client, controller, DTO, marker adapter, detail/trade flow, request validation, error handling을 건드릴 때 사용한다.

## 확인 항목

- URL과 HTTP method.
- Request field name/type.
- Response field name/type.
- 금액 단위.
- 좌표 규칙.
- Error status와 `ProblemDetail` shape.
- Empty result behavior.
- V1/V2 boundary.

## 프론트엔드 규칙

Canonical marker field는 `parcelId`, `lat`, `lng`, `latestDealAmount`, `unitCntSum`이다. `id`, `latitude`, `longitude` 같은 source variant는 adapter 안에서만 임시 수용한다.

## 백엔드 규칙

Canonical V1 field를 반환하고, operational trade relation은 `complex_id`로 유지한다. Audit field는 public trade response에 노출하지 않는다. Map endpoint는 ranking, trend, favorite, alarm, mail, auth state에 의존하지 않는다.

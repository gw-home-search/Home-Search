---
name: vertical-slice-implementation
description: Home Search web/api 작업을 점진적으로 구현하고 검증할 수 있는 얇은 V1 slices로 나눈다.
---

# Vertical Slice Implementation Skill

V1 plan이 있고 implementation 시작 전인 경우 이 skill을 사용한다.

## Purpose

plan을 작고 독립적으로 검증 가능한 slices로 바꾼다. 각 slice는 관련 layers를 통과하는 하나의 observable path를 제공해야 한다.

이것은 incremental implementation과 issue-breakdown patterns를 Home Search에 맞게 조정한 것이다.

## Slice Rules

각 slice는 다음을 포함해야 한다:

- User-visible 또는 API-visible behavior.
- Exact app ownership.
- API contract checkpoint.
- backend가 관련되면 data invariant checkpoint.
- Test seam.
- Verification command.
- Stop condition.

## Good Slice Examples

- Backend: raw RTMS ingest record가 normalized trade insert보다 먼저 저장되고 duplicate source keys가 duplicate trades를 만들지 않는다.
- Backend: `/api/v1/map/complexes`가 V1 tables만으로 canonical marker fields를 반환한다.
- Frontend: map idle이 complex markers를 fetch하고 adapter에서 fields를 normalize하며 API failure 시 map usable 상태를 유지한다.
- Cross-app: marker click이 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를 contract drift 없이 사용해 detail drawer를 연다.

## Avoid

- "copy all backend files" 또는 "build all UI components" 같은 horizontal slices.
- V2 scope.
- 검증되지 않은 app-wide refactors.
- `api-contract` 없는 cross-app changes.

## Output

각 slice에 대해 다음을 제공한다:

- Slice name.
- App ownership.
- Files likely touched.
- Public seam.
- Tests.
- Verification.
- Parallelism: can run in parallel, blocks another slice, or must run first.

User-facing slice breakdowns는 Korean-first prose를 사용하되 commands, paths, API names, status tokens는 그대로 유지한다.

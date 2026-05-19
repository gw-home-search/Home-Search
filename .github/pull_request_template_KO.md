## 요약

상태: Pass|Partial|Fail

## 작업 범위

- backend:
- frontend:
- harness:
- docs/infra:

## TDD Evidence

First RED:
Expected RED failure:
Minimum GREEN:

## 검증

검증:
- `cd apps/api && ./gradlew test` = not run ()
- `cd apps/web && npm run test` = not run ()
- `cd apps/web && npm run build` = not run ()
- `git diff --check` = not run ()

## Contract 영향

영향 없음

<!-- 또는: 영향 있음: <요약> -->

contract-reviewer:

## 주요 위험

주요 위험:
reviewer:

## 다음 행동

다음 행동:

## 체크리스트

- [ ] main merge 자동화 없음
- [ ] main push 없음
- [ ] integration branch만 push
- [ ] draft PR
- [ ] V1 API URL/response 영향 확인
- [ ] DB migration 실행 없음
- [ ] Open API 호출 없음
- [ ] secrets 저장 없음

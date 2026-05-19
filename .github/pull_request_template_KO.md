## 요약

상태: Pass|Partial|Fail

## 작업 범위

- backend:
- frontend:
- harness:
- docs/infra:

## TDD 근거

최초 RED:
예상 RED 실패:
최소 GREEN:

## 검증

검증:
- `git diff --check` = not run (사유)
- `cd apps/api && ./gradlew backendQualityCheck` = not run (사유)
- `cd apps/web && npm run test` = not run (사유)
- `cd apps/web && npm run build` = not run (사유)
- `python3 .codex/harness/pr_lint.py --self-test` = not run (사유)
- `python3 .codex/harness/user_language_check.py --self-test` = not run (사유)
- `python3 .codex/hooks/stop_verification_gate.py --self-test` = not run (사유)
- `python3 .codex/hooks/post_tool_use_review.py --self-test` = not run (사유)
- `bash scripts/check-ko-docs.sh` = not run (사유)

Coverage: >=90%
Docs/OpenAPI: generated + verified

## 계약 영향

영향 없음

<!-- 또는: 영향 있음: <요약> -->

contract-reviewer:

## 주요 위험

주요 위험: 없음
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

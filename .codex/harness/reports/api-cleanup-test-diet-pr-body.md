## 요약

상태: Pass
`apps/api` foundation 테스트의 과도한 구조 고정을 줄이고, later-scope `apps/news`의 테스트 전용 boundary scaffold를 제거했습니다.
추가로 의미 없이 통과하던 enum 문서 테스트와 실행 가능한 gate를 중복 고정하던 quality gate 문자열 테스트를 제거했습니다.

- work item: api-cleanup-test-diet
- targets: backend
- integration branch: feat/api-cleanup-test-diet-integration
- PR type: Chore

## 작업 범위

- backend: `apps/api` foundation test diet
- backend 추가 정리: 대상 없는 enum documentation test, 중복 quality gate config-string test, monitoring exact-version assertions 제거
- later-scope app: `apps/news` unused boundary scaffold 정리
- 유지: `apps/rtms-loader`, `apps/source-data`, `libs/rtms-ingest-core`
- 변경 없음: public API URL/response, DB migration, Docker volume/data, production ingest/map behavior

## 사용 skill

| phase | skill | role | path | required evidence |
| --- | --- | --- | --- | --- |
| execute | home-search-harness | orchestrator | .codex/harness/home | 상태; 검증; 다음 행동 |
| execute | $tdd | checkpoint | .agents/skills/tdd/SKILL.md | 최초 RED; 예상 RED 실패; 최소 GREEN |
| execute | $backend-api | support | .agents/skills/backend-api/SKILL.md | foundationTest; backendQualityCheck |
| execute | $api-contract | checkpoint | .agents/skills/api-contract/SKILL.md | 계약 영향 |
| recover | $systematic-debugging | recovery | .agents/skills/systematic-debugging/SKILL.md | Docker/Testcontainers 실패 원인 분리 |
| gate | $security-audit | checkpoint | .agents/skills/security-audit/SKILL.md | security-audit: 지적사항 |
| gate | $code-review | review | .agents/skills/code-review/SKILL.md | reviewer: 지적사항 |

## TDD 근거

최초 RED: cleanup/test-only 변경이라 production behavior RED는 not applicable
예상 RED 실패: stale 또는 과도한 foundation assertion이 구조 이동과 harness worklog 변경에 실패
최소 GREEN: public/data safety assertion은 유지하고 중복 persistence/package/worklog assertion만 제거

## 검증

검증:
- `git diff --check` = pass (ok)
- `cd apps/api && ./gradlew foundationTest` = pass (BUILD SUCCESSFUL)
- `cd apps/news && ../api/gradlew test` = pass (BUILD SUCCESSFUL)
- `python3 .codex/harness/pr_lint.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/pr_body_check.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/pr_context.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/home_flow.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/home_integrate.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/home_plan.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/home_pr.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/home_report.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/project_terms_check.py` = pass (상태: Pass)
- `python3 .codex/harness/project_terms_check.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/skill_routing.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/user_language_check.py --self-test` = pass (self-test passed)
- `python3 .codex/harness/worklog_sync.py --self-test` = pass (self-test passed)
- `.codex/harness/home --self-test` = pass (self-test passed)
- `python3 scripts/check-test-display-names.py` = pass (test display name policy passed)
- `cd apps/api && ./gradlew backendQualityCheck` = pass (BUILD SUCCESSFUL)
- `cd apps/api && ./gradlew clean backendQualityCheck` = pass (BUILD SUCCESSFUL)

Coverage: >=90%
Docs/OpenAPI: generated + verified

## 계약 영향

영향 없음

contract-reviewer: not needed

## 보안 영향

보안 영향: 없음
security-audit: 지적사항 = none

## 주요 위험

주요 위험: 없음
reviewer: 지적사항 = none

## 다음 행동

다음 행동: GitHub draft PR diff와 checks를 확인한 뒤 수동 merge 여부를 결정합니다.

## 체크리스트

- [x] main merge 자동화 없음
- [x] main push 없음
- [x] integration branch만 push
- [x] draft PR
- [x] public API URL/response 영향 확인
- [x] DB migration 실행 없음
- [x] Open API 호출 없음
- [x] secrets 저장 없음

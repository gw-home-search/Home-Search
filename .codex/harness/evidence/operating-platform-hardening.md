# 운영환경 고도화 TDD 근거

## Inbox pagination overflow

- 최초 RED: `./gradlew :core:test --tests 'com.home.infrastructure.persistence.user.JdbcInsightRepositoryTest.returnsEmptyInboxForLargeValidPage' --no-daemon --stacktrace`가 `ArithmeticException`으로 실패했다.
- 예상 RED 실패: 계약상 유효한 `page=Integer.MAX_VALUE`, `size=100`의 offset을 `int`로 계산해 overflow가 발생했다.
- 최소 GREEN: offset을 64-bit로 계산하도록 변경한 뒤 동일 명령이 `BUILD SUCCESSFUL`로 통과했다.

## Isolated Compose validation

- 최초 RED: 별도 publication clone에서 `infra/test-compose-config.sh`가 clone에 없는 local `.env` 기본 경로 때문에 실패했다.
- 예상 RED 실패: 검증기가 개발자 checkout의 비추적 secret 파일 존재에 의존했다.
- 최소 GREEN: 기본 경로는 유지하면서 local env file 경로만 환경 변수로 override할 수 있게 했고, `/dev/null` override로 Compose config 검증을 통과했다. 실제 secret 파일은 읽거나 복사하지 않았다.

## Diff hygiene

- 최초 RED: `git diff --check main...HEAD`가 Markdown 4개 파일의 EOF 공백을 보고했다.
- 예상 RED 실패: canonical Markdown 끝에 불필요한 빈 줄이 포함됐다.
- 최소 GREEN: EOF 공백만 제거하고 동일 diff 검사를 통과했다.

## 주요 동작 slice의 회귀 fixture

- property news/outbox: `MarketNewsCollectionServiceTest`, `PropertyEventOutboxRelayServiceTest`, `JdbcPropertyEventOutboxRepositoryJdbcIntegrationTest`, `MarketNewsControllerContractTest`가 수집 예산, publish 실패 시 outbox 보존, 중복 relay, public response 계약을 검증한다.
- user worker/inbox: `InsightPublishedEventServiceTest`, `InsightEventListenerTest`, `InsightEventMessageParserTest`, `JdbcInsightRepositoryTest`가 duplicate/stale no-op, version gap rollback, retry/DLQ 경계, inbox 원자성을 검증한다.
- gateway/contracts: `.github/scripts/test-event-contracts.sh`, `.github/scripts/test-event-contract-baseline.sh`, `infra/nginx/test-public-gateway-routing.sh`가 금지 데이터, schema baseline, exact-path owner와 인증 경계를 검증한다.
- Terraform/security: bootstrap/staging `*.tftest.hcl`가 workload별 secret allowlist, cross-environment deny, streaming/backup/alarm action 조건을 검증한다.
- release: `infra/deploy/test-deploy-scripts.sh`, `infra/release/test-create-release-manifest.sh`, `.github/scripts/test-release-contract-metadata.sh`가 digest 고정, rollback, schema/topic/migration hash를 검증한다.

이전 주요 slice의 exact 최초 RED console transcript는 현재 저장소에 보존돼 있지 않다.
위 항목은 실행된 회귀 fixture와 기대 실패 경계를 기록한 것이며, 보존되지 않은
출력을 재구성해 최초 RED라고 주장하지 않는다. 현재 수정 slice의 실제
최초 RED/예상 RED 실패/최소 GREEN은 위 세 절에 별도로 기록했다.

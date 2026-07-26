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

## News collection resume safety

- 최초 RED: 재개 cursor·누적 progress·raw payload 일치 계약을 테스트에 먼저 추가한 뒤 targeted `:core:test`가 존재하지 않는 progress API와 `MarketNewsWorkUnitSpec` 상태 때문에 compile 실패했다.
- 예상 RED 실패: `RUNNING` unit이 저장된 `last_provider_start`를 무시하고 `start=1`부터 재호출하며, 같은 provider 위치의 변경된 payload가 기존 raw row의 article/rejection 결과를 바꿀 수 있었다.
- 최소 GREEN: 마지막 성공 page와 누적 call/raw/oldest 상태를 page마다 저장·복원하고, 동일 raw payload만 link/reject하도록 제한했다. service unit test와 PostgreSQL resume/raw-match integration test가 통과했다.

## News failure kind domain ownership

- 최초 RED: `./gradlew :core:test --tests com.home.domain.news.MarketNewsFailureKindTest --no-daemon --stacktrace`가 `MarketNewsFailureKind`를 찾을 수 없어 `:core:compileTestJava`에서 실패했다.
- 예상 RED 실패: 새 `RAW_POSITION_CONFLICT`를 포함한 영속 운영 실패 사유가 문자열로 전달돼 stable enum과 한국어 운영 metadata를 소유하지 않았다.
- 최소 GREEN: 전체 뉴스 수집 실패 사유를 `MarketNewsFailureKind`로 타입화하고 `titleKo()`/`descriptionKo()` 및 남은 work unit 중단 predicate를 domain에 배치했다. domain/service targeted test와 `recordWorkUnitPageProgress()` 저장 후 재조회하는 PostgreSQL integration test가 통과했다.

## Gate evidence propagation

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`가 `parse_gate_review` 부재 `NameError`로 실패했고, `python3 .codex/harness/home_report.py --self-test`가 실제 gate/TDD 문구를 찾지 못해 실패했다.
- 예상 RED 실패: gate 출력이 payload에서 유실돼 `Partial` 또는 `reviewer/security listed` 결과를 게시 전에 차단하지 못하고 PR body가 일반 문구를 생성했다.
- 최소 GREEN: gate 상태·reviewer·security·contract 결정을 구조화하고 `Pass/none/none/Pass`만 게시 가능하게 했다. TDD 문서와 gate 검증 공백을 payload/PR body에 전달하며 `home_flow`, `home_report`, `pr_lint` self-test가 통과했다.

## Gate output and verification completeness

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`에 operating-platform preset의 `home_report` 검증과 gate prompt의 reviewer/contract 필수 라벨 assertion을 추가한 뒤 `self-test failed: home_flow`로 실패했다.
- 예상 RED 실패: 변경된 `home_report.py`의 필수 self-test가 preset evidence에서 누락되고, 정상 reviewer가 문서화된 출력 형식만 따르면 publish 파서가 필수 결정을 찾지 못했다.
- 최소 GREEN: backend preset에 `python3 .codex/harness/home_report.py --self-test`를 추가하고 gate prompt에 `reviewer`, `계약 영향`, `contract-reviewer` 라벨을 명시했다. `home_flow`, `home_report`, `pr_lint`, project terms 검증이 통과했다.

## Isolated integration PR publication

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`가 `build_pr_command` 부재 `NameError`로 실패했다. 실제 isolated integration 검증은 통과했지만 `home_pr.py`가 hard-coded checkout에서 branch를 찾아 게시 전에 차단된 실패도 재현 근거로 확인했다.
- 예상 RED 실패: `home_flow.py`가 별도 `home_pr.py` 프로세스에 선택된 main worktree를 전달하지 않아, 검증한 integration branch와 게시 시 조회하는 repository가 달라졌다.
- 최소 GREEN: PR command builder가 `--main-worktree`를 항상 전달하고 PR 후 reporter도 같은 resolved worktree를 사용하도록 했다. preset에 `home_pr` self-test를 포함했으며 `home_flow`, `home_pr`, `pr_lint` self-test와 `git diff --check`가 통과했다.

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

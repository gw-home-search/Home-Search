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

## News terminal page crash safety

- 최초 RED: `./gradlew :core:test --tests com.home.application.news.collection.MarketNewsCollectionServiceTest.completesTerminalResumePageAtomically --no-daemon --stacktrace`가 `completeWorkUnitPage` 부재로 `:core:compileTestJava`에서 실패했다.
- 예상 RED 실패: 재개한 마지막 `start=901` page에서 progress cursor를 먼저 저장하고 cutoff 완료 전 중단되면 다음 실행이 `start=1001`로 건너뛰어 정상 unit을 `TRUNCATED/CUTOFF_NOT_REACHED`로 오판했다.
- 최소 GREEN: terminal page는 일반 progress 저장 없이 repository의 단일 `completeWorkUnitPage`로 cursor·count·cutoff·COMPLETED 상태를 한 SQL update에 저장한다. `MarketNewsCollectionServiceTest` 전체와 실제 PostgreSQL terminal row/resumable exclusion integration test가 통과했다.

## News failure kind domain ownership

- 최초 RED: `./gradlew :core:test --tests com.home.domain.news.MarketNewsFailureKindTest --no-daemon --stacktrace`가 `MarketNewsFailureKind`를 찾을 수 없어 `:core:compileTestJava`에서 실패했다.
- 예상 RED 실패: 새 `RAW_POSITION_CONFLICT`를 포함한 영속 운영 실패 사유가 문자열로 전달돼 stable enum과 한국어 운영 metadata를 소유하지 않았다.
- 최소 GREEN: 전체 뉴스 수집 실패 사유를 `MarketNewsFailureKind`로 타입화하고 `titleKo()`/`descriptionKo()` 및 남은 work unit 중단 predicate를 domain에 배치했다. domain/service targeted test와 `recordWorkUnitPageProgress()` 저장 후 재조회하는 PostgreSQL integration test가 통과했다.

## Gate evidence propagation

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`가 `parse_gate_review` 부재 `NameError`로 실패했고, `python3 .codex/harness/home_report.py --self-test`가 실제 gate/TDD 문구를 찾지 못해 실패했다.
- 예상 RED 실패: gate 출력이 payload에서 유실돼 `Partial` 또는 `reviewer/security listed` 결과를 게시 전에 차단하지 못하고 PR body가 일반 문구를 생성했다.
- 최소 GREEN: gate 상태·reviewer·security·contract 결정을 구조화하고 `Pass/none/none/Pass`만 게시 가능하게 했다. TDD 문서와 gate 검증 공백을 payload/PR body에 전달하며 `home_flow`, `home_report`, `pr_lint` self-test가 통과했다.

## Gate evidence uniqueness

- 최초 RED: 설명 문장 속 `reviewer: 지적사항 = none`과 최종 `listed` 라벨이 함께 있는 fixture에서 `home_flow --self-test`가 실패했고, gate evidence 없는 payload를 fail-closed로 요구한 `home_report --self-test`도 실패했다. 실제 gate 오류 메시지가 최종 `listed` 대신 앞선 `none`을 파싱해 재현됐다.
- 예상 RED 실패: 부분 문자열 첫 일치와 낙관적 기본값 때문에 모순·중복·누락된 gate 결과가 `none/Pass`로 게시 조건을 우회할 수 있었다.
- 최소 GREEN: 게시 결정 라벨을 anchored full-line으로 정확히 한 번만 허용하고 누락·중복은 `Partial/listed`로 처리했다. PR body도 gate evidence 누락 시 `reviewer/security=listed`, `contract=Partial`을 출력하며 관련 harness self-test가 통과했다.

## Gate output freshness

- 최초 RED: stale output 제거, missing output 차단, stale mtime 차단 fixture를 추가한 뒤 `home_flow --self-test`가 `prepare_gate_output` 부재 `NameError`로 실패했다.
- 예상 RED 실패: gate subprocess가 output을 만들지 않아도 파싱을 건너뛰고, 고정 경로에 남은 이전 gate 파일을 현재 판정으로 재사용할 수 있었다.
- 최소 GREEN: non-dry gate 실행 전에 기존 output을 제거하고 실행 후 새 regular file 존재, 실행 시작 이후 mtime, non-empty content를 모두 강제했다. missing/stale fixture와 전체 harness self-test가 통과했다.

## Gate required section completeness

- 최초 RED: 의사결정 필드만 있고 `최초 RED`, `예상 RED 실패`, `최소 GREEN`, `검증`, `리뷰`, `다음 행동`이 없는 fixture를 차단하도록 추가한 뒤 `python3 .codex/harness/home_flow.py --self-test`가 실패했다.
- 예상 RED 실패: `Pass/none/none/Pass` 결정만 있으면 TDD·검증·리뷰 근거와 후속 행동이 비어 있어도 publish할 수 있었다.
- 최소 GREEN: 필수 section label을 anchored·unique 방식으로 읽고 모두 non-empty일 때만 publish를 허용했다. 완전한 multiline fixture는 통과하고 누락 fixture는 차단하는 `home_flow` self-test가 통과했다.

## Gate output and verification completeness

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`에 operating-platform preset의 `home_report` 검증과 gate prompt의 reviewer/contract 필수 라벨 assertion을 추가한 뒤 `self-test failed: home_flow`로 실패했다.
- 예상 RED 실패: 변경된 `home_report.py`의 필수 self-test가 preset evidence에서 누락되고, 정상 reviewer가 문서화된 출력 형식만 따르면 publish 파서가 필수 결정을 찾지 못했다.
- 최소 GREEN: backend preset에 `python3 .codex/harness/home_report.py --self-test`를 추가하고 gate prompt에 `reviewer`, `계약 영향`, `contract-reviewer` 라벨을 명시했다. `home_flow`, `home_report`, `pr_lint`, project terms 검증이 통과했다.

## Isolated integration PR publication

- 최초 RED: `python3 .codex/harness/home_flow.py --self-test`가 `build_pr_command` 부재 `NameError`로 실패했다. 실제 isolated integration 검증은 통과했지만 `home_pr.py`가 hard-coded checkout에서 branch를 찾아 게시 전에 차단된 실패도 재현 근거로 확인했다.
- 예상 RED 실패: `home_flow.py`가 별도 `home_pr.py` 프로세스에 선택된 main worktree를 전달하지 않아, 검증한 integration branch와 게시 시 조회하는 repository가 달라졌다.
- 최소 GREEN: PR command builder가 `--main-worktree`를 항상 전달하고 PR 후 reporter도 같은 resolved worktree를 사용하도록 했다. preset에 `home_pr` self-test를 포함했으며 `home_flow`, `home_pr`, `pr_lint` self-test와 `git diff --check`가 통과했다.

## News stopping failure resume safety

- 최초 RED: durable 중단 원인을 가진 재개 execution fixture와 PostgreSQL 복원 test를 먼저 추가한 뒤 `MarketNewsCollectionExecution.stoppingFailureKind()`가 없어 targeted compile이 실패했다.
- 예상 RED 실패: `AUTHENTICATION`, `DAILY_QUOTA`, `DAILY_CALL_BUDGET` 실패 저장 후 execution 종료 전에 중단되면 재개 조회가 중단 원인을 잃고 provider를 다시 호출할 수 있었다.
- 최소 GREEN: 재개 조회가 failed/skipped work unit의 typed 중단 원인을 복원하고 service가 provider 호출 없이 남은 unit을 skip한 뒤 같은 원인으로 execution을 종료한다. service unit test와 실제 PostgreSQL persistence test가 통과했다.

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

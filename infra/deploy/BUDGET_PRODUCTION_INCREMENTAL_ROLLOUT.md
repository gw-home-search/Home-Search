# budget-production 증분 기능 복구 runbook

이 runbook은 `rollout-budget-production.yml`의 보호된 증분 rollout만 다룬다. 최초 배포 workflow, PostgreSQL·Valkey task definition, DNS, EC2, EBS, backup/DLM 정책, data import, recovery rehearsal은 이 절차의 변경 대상이 아니다.

## 배포 전 외부 전제조건

- GitHub repository variable `MARKET_NEWS_ENABLED=true`
- RTMS APT service key, Naver news API Hub credential 2개, Google·Kakao·Naver OAuth credential 6개가 각 전용 SSM SecureString에 out-of-band로 입력됨
- 세 provider console callback이 `https://homesearch.world/login/oauth2/code/{provider}`와 일치함
- `infra/deploy/f37-model-manifest.json`의 exact 7-file allowlist와 checksum을 만족하는 F37 artifact가 준비됨
- F37 model S3/SSM, exact market-news/RTMS Scheduler, AI canary log read 권한만 포함한 bootstrap saved plan이 적용되었고, SHA-256을 담은 `terraform-bootstrap-plan.json`이 release SHA 전용 S3 경로에 존재함
- `security-audit: 지적사항 = none`

`terraform-bootstrap-plan.json`의 `checks` 에는 `only_approved_runtime_permissions=true`,
`destroy_changes=0`, `applied=true`, `external_secrets_ready=true`, `saved_plan_sha256=<64-hex>`를
기록한다. Secret 값은 기록하지 않고 non-empty/고정 `UNSET` 아님 결과만 증명한다.

어느 하나라도 충족되지 않으면 release tag 또는 운영 rollout을 진행하지 않는다.

## F37 immutable upload

모델 byte는 Git, Docker image, GitHub artifact, Terraform state, SSM payload에 넣지 않는다. artifact 디렉터리를 받은 운영자는 다음 스크립트만 사용한다.

```bash
infra/deploy/upload-f37-model.sh \
  /secure/path/deployment__F37_monthly_anchor_prev3_rolling_huber_010 \
  home-search-budget-production-backup-<account-id>
```

스크립트는 exact allowlist, SHA-256, regular-file 조건을 먼저 확인한다. 기존 object는 `aws:kms`와 `sha256` metadata가 모두 일치할 때만 재사용하며 다른 object를 overwrite하지 않는다.

## Workflow dispatch

다음 입력만 허용한다.

```text
release_tag=vX.Y.Z
release_sha=<merged-main-40-hex>
property_migration_target=41
enable_rtms_refresh_schedule=true
enable_prediction=true
enable_ml_service=true
oauth_enabled_providers=google,kakao,naver
protected_rollout_approval=true
security_audit_result=none
bootstrap_plan_evidence_uri=s3://.../deployment-evidence/bootstrap/<release-sha>/terraform-bootstrap-plan.json
```

market-news public enablement는 입력으로 받지 않고 배포 직전 live 값을 보존한다. 네
schedule은 항상 `false`로 유지하며 bootstrap은 `skipped` evidence로 기록한다.

`rtms_resume_request_id`는 직전 실행과 **같은 KST 날짜 안에서만** 유효하다. daily job의 식별 파라미터는
`runDate`와 `requestId` 두 개이고 `runDate`는 KST 당일로 결정되므로, KST 자정을 넘긴 뒤 같은 requestId를 넘기면
`BatchExecutionCorrelationGuard`가 `requestId was already used by a different Batch parameter set`로 즉시
종료시킨다. 날짜가 바뀐 재시도에서는 이 입력을 비워 새 requestId를 발급받는다.

## RTMS catch-up 재사용

같은 KST 날짜에 RTMS catch-up이 이미 성공했다면 `rtms_catchup_execution_ids`에
`<first>,<repeat>` 실행 id를 넘겨 수집을 다시 하지 않는다. workflow는 두
`rtms-daily-refresh` 태스크를 실행하지 않고, 넘긴 id를 그대로
`run-runtime-feature-audit.sh`에 전달한다. 감사는 `rtms_collection_execution`과
`rtms_ingest_run`을 DB에서 직접 조회하므로 `rtms-catchup.json` 증거는 동일하게
생성되고 반복 실행의 `normalized_inserted_count == 0` 검증도 그대로 수행된다.

재사용 여부는 `deployment-evidence/rtms-catchup-source.json`의
`rtms_catchup_reused`에 기록한다. `rtms_resume_request_id`와 동시에 지정할 수
없다. 실행 id는 `public.rtms_collection_execution`에서 확인한다.

이 입력은 공공 API 호출 쿼터를 아끼기 위한 것이며, 다른 날짜의 실행 id를 넘기면
감사 SQL이 당일 데이터와 맞지 않아 실패한다.

## Application 배포 비율과 호스트 메모리

budget production은 단일 EC2 host에서 모든 application service를 실행한다. ECS 배포
설정이 `maximumPercent 200`이면 교체 중 신규 task와 기존 task가 동시에 떠 메모리가 두 배로
필요하고, 이 host에서는 배치가 실패한다.

```
unable to place a task because no container instance met all of its requirements.
The closest matching container-instance ... has insufficient memory available.
```

따라서 모든 application service는 `maximumPercent 100`, `minimumHealthyPercent 0`으로
운영한다. 교체 중 해당 service는 짧게 비지만 host 메모리 안에서 안전하게 교체된다.
rollout plan은 live 값을 읽어 tfvars에 반영하므로 live 설정이 곧 기준이 된다.

v1.0.57 rollout(run 30955022823)은 `property-api`와 `chat-bff`만 200으로 남아 있어
`Waiter ServicesStable failed: Max attempts exceeded`로 중단됐다.

## 실패 후 재시도

한 번 실행한 rollout은 **같은 release tag로 재시도할 수 없다.**

- prep plan이 이미 적용된 상태에서 같은 tag로 다시 planning하면 `terraform plan -detailed-exitcode`가 `0`을
  반환하고, `Verify incremental Terraform allowlist`가 exit code `2`를 요구하므로 실패한다.
- 같은 commit에 새 tag만 붙여도 release는 통과하지 못한다. ECR repository의 SHA tag가 immutable이라
  `The image tag '<sha>' already exists ... cannot be overwritten`으로 push가 거부된다.

따라서 재시도는 **새 commit을 main에 머지한 뒤 새 tag를 끊는 것**이 유일한 경로다.

release run이 이미지 build 도중 실패하면 일부 image는 ECR에 push된 상태로 남는다. 같은
commit으로 release를 다시 실행하면 먼저 push된 repository에서
`The image tag '<sha>' already exists ... cannot be overwritten`으로 거부되므로, 이때도
새 commit이 필요하다. Gradle wrapper 잠금 timeout이나 OIDC token 만료처럼 build 환경
문제로 실패한 경우에도 동일하다.

prep plan은 현재 application task definition ARN과 desired count를 exact pin하고 schedule을 disabled로 둔다. saved full plan 적용 뒤 V39/V40→V41 migrate 또는 live V41 validate-only, F37 install, ML health, RTMS first/repeat catch-up, news bootstrap skipped evidence, AI canary 순서로 진행한다. Application 교체 순서는 `ml → property-api → user-api → ai → chat-bff → admin-api → admin-gateway → public-gateway`이다.

Workflow는 routine rollout에서 Google·Kakao·Naver 실제 login evidence를 기다리지 않는다.
각 authorization endpoint의 redirect status와 provider host까지만 확인하고 redirect를
따라가지 않으며, invalid callback이 controlled 4xx인지 확인한다. 생성 evidence는
`status=skipped`, `reason=routine rollout excludes live provider login acceptance`,
`syntheticContractPassed=true`를 기록하고 query, `state`, `code`, client id를 저장하지 않는다.
provider console이나 실제 secret 조합 drift는 잔여 위험으로 남는다.

## 증거와 중단 조건

최종 builder는 source/live/diagnosis/RED, bootstrap/prep/final Terraform, application/platform 분리, migration, model, ML, RTMS, news, OAuth, canary, service, feature, observation 증거를 모두 요구한다. 각 증거는 credential, JWT, cookie, OAuth `code`/`state`, 검색어 원문을 포함하지 않는다.

15분 hard gate에서 public 5xx, task crash, readiness failure, secret 노출, platform 변경 중 하나라도 발생하면 rollback한다. p95 및 순간 CPU/memory는 단독 rollback 사유가 아니며 60분 후속 관찰에 누적한다.

Rollback은 캡처된 application 8개 ARN/desired count만 복원하고 각 service stable waiter와 public search smoke를 실행한다. Schedule을 먼저 disable하고 PostgreSQL·Valkey, DNS, EBS, EC2, 정상 수집 데이터, V40 index, V41 SEO catalog, model S3 object는 변경하거나 삭제하지 않는다.

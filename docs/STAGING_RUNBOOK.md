# Staging 운영 Runbook

## 범위와 안전 원칙

이 문서는 AWS staging의 최초 생성, release, migration, rollback, backup,
restore rehearsal 절차를 소유한다. production cutover, DNS 전환, production
배포는 이번 범위 밖이다.

- 공개 URL과 API response contract는 `API_CONTRACT.md`를 따른다.
- Terraform과 release manifest에는 secret 값을 넣지 않는다.
- DB down migration은 자동 실행하지 않는다.
- coordinate-source DB는 operational backup 대상이 아니다.
- Docker volume 삭제와 기존 DB overwrite는 이 절차에 없다.

## Workload 분류

| 분류 | Workload | 운영 방식 |
|---|---|---|
| service | `property-api`, `user-api`, `admin-api`, `public-gateway`, `admin-gateway`, `user-insight-worker` | ECS service, private task subnet, circuit-breaker rollback |
| live-capable optional | `ml` | EFS `/model` artifact가 있을 때만 `enable_ml=true` |
| maintenance | `property-batch`, `admin-ops` | 요청된 작업마다 ECS one-shot task |
| bootstrap one-shot | `secret-bootstrap`, `database-bootstrap`, `runtime-grants` | 최초 생성 및 credential rotation 후 명시 실행 |
| migration one-shot | `property-flyway`, `admin-migration`, `user-flyway`, `source-data-migration` | service update 전에 모두 exit code 0 필요 |
| continuity one-shot | `backup`, `restore-verification` | EventBridge Scheduler 또는 수동 rehearsal |
| later-scope | authenticated chatbot, ranking, alarms, recommendations, production cutover | 이번 staging critical path에서 제외 |

## 필수 GitHub Environment와 변수

`release` Environment:

| 변수 | 예시 형상 | Secret 여부 |
|---|---|---|
| `AWS_RELEASE_ROLE_ARN` | release 전용 OIDC role ARN | 아니오 |
| `AWS_REGION` | `ap-northeast-2` | 아니오 |
| `STAGING_PUBLIC_ORIGIN` | `https://staging.example.com` | 아니오 |
| `KAKAO_MAP_APP_KEY` | browser용 JavaScript app key | 공개 client 식별자 |

`staging` Environment:

| 변수 | 예시 형상 |
|---|---|
| `AWS_STAGING_ROLE_ARN` | protected staging OIDC role ARN |
| `AWS_REGION` | `ap-northeast-2` |
| `STAGING_ADMIN_ALLOWED_CIDRS_JSON` | `["203.0.113.10/32"]` |
| `STAGING_PUBLIC_CERTIFICATE_ARN` | public ACM certificate ARN |
| `STAGING_ADMIN_CERTIFICATE_ARN` | admin ACM certificate ARN |
| `STAGING_PUBLIC_ORIGIN` | public HTTPS origin, path 없음 |
| `STAGING_ADMIN_ORIGIN` | admin HTTPS origin, path 없음 |
| `TERRAFORM_STATE_BUCKET` | bootstrap output |
| `TERRAFORM_STAGING_STATE_KEY` | `home-search/staging/terraform.tfstate` |
| `TERRAFORM_STATE_KMS_KEY_ARN` | bootstrap output |

성능 evidence 변수:

| 변수 | 의미 |
|---|---|
| `STAGING_PERF_MAP_BOUNDS_JSON` | 운영 데이터가 있는 작은 bbox request JSON |
| `STAGING_PERF_READY_COMPLEX_ID` | prediction 상태가 `READY`인 고정 complex id |
| `STAGING_PERF_MISS_COMPLEX_ID` | cache miss 관측용 유효 complex id |

실제 OAuth credential, public-data provider key, DB password, RSA private key는
GitHub variable이나 Terraform 변수로 전달하지 않는다. Secrets Manager의
`oauth-providers`, `public-data-providers` container에 승인된 operator가 값을
직접 넣는다. `secret-bootstrap` task는 DB role마다 별도 container
(`property-runtime-db`, `coordinate-reader-db`, `admin-runtime-db`,
`user-runtime-db`, 각 `*-migrator-db`, `coordinate-importer-db`,
`property-ai-reader-db`, `backup-db`)와 user/admin RSA key pair를 처음 한 번
생성하며 기존 secret version이 있으면 덮어쓰지 않는다. ECS execution role은
자신의 container ARN만 읽고 RDS master secret은 bootstrap task role만 읽는다.

## 1. Terraform bootstrap

AWS operator credential chain을 사용해 local state로 최초 생성한다.

```bash
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap apply \
  -var='state_bucket_name=GLOBALLY_UNIQUE_BUCKET' \
  -var='github_repository=OWNER/REPOSITORY'
```

출력한 state bucket, KMS key, staging/release role ARN을 기록한다. 이후
`backend.s3.tf.example`을 추적되지 않는 `backend.tf`로 복사해 placeholder만
채우고 다음 명령으로 state를 명시적으로 이전한다.

```bash
terraform -chdir=infra/terraform/bootstrap init -migrate-state
```

S3 versioning, KMS encryption, public access block, `.tflock` 생성과 삭제가
확인될 때까지 pre-migration local state를 보호된 recovery artifact로 보관한다.

## 2. 최초 staging foundation

release workflow가 아직 이미지를 게시할 ECR repository를 필요로 하므로
operator가 KMS/ECR registry target을 먼저 apply한다. 이 target-only plan의
형식 검증을 위해 placeholder digest map을 전달할 수 있지만 task definition이나
service에는 적용하지 않는다. ECR 생성 뒤 5절의 첫 image release를 게시하고,
그 manifest의 실제 15개 digest로 foundation full plan을 만든다.

```bash
terraform -chdir=infra/terraform/staging init
terraform -chdir=infra/terraform/staging plan \
  -target='aws_ecr_repository.image' \
  -var-file=registry-bootstrap.auto.tfvars.json -out=registry.tfplan
terraform -chdir=infra/terraform/staging apply registry.tfplan

# 첫 release image 게시 후 actual digest manifest를 사용한다.
terraform -chdir=infra/terraform/staging plan \
  -var-file=foundation.auto.tfvars.json -out=foundation.tfplan
terraform -chdir=infra/terraform/staging apply foundation.tfplan
```

계획에서 public/admin ALB 분리, admin CIDR 제한, private RDS/Valkey, 별도
coordinate-source RDS, ECR, secret container, EFS, private MSK Serverless,
Glue registry, encrypted operations SNS topic, Scheduler failure DLQ가 생성되는지
확인한다. Terraform은 topic과 Glue schema version을 생성하지 않으며 contract
pipeline이 이를 승격한다.
Terraform state와 plan 파일에 secret 값이 없어야 한다.

## 3. Secret과 DB bootstrap

`terraform output -json network`와 `workload_release`에서 cluster, app subnet,
`ops` security group, task definition ARN을 얻는다. `run-ecs-task.sh`는 task가
멈출 때까지 기다리고 모든 container exit code가 0일 때만 성공한다.

```bash
infra/deploy/run-ecs-task.sh \
  CLUSTER_ARN SECRET_BOOTSTRAP_TASK_ARN '["subnet-a","subnet-b"]' '["sg-ops"]'
infra/deploy/run-ecs-task.sh \
  CLUSTER_ARN DATABASE_BOOTSTRAP_TASK_ARN '["subnet-a","subnet-b"]' '["sg-ops"]'
```

그 뒤 4개 migration task를 실행하고 마지막에 `runtime-grants`를 실행한다.
실패한 task가 있으면 service를 시작하지 않는다. stdout, CloudWatch Logs,
ECS override, process argv에 password나 private key가 나타나면 즉시 중단한다.

## 4. ML model 업로드

ML을 사용하지 않으면 `enable_ml=false`를 유지한다. 활성화할 때는 VPC 내부
maintenance host를 app subnet과 `home-search-staging-ml` security group에
연결하고 EFS를 TLS로 mount한다.

1. model 파일을 `/model`의 임시 이름으로 전송한다.
2. 승인된 SHA-256과 비교한다.
3. owner를 runtime UID/GID `10001:10001`, mode를 read-only로 맞춘다.
4. 최종 artifact 이름으로 atomic rename한다.
5. maintenance mount와 host를 제거한 후 ML task를 한 번 실행해 `/health`를
   확인한다.

Model은 repository, image layer, Terraform state, GitHub artifact에 넣지 않는다.
artifact가 없거나 checksum이 다르면 ML entrypoint가 실패해야 한다.

## 5. Image release

1. release commit이 `main`에 포함됐는지 확인한다.
2. `main` push에서 실행된 `ci`가 변경 대상 gate를 `success`, 비대상 gate를
   `skipped`로 완료했는지 확인한다.
3. `vMAJOR.MINOR.PATCH` tag를 만든다.
4. `Publish release images` workflow를 확인한다.

Workflow는 tag/main/check-run을 재검증하고 OIDC로 ECR에 로그인한다. 모든
image를 SHA와 SemVer tag로 게시하되 deployment identity는 digest다. 결과
artifact에는 `release-manifest.json`, image별 SPDX SBOM, Grype JSON이 포함된다.
Critical finding이나 SHA/SemVer digest 불일치는 release 실패다.

## 6. Staging deploy와 migration

`Deploy staging` workflow를 `release_tag`, optional `enable_ml`,
`enable_market_news_public`, `enable_market_news_schedules`,
`enable_user_insights_public`, `enable_property_event_relay_schedule`,
`enable_property_event_retention_schedule`로 실행한다. 한 번 승인해 활성화한
schedule은 이후 release에서도 해당 입력을 `true`로 유지해야 하며, workflow가
이를 Terraform tfvars에 명시적으로 기록한다.
Release manifest의 `build_flags.market_news_enabled`와
`enable_market_news_public`은 반드시 같아야 한다.
`staging` Environment approval 전에 plan 범위와 release evidence를 검토한다.
`property.trade-events.v1`, `property.complex-events.v1`,
`property.insight-events.v1` topic과 contract 등록이 확인되기 전에는
`enable_property_event_relay_schedule=false`를 유지한다. 확인 후 별도 reviewed
plan에서 이를 `true`로 전환하면 provider/coordinate secret이 없는 private
`property-event-relay` task가 5분마다
`propertyEventRelayJob`을 실행한다.
Property Flyway V26과 runtime grant를 확인한 뒤에는
`enable_property_event_retention_schedule=true`로 전환하고, 이후 release에도
같은 값을 유지한다.

고정 순서:

1. 현재 property/admin/user DB backup task와 checksum 생성 성공
2. release one-shot task definition만 등록, service는 미변경
3. property/admin/user/source migration과 `runtime-grants`
4. full Terraform plan 생성 및 workload allowlist 검증
5. 이전 ECS service task ARN/digest capture
6. Terraform apply, ECS stable wait
7. public/admin root, generic C401, actuator 404, admin 인증 경계 smoke
8. staging release manifest 업로드

Migration 실패 시 4번 이후는 실행되지 않는다. 자동 DB down migration은
없다. plan에 RDS, subnet, security group, KMS, S3 등 workload release 범위 밖
변경이 있으면 `verify-terraform-plan.sh`가 차단한다.

## 7. Rollback

Service 안정화나 smoke가 실패하면 workflow가
`deployment-evidence/previous-release.json`의 이전 task definition ARN으로 각
service를 되돌리고 이전 desired count도 함께 복구한 뒤 stable 상태를 기다린다.
`skip_destroy=true`이므로 이전 revision은 rollback용으로 등록 상태를 유지한다.

수동 rehearsal:

```bash
infra/deploy/rollback-services.sh previous-release.json
```

첫 배포에는 이전 service revision이 없으므로 자동 rollback할 수 없다. 이
경우 service를 활성화하기 전 별도 smoke window를 두고, 실패하면 원인을 고친
새 release로 재배포한다. Migration을 rollback하기 위한 down SQL은 실행하지
않는다.

## 8. Backup과 restore rehearsal

- 매일 03:30 KST: property/admin/user custom-format backup
- 매주 일요일 04:30 KST: 최신 3개 backup을 ephemeral PostgreSQL에 복원 검증
- staging retention: 30일
- 제외: `home_search_coordinate_source`

Restore verification은 dump checksum, migration checksum, Flyway success count,
핵심 table row count를 검사한다. 기존 RDS에 연결해 drop/overwrite하지 않는다.
수동 검증은 `restore-verification` task definition을 `run-ecs-task.sh`로 실행한다.

`BackupFailureCount`, `RestoreFailureCount`, `ChecksumMismatchCount`,
`BackupAgeSeconds`, `RestoreDurationSeconds`, Scheduler `TargetErrorCount` alarm을
확인한다. `BackupAgeSeconds`가 없거나 26시간을 넘으면 정상으로 간주하지 않는다.
모든 Scheduler는 retry 소진 시 SQS-managed SSE DLQ에 실패 payload를 14일
보관한다. `ApproximateNumberOfMessagesVisible`이 1 이상이면 operations SNS
alarm이 발생해야 한다. 실제 apply 후 `streaming.operations_topic_arn`에 승인된
구독을 연결하고 확인(confirmed) 상태와 test alarm 수신을 evidence로 남긴다.
구독이 없거나 미확인 상태이면 staging 실증을 시작하지 않는다.

## 9. 성능 evidence

성공한 staging deploy 뒤와 매주 k6 evidence workflow가 실행된다. 결과는
staging release manifest와 함께 보관된다. map cold는 cache를 강제 삭제한 값이
아니라 VU별 첫 요청이고, warm은 같은 VU의 반복 요청이다. prediction은 고정
READY id와 cache-miss id를 분리한다.

초기 job은 merge-blocking이 아니다. 동일 조건에서 3회 이상 안정된 baseline이
쌓인 뒤 threshold와 data fixture를 재검토하고 별도 PR에서 merge gate 승격을
결정한다.

## 검증 공백과 production 경계

- 이 repository 작업만으로 실제 AWS apply, DNS, ACM validation, OAuth provider,
  Kakao provider, model artifact upload를 실행하지 않는다.
- cold cache는 destructive cache flush 없이 first-request proxy로 측정한다.
- 실제 staging backup/restore와 rollback rehearsal은 environment 생성 후 evidence
  artifact로 추가해야 한다.
- production account, production data, production DNS와 traffic cutover는 별도
  승인 계획 없이는 실행하지 않는다.

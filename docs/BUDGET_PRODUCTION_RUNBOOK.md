# Budget Production Runbook

## 준비

GitHub Environment를 다음처럼 만든다.

- `budget-production-plan`: reviewer 없이 read-only plan
- `budget-production`: `kwongwangjae` required reviewer

Repository/Environment variables:

- `AWS_BUDGET_PRODUCTION_PLAN_ROLE_ARN`
- `AWS_BUDGET_PRODUCTION_APPLY_ROLE_ARN`
- `AWS_BUDGET_PRODUCTION_DEPLOY_ROLE_ARN`
- `BUDGET_PRODUCTION_TF_STATE_BUCKET`, `BUDGET_PRODUCTION_TF_STATE_KMS_ARN`
- `BUDGET_PRODUCTION_HOSTED_ZONE_ID`, `BUDGET_PRODUCTION_ALARM_EMAIL`
- `BUDGET_PRODUCTION_ACCEPTANCE_EVIDENCE_S3_URI`

Release Environment의 `KAKAO_MAP_APP_KEY`에는 Kakao Developers의 browser 공개
식별자인 **JavaScript key**를 넣는다. OAuth REST API key나 Native app key를 넣으면
SDK가 `401`을 반환해 지도가 렌더링되지 않는다. Kakao Web 플랫폼에는
`https://homesearch.world`를 등록한다.

Acceptance URI는 `s3://home-search-budget-production-backup-<account-id>/budget-production/acceptance/<release-tag>`
형식으로 고정한다. 그 prefix에는 `acceptance.json`, `security.json`,
`observability.json`, `release-exceptions.json`이 있어야 하고 각 파일의
`release_tag`와 `commit_sha`가 실행 입력과 정확히 일치해야 한다. 스키마와 gate는
`infra/deploy/build-budget-production-ready-evidence.sh`가 단일 source다. 실제
secret, user ID, JWT, prompt/query/answer, private key는 evidence에 넣지 않는다.

## 최초 배포

1. exact `BUDGET_PRODUCTION_HOSTED_ZONE_ID`를
   `budget_production_hosted_zone_id`로 전달해 bootstrap state를 apply하고 budget
   OIDC role을 만든다. plan/apply의 budget SSM prefix read는 provider refresh에만
   사용하며 state/plan/log에 복호화 값을 남기지 않는다.
2. `Deploy budget production` workflow를 `operation=registry`로 실행한다. plan을
   확인하고 protected apply를 승인한다. 이 단계는 budget Postgres/Valkey ECR만 만든다.
3. same-origin frontend가 포함된 새 tag를 발행한다. `v1.0.4`는 사용하지 않는다.
   release evidence가 17 application + 2 platform digest/SBOM/Grype를 포함하는지 확인한다.
4. release/import 입력 없이 workflow를 `operation=foundation`으로 실행한다. plan의
   zero-destroy와 `$95/$99` cost gate를 확인하고 protected apply를 승인한다. 이때
   선택한 exact AMI와 stable AZ를 state output에 고정한다. 이후 deploy는 최신 권장
   AMI/AZ를 다시 선택하지 않고 이 값을 재사용해야 한다. 이 단계부터 EC2/EBS/EIP 등
   월간 비용이 발생하며 public DNS와 data/application service는 아직 비활성이다.
5. foundation output의 backup/reference bucket, SSM parameter, instance/EBS/EIP를
   기록한다. SSM의 외부 credential parameter를 채우되 Terraform에는 값을 전달하지 않는다.
   전국 RTMS daily refresh에는 Systems Manager Parameter Store의
   `/home-search/budget-production/property/apt-service-key`에 공공데이터포털
   `APT_SERVICE_KEY`를 SecureString으로 넣는다. local `.env`는 운영 ECS가 읽지 않는다.
   foundation apply는 Terraform state만 신뢰하지 않고 AWS Budgets API에서 actual `$50`,
   forecast `$80/$100` 알림과 승인 email subscriber를 exact 검증한다. provider create가
   중간 취소되어 budget만 남은 경우에는 누락 알림만 보정하고, 예상 밖 threshold나
   subscriber가 있으면 삭제하지 않고 중단한다. SNS alarm topic의 별도 subscription도
   email에서 `Confirm subscription`을 완료하고 live ARN이 `PendingConfirmation`이 아닌지
   확인한다.
   Kakao console에는 `homesearch.world`와 staging origin, callback을 등록한다. 현재
   초기 enablement set은 Kakao만 사용하며 비활성 Google/Naver credential은 readiness에서
   요구하지 않는다. redirect URI와 secret을 준비한 provider만 이후 set에 추가한다.
6. 승인된 backup image로 Property+Reference data-only artifact를 만들고 foundation의
   backup bucket에 업로드한다. manifest SHA-256과 raw-before-normalized, catalog allowlist를
   확인한다. current 24h SLO, SNS test alarm 수신, Kakao/OAuth console, network/ACL probe를
   acceptance prefix에 업로드한다.
7. workflow를 `operation=deploy`와 exact tag/SHA, migration S3 prefix/SHA로 실행한다.
8. foundation plan이 state에 고정된 동일 AMI/AZ를 사용하고 zero-destroy인지 다시
   확인해 apply를 승인한다. 기존 data phase에서 새 release로 재개할 때는
   `skip_destroy=true`인 exact budget task definition의 image digest/Release evidence
   revision만 예외다. 같은 ECR repository, 불변 role/port/env/command, data import의
   동일 bucket release evidence suffix를 검증하며 이전 ECS revision은 보존한다.
9. workflow가 secret bootstrap/readiness, Postgres/Valkey, Flyway, data-only import,
   reconcile, marker projection, logical backup을 순서대로 실행한다.
   post-cutover schedule이 활성화되면 전국 RTMS daily refresh는 매일 07:30 KST에
   실행되고 그 실행의 daily/rolling insight와 marker projection을 함께 갱신한다.
10. import 동안만 Unlimited를 사용한다. 실패 여부와 무관하게 다음 step에서
   `standard`를 재설정하는지 확인한다. rollout job timeout까지 대비한 별도
   `budget-production-credit-cleanup` protected job도 host를 다시 찾아 Standard를
   확인하며, 이 job이 성공하지 않으면 DNS plan은 시작하지 않는다.
11. ingress 없는 recovery instance의 logical restore와 EBS clone restore가 모두
    4시간 이내인지 확인한다.
12. private service, public gateway, `curl --resolve`와 CPU credit 216 gate가
    통과하면 별도 DNS plan을 검토한다.
13. `public_dns_enable_approved=true`일 때만 마지막 protected job을 승인한다.
    A record와 backup schedule 적용 뒤 `BUDGET_PRODUCTION_READY.json`이 생성된다.

중단 조건은 보존형 task definition release revision 외 plan의 destroy/금지 resource,
비용 초과, `UNSET`, digest/SBOM 누락,
staging origin, disk headroom 부족, ACL/IMDS/public port 실패, reconcile/restore
mismatch, 미확인 SNS/Kakao/OAuth evidence, credit `standard` 미복원이다.

Foundation apply가 중간 실패하면 기존 plan을 다시 apply하지 않는다. 새 plan은
동일 tag의 host, state output, 부분 생성된 data EBS/subnet 순으로 AMI/AZ pin을
재사용하며 tag 일치 resource가 여러 개거나 AZ가 다르면 fail closed한다. 새 plan의
destroy가 0이고 기존 data EBS/EIP/VPC를 유지하는지 확인한 뒤에만 재승인한다.
이전 rollout이 data phase에서 PostgreSQL/Valkey를 시작한 뒤 실패했다면 state의
`data_services_enabled=true`를 재개 가능한 상태로 인정한다. 새 reviewed foundation
plan은 기존 값을 preflight evidence에 기록하고 `data_services_enabled=false`를 목표로
두 service를 먼저 data-dark로 수렴시킨 뒤 task definition 등록과 bootstrap을 다시
시작한다. `true|false` 이외의 output, phase 후퇴, destroy가 있으면 그대로 중단한다.
실패한 provider create가 live resource를 남기고 state instance만 `tainted`로 표시한
경우에만 `recover_tainted_ssm_state=true`로 foundation workflow를 다시 실행한다.
이 입력은 protected apply role로 먼저 실행되며, 허용 대상은
`aws_ssm_parameter.runtime`과 exact count index의 `aws_internet_gateway.this[0]`,
`aws_s3_bucket.reference_raw[0]`, `aws_security_group.host[0]`,
`aws_security_group.recovery[0]`뿐이다. parameter는 exact budget prefix,
`SecureString`, `DataClass=secret`, `ParameterStatus=out-of-band`를 확인한다. foundation
resource는 state ID와 live VPC CIDR/소유 태그, IGW 연결/태그, bucket account명/region,
security group 이름/설명/무인바운드를 전부 확인한 뒤 state taint 표시만 제거한다.
다른 taint나 live metadata 불일치가 하나라도 있으면 아무 state도 바꾸지 않고
중단한다. parameter 값과 live resource는 읽거나 갱신하지 않으며 정상 복구 후 다음
실행부터 입력은 다시 `false`로 둔다.

운영 복구 중 `/home-search/budget-production/property/apt-service-key` 컨테이너를
먼저 만들었다면 같은 입력으로 workflow를 한 번 실행한다. 복구 step은 exact 이름,
`SecureString`, 보호 태그를 확인한 뒤 값은 읽지 않고 해당 resource만 Terraform
state에 import한다. 다른 parameter key나 메타데이터 불일치는 fail closed한다.

초기 data service 기동 전에 생성된 64자 hex parameter에 단일 LF가 포함됐다는
Valkey URL-safe 오류가 확인되면 import를 시작하지 않는다. 먼저 exact Terraform
plan으로 `deployment_phase=data`, `data_services_enabled=false`를 적용해 PostgreSQL과
Valkey의 desired/running/pending count가 모두 0인지 확인한다. 그 뒤
`infra/deploy/run-budget-generated-value-normalization.sh`를 기존 reviewed
`secret-bootstrap` task definition ARN과 run-specific `started-by`로 한 번 실행한다.
runner는 기존 reviewed image와 최소 SSM task role을 재사용하고, AWS 내부에서만
17개 생성값의 끝 LF와 3개 AI DSN을 정규화하며 값은 stdout, task definition,
Terraform state에 넣지 않는다. task 종료 후 임시 task definition은 deregister한다.
service가 data-dark가 아니거나 값 검증 preflight가 실패하면 parameter를 수정하지 않고
중단한다. `PutParameter` 중간 실패는 같은 idempotent runner를 재실행해 수렴시킨다.
다음 deploy는 새 plan과 protected approval로 재시작하며, platform waiter
후 PostgreSQL/Valkey가 각각 desired 1, running 1, pending 0인지 별도 assertion한다.
정규화 전 password로 이미 초기화된 retained PostgreSQL cluster는 새
`budget-postgres` image가 정상 TCP listener를 열기 전에 local trust Unix socket으로만
bootstrap/service role 13개의 password를 현재 runtime parameter와 idempotent하게
맞춘다. 이 과정은 database, schema, table, row를 삭제하거나 다시 만들지 않으며
password를 command line이나 log에 출력하지 않는다. role 누락 또는 reconcile 실패 시
container가 fail closed하므로 Flyway/import를 실행하지 않는다.

## Application 증분 rollout

최초 `BUDGET_PRODUCTION_READY.json`이 생성된 public phase에서는
`Rollout budget production` workflow만 사용한다. bootstrap state에는 먼저 exact
workflow claim을 반영한다. 이 trust 확장은 `main`과 `refs/tags/v*`,
`budget-production-plan`/`budget-production` Environment에 계속 묶이며 다른 workflow나
branch에는 권한을 주지 않는다.

1. 병합된 `main` commit에 아직 사용되지 않은 exact release tag를 붙이고 17개
   application image와 2개 platform manifest, SBOM, provenance가 성공한 뒤 tag/SHA와
   `property_migration_target=40`을 입력한다.
2. plan job이 live phase `public`, DNS enablement, PostgreSQL/Valkey health, 26시간 이내
   backup, root 8GiB/data 20GiB 여유 공간을 확인한다. release의 application digest만
   사용하고 live PostgreSQL/Valkey digest 두 개는 그대로 tfvars에 넣는다.
3. plan은 application/one-shot task definition과 application service revision,
   `rtms-daily-refresh`의 제한된 scheduler/IAM만 허용한다. EC2, EBS, EIP, VPC, S3,
   DNS, platform service 변경 또는 보존형 task definition 외 delete가 있으면 승인하지 않는다.
4. protected 승인 뒤 기존 application task definition ARN과 desired count를 캡처한다.
   새 backup one-shot의 read-only audit가 V39 exact history와
   `complex`/`complex_name_alias`/`parcel`/`trade` row count·식별자 checksum을 S3의
   release별 `logical/rollout-audit` prefix에 기록한다.
5. property Flyway만 `target=40 migrate`, `target=40 validate` 순서로 실행한다. V40의
   `lock_timeout=5s` 실패, history drift, failed/missing/out-of-order migration이면
   application rollout을 시작하지 않는다. after audit는 V40 history와 before snapshot의
   row count/checksum 동일성을 함께 강제한다.
6. `property-api → user-api → ai → chat-bff`를 각각 stable까지 교체한다. 기존
   public gateway를 통해 20개 동시 prefix 검색과 backend smoke가 성공한 뒤
   `public-gateway`를 마지막으로 교체한다.
7. 남은 inactive application/one-shot revision과 RTMS scheduler/IAM을 reviewed plan으로
   수렴시킨다. refresh-only 뒤 zero-drift plan, 기존 `homesearch.world` DNS의 public
   exact/prefix smoke를 확인한다. DNS record에는 apply하지 않는다.
8. 60분 동안 분당 exact/prefix synthetic 검색을 실행한다. 5xx 0, exact p95 500ms 이하,
   prefix p95 1초 이하, CPU 80% 미만, memory 90% 미만을 만족해야
   `BUDGET_PRODUCTION_INCREMENTAL_READY.json`을 만든다. 지도 p95는 관측만 하고 이
   증분 rollout의 차단 조건으로 사용하지 않는다.

이 workflow는 전국 data import, logical/EBS restore rehearsal, Unlimited CPU credit 전환,
platform service 재시작, data volume 변경을 실행하지 않는다. V40은 additive index이므로
down migration하지 않는다. migration 이후 실패하면 캡처한 이전 revision과 desired
count로 모든 application service를 복원하며 DNS, PostgreSQL row, backup, data volume은
변경하지 않는다.

## SSM과 Admin

SSH는 사용하지 않는다. Session Manager로 host에 접속한다. Admin API/gateway는
desired 0이며 승인된 maintenance window에만 ECS desired count를 1로 올리고 SSM
port forwarding으로 18001에 접근한다. 종료 시 두 service를 0으로 되돌리고
task/event evidence를 남긴다. 18001, 18081을 Security Group에 열지 않는다.

## Certificate renewal

ACM renewal event가 `home-search-budget-production-configure-edge` document를
호출한다. 실패 alarm 시 certificate ARN/renewal 상태와 SSM association을 확인하고
document를 exact host instance에 재실행한다. output log에 passphrase/key/body를
복사하지 않는다. `nginx -t` 실패 시 기존 key/config를 유지하고 DNS는 바꾸지 않는다.

## Backup과 restore drill

- 01:30 KST: DLM data EBS snapshot, 최근 7개
- 03:30 KST: 4개 logical DB custom dump, Object Lock Governance 35일
- 매월: logical restore runner
- 분기: EBS clone/prewarm/restore runner

`infra/budget/run-recovery-rehearsal.sh`는 7개 인자면 logical, snapshot/postgres
digest를 추가하면 EBS mode다. instance와 clone volume은 `Purpose`/`RunId`를 다시
확인한 뒤에만 종료/삭제한다. 원본 data volume과 live PostgreSQL을 mount하거나
overwrite하지 않는다.

## Host replacement

1. incident를 선언하고 instance ID, EIP allocation, data volume ID/AZ, exact AMI를 기록한다.
2. 기존 instance를 stop한다. data volume detach는 상태 확인과 별도 승인을 거친다.
3. 같은 AZ/exact AMI의 termination-protected `t3a.large`를 만든다.
4. 기존 data EBS를 연결한다. filesystem을 format하지 않는다.
5. host bootstrap, edge certificate, observability SSM document를 재적용한다.
6. platform→private→public 순서로 task를 확인하고 EIP를 재연결한다.
7. `curl --resolve`, marker parity, ACL/IMDS, backup age를 검증한다. 목표 RTO는 1시간이다.

## Data recovery와 확장

data volume 손상은 최근 snapshot에서 새 volume을 만들고 block을 순차 read해
prewarm한다. logical 손상은 dump를 새 volume/DB에 restore한다. 기존 volume을
덮어쓰지 않는다. free space 20GiB 미만이면 DNS/cutover를 중단하고 `ModifyVolume`
후 XFS online grow를 별도 reviewed plan으로 수행한다.

## HA 승격과 rollback

90일 review에서 비용/SLO/availability가 맞지 않으면 기존 HA Production root로
동일 digest와 data-only migration을 배포한다. 검증 후 `homesearch.world` A record를
state transfer 또는 explicit import로 한 owner에게만 넘긴다.

Application rollback은 이전 task definition, AI graph는 동일 digest의 mode `off`,
host config는 이전 SSM bundle SHA를 사용한다. DB down migration과
`terraform destroy`는 사용하지 않는다.

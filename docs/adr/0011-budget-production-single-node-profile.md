# ADR 0011: 월 $100 단일 노드 budget-production profile

- 상태: Accepted
- 결정일: 2026-07-29
- 재검토일: 2026-10-27
- 범위: `infra/terraform/budget-production`, release/deploy workflow, 운영 evidence

> ADR 0010 번호는 이미 Agentic 챗봇 결정에 사용 중이므로 이 결정은 0011을
> 사용한다.

## Context

기존 HA Production 설계는 다중 AZ, managed database/cache, ALB/WAF/NAT를
전제로 한다. 이 구조는 월 AWS 세전 `$100` 제약을 만족하지 않는다. 기존
Production과 staging의 state·resource를 재해석하거나 축소하면 rollback과
소유권 추적도 불명확해진다.

## Decision

- `budget-production`을 기존 Production과 병렬인 별도 profile로 둔다.
- 서울 리전의 단일 AZ, 단일 `t3a.large`, EIP, 30GiB root와 파괴 방지된
  80GiB data EBS를 사용한다. ASG, ALB, NAT, RDS, ElastiCache, MSK는 만들지 않는다.
- 하나의 PostgreSQL process를 사용하되 Property/User/Admin/AI DB와 role을
  분리한다. Coordinate DB와 전국 snapshot은 만들지 않는다.
- ECS EC2 `bridge`와 고정 host port를 사용한다. 외부 ingress는 80/443뿐이며
  host Nginx만 loopback의 public gateway를 호출한다.
- state key는 `home-search/budget-production/terraform.tfstate`로 고정하고
  plan/apply/deploy OIDC role을 분리한다. deploy role은 Terraform state를 읽지 않는다.
- release는 17개 application image와 2개 budget platform image의 같은
  `linux/amd64` digest 집합이어야 한다. `v1.0.4`는 staging origin 위험 때문에
  배포하지 않는다.
- 평시는 CPU credit `standard`다. 승인된 import/recovery만 최대 8시간
  `unlimited`를 허용하며 종료 경로에서 `standard`를 재검증한다.
- daily EBS snapshot과 35일 logical dump를 병행한다. 복구 목표는
  `RPO 24h / RTO 4h`이며 live DB를 in-place overwrite하지 않는다.
- AWS-managed `aws/ebs`, `aws/s3` key를 비용 예외로 사용한다. IAM, bucket
  policy, parameter ARN, exact state deny가 보완 통제다.
- 최종 evidence 상태는 기존 `READY_TO_DEPLOY`와 다른
  `BUDGET_PRODUCTION_READY`다.

ADR 0007의 MSK-first runtime, ADR 0008의 service별 물리 cluster, ADR 0009의
HA Production 격리는 budget 환경에서만 예외다. 이벤트 outbox는 보존하지만
relay/worker/MSK는 활성화하지 않는다. 기존 HA Production 결정은 그대로다.

## API와 OAuth compatibility

Frontend User API는 production build에서 browser same-origin을 사용한다.
공개 URL, method, field, unit, `complex_id` 의미는 바꾸지 않는다.

초기 Kakao-only 제안은 현재 `docs/API_CONTRACT.md`가 Google/Kakao/Naver를
보장하므로 채택하지 않는다. 세 provider의 redirect/secret readiness를 모두
요구한다. provider 축소가 필요하면 먼저 별도 public contract 변경 승인을 받는다.

## Consequences

- 단일 host/AZ 장애는 전체 중단을 일으킨다. automatic recovery, EIP, EBS
  snapshot, logical dump와 수동 replacement runbook이 이를 완화한다.
- 고정 port 때문에 deployment 중 30~90초 interruption을 허용한다.
- WAF와 task별 SG가 없다. Nginx 제한, DB role, Valkey ACL, task별 execution
  role, IMDS 차단, no-SSH가 보완 통제다.
- 비용 gate를 만족하지 못하거나 90일 관측에서 CPU/memory/SLO가 맞지 않으면
  HA Production으로 승격하고 이 profile의 DNS를 이전한다.

## Rollback

이전 task definition/digest로 되돌리고 DB down migration은 하지 않는다.
data 복구는 snapshot/dump에서 새 volume을 만들어 전환한다. 일반 rollback에
`terraform destroy`를 사용하지 않으며 data EBS, backup bucket, SSM parameter를
삭제하지 않는다.

# Budget Production Architecture

## 목적과 경계

`budget-production`은 월 AWS 증분 `$95` 이하를 우선하는 단일 노드 profile이다.
기존 `infra/terraform/production`, staging state, application ECR 17개는 소유하지
않는다. 공개 계약은 `docs/API_CONTRACT.md` 그대로다.

```text
Internet -> Route53 A -> EIP -> host Nginx :443 -> 127.0.0.1:18000
                                               -> ECS public-gateway
                                                  -> Property/User/BFF/AI
Docker bridge 172.31.255.1 -> PostgreSQL :15432, Valkey :16379
                              -> /srv/home-search (encrypted data EBS)
SSM -> host maintenance     S3/DLM <- logical dump/EBS snapshot
```

## Resource ownership

| 소유자 | 관리 대상 |
|---|---|
| bootstrap state | state S3/KMS, budget plan/apply/deploy OIDC role |
| budget state | VPC/subnet/EIP/EC2/EBS/ECS, platform ECR, S3, SSM, ACM, DNS, alarm/cost |
| staging state | 기존 application ECR 17개 |
| 기존 production state | 변경 없음 |
| 외부 입력 | hosted zone ID, external credential 값, 승인된 acceptance evidence |

State는 `home-search/budget-production/terraform.tfstate`와 해당 `.tflock`만
사용한다. workspace는 `default`만 허용한다. Terraform state에는 실제 secret
값을 넣지 않고 `UNSET` parameter container와 `ignore_changes`만 관리한다.

## Network와 port

VPC는 `10.44.0.0/24`, subnet은 한 AZ의 `10.44.0.0/26`, Docker bridge는
`172.31.255.0/24`다. SSH key/TCP 22는 없고 SSM agent가 public HTTPS endpoint로
outbound 연결한다.

| Workload | Container | Host | Desired |
|---|---:|---:|---:|
| public-gateway | 8080 | 18000 | public에서 1 |
| admin-gateway | 8080 | 18001 | 0 |
| property-api | 8080 | 18080 | 1 |
| admin-api | 8081 | 18081 | 0 |
| user-api | 8082 | 18082 | 1 |
| chat-bff | 8083 | 18083 | 1 |
| AI | 8000 | 18084 | 1 |
| ML | 8001 | 18085 | 0 |
| PostgreSQL | 5432 | 15432 | 1 |
| Valkey | 6379 | 16379 | 1 |

Security Group public ingress는 TCP 80/443뿐이다. `DOCKER-USER`는 container의
`169.254.169.254` 접근을 거부한다. privileged/host PID/IPC/`NET_RAW`는 허용하지
않는다. 실행 role은 자신의 SSM parameter만 읽고 runtime role은 secret을 읽지 않는다.

## Edge와 secret flow

Host Nginx는 unknown Host를 444로 거부하고 1MiB body, TLS 1.2/1.3, security
header, JSON access log를 적용한다. chatbot JSON/SSE route는 drain marker가 있으면
503이며 SSE buffering을 끄고 read timeout 75초를 사용한다.

ACM은 `homesearch.world` 단일 exportable certificate를 발급한다. passphrase는
SSM SecureString이고 host instance role만 해당 ARN을 읽는다. SSM document 안에서
export/decrypt한 key는 host에 0400으로 설치되고 state, artifact, CloudWatch log에
기록되지 않는다. renewal event는 같은 document를 실행한 뒤 `nginx -t`와 graceful
reload를 수행한다.

생성형 DB/Valkey/key secret은 one-shot bootstrap이 `UNSET`일 때만 쓴다. 외부
Kakao/OpenAI/OAuth 값은 operator가 별도 채운다. 현재 public contract 때문에
Google/Kakao/Naver 모두 readiness 대상이다.

## Data와 availability

`/srv/home-search`의 하위 경로는 `postgres`, `valkey`, `backup-staging`,
`runtime`이다. bootstrap은 expected volume ID/AZ와 filesystem signature를 먼저
검증하고 기존 filesystem이면 mount만 한다. 완전히 빈 volume만 XFS로 format하며
불확실하면 중단한다.

하나의 PostgreSQL에 `home_search`, `home_search_user`, `home_search_admin`,
`home_search_ai`를 두고 runtime/migrator/importer/backup role을 분리한다.
cross-DB FK/join은 금지한다. Valkey는 cache/rate-limit 전용이며 persistence를
끄고 Property/BFF ACL과 prefix를 분리한다.

EC2 hardware failure는 automatic recovery가 instance/EIP/EBS를 유지한다. OS
손상은 exact AMI replacement와 기존 data EBS 재연결, data 손상은 snapshot clone,
논리 손상은 logical dump를 새 DB에 restore한다.

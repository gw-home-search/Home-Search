# Budget Production Threat Model

## 자산과 trust boundary

보호 대상은 raw/normalized trade, DB/Valkey credential, OAuth/OpenAI credential,
JWT private key, ACM private key, backup, Terraform state, release digest/evidence다.
주요 경계는 Internet↔host Nginx, host↔ECS bridge, task↔SSM, task↔DB/Valkey,
GitHub OIDC↔AWS role, live EBS↔backup/recovery다.

| 위협 | 영향 | 통제 | 잔여 위험 |
|---|---|---|---|
| public host compromise | 전체 단일 노드 장악 | 80/443만, no SSH, IMDSv2, container IMDS reject, no privileged/NET_RAW | 단일 host blast radius |
| bridge lateral movement | task 간 port 접근 | DB role, Valkey ACL/prefix, task별 execution role, runtime secret read 금지 | task별 SG 없음 |
| secret exfiltration | DB/OAuth/JWT/ACM 탈취 | SSM ARN allowlist, `value_wo` `UNSET`, no Terraform state value, 0400 key, log/artifact scan | provider refresh 때문에 plan/apply role은 budget prefix를 일시 복호화할 수 있고 host root는 certificate 접근 가능 |
| EBS loss/corruption | 모든 DB 중단/손실 | encrypted protected data EBS, daily snapshot, logical dump, clone restore | RPO 최대 24h |
| backup tampering/deletion | 복구 불가 | versioning, Object Lock Governance 35일, checksum/head verify, TLS-only policy | AWS-managed KMS key policy 격리 약화 |
| release substitution | 악성/환경 종속 image | exact tag+SHA, 17+2 digest, SBOM/Grype, staging-origin scan, immutable ECR | scanner 미탐 가능 |
| OIDC/state confusion | 다른 환경 파괴 | exact workflow/environment claims, state object deny, budget ARN/tag mutation deny, deploy state deny, zero-destroy verifier | hosted zone처럼 외부 입력 resource는 protected plan/apply 승인에 의존 |
| Unlimited credit 잔류 | 비용 초과 | trap + in-job always + cross-job cleanup, explicit Standard assertion, readiness gate | workflow 전체 취소나 AWS API 장애 시 수동 확인 필요 |
| L7 abuse/WAF 부재 | 비용/latency/DoS | Nginx 1MiB/rate/connection limit, app bounds, Shield Standard | 정교한 WAF rule 없음 |
| backup/recovery runner 오대상 | 정상 resource 삭제 | exact instance/volume ID와 Environment/Purpose/RunId tag 재검증, create-time tag IAM | 같은 승인 실행 안의 recovery resource 오용 가능 |

## Security acceptance

- public probe에서 80/443 외 port가 닫혀야 한다.
- `/internal`, Admin, actuator, metrics, direct AI가 공개 route에서 거부돼야 한다.
- container IMDS, cross-database role, Valkey cross-prefix probe가 실패해야 한다.
- plan/state/log/artifact에 password, token, private key, certificate export body가 없어야 한다.
- backup checksum, logical/EBS restore, marker parity가 pass여야 한다.
- `security.json`은 findings-first review 결과와
  `security-audit: 지적사항 = none`을 포함해야 한다.

보안 지적사항이 하나라도 있으면 readiness builder가 `none`을 만들지 않는다.
수용 가능한 잔여 위험은 `listed`로 기록하고 별도 승인 전 DNS를 적용하지 않는다.
